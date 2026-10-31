"""Evidence crop builder: one sanitised crop per declaration, plus an annotated overview image.

Crops are re-encoded from pixels (no EXIF / metadata survives) and kept small so the whole report can
be stored with the inspection record.
"""

from __future__ import annotations

import base64

import cv2
import numpy as np

STATUS_COLOURS = {  # BGR
    "PASS": (74, 163, 22),
    "FAIL": (77, 72, 229),
    "REVIEW": (11, 158, 245),
    "NOT_APPLICABLE": (139, 116, 100),
}

FIELD_LABELS = {
    "mrp": "MRP declaration",
    "net_quantity": "Net quantity",
    "mfg_date": "Manufacture / packing date",
    "best_before": "Best before / use-by",
    "manufacturer": "Manufacturer / packer",
    "customer_care": "Consumer care",
    "unit_sale_price": "Unit sale price",
    "origin": "Country of origin",
    "generic_name": "Generic name",
    "batch_no": "Batch / lot number",
    "fssai_license": "FSSAI licence",
    "calibration": "ArUco calibration marker",
}


def encode_jpeg(img: np.ndarray, max_side: int, quality: int) -> str:
    h, w = img.shape[:2]
    s = max_side / max(h, w)
    if s < 1:
        img = cv2.resize(img, (max(1, int(w * s)), max(1, int(h * s))), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality, cv2.IMWRITE_JPEG_OPTIMIZE, 1])
    if not ok:
        return ""
    return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode()


def union_box(boxes: list) -> tuple[int, int, int, int] | None:
    boxes = [b for b in boxes if b]
    if not boxes:
        return None
    x0 = min(b[0] for b in boxes)
    y0 = min(b[1] for b in boxes)
    x1 = max(b[0] + b[2] for b in boxes)
    y1 = max(b[1] + b[3] for b in boxes)
    return x0, y0, x1 - x0, y1 - y0


def field_boxes(decl: dict, lines) -> dict[str, tuple[int, int, int, int]]:
    out = {}
    for key, value in decl.items():
        if key.startswith("_") or not isinstance(value, dict):
            continue
        idx = list(value.get("lines") or [])
        if key == "mrp":
            idx += value.get("tax_lines") or []
        if key == "origin":
            idx += value.get("import_lines") or []
        box = union_box([lines[i].box for i in sorted(set(idx)) if 0 <= i < len(lines)])
        if box:
            out[key] = box
    return out


def build(img: np.ndarray, boxes: dict[str, tuple], field_status: dict[str, str], field_rules: dict[str, list[str]],
          confidences: dict[str, float]) -> tuple[list[dict], dict[str, str]]:
    evidence, ids = [], {}
    h, w = img.shape[:2]
    for n, (key, (x, y, bw, bh)) in enumerate(sorted(boxes.items(), key=lambda kv: (kv[1][1], kv[1][0])), start=1):
        pad = max(10, int(bh * 0.35))
        x0, y0 = max(0, x - pad), max(0, y - pad)
        x1, y1 = min(w, x + bw + pad), min(h, y + bh + pad)
        crop = img[y0:y1, x0:x1].copy()
        colour = STATUS_COLOURS.get(field_status.get(key, "PASS"), STATUS_COLOURS["PASS"])
        cv2.rectangle(crop, (x - x0, y - y0), (x - x0 + bw, y - y0 + bh), colour, max(2, bh // 18))
        eid = f"E{n}"
        ids[key] = eid
        evidence.append({
            "id": eid,
            "field": key,
            "label": FIELD_LABELS.get(key, key.replace("_", " ").title()),
            "rule_ids": sorted(field_rules.get(key, [])),
            "status": field_status.get(key),
            "box": [int(x), int(y), int(bw), int(bh)],
            "confidence": round(confidences.get(key, 0.0), 3),
            "image": encode_jpeg(crop, 420, 74),
        })
    return evidence, ids


def annotate(img: np.ndarray, evidence: list[dict]) -> str:
    canvas = img.copy()
    h, w = canvas.shape[:2]
    thick = max(2, int(max(h, w) / 500))
    for ev in evidence:
        x, y, bw, bh = ev["box"]
        colour = STATUS_COLOURS.get(ev.get("status") or "PASS", STATUS_COLOURS["PASS"])
        cv2.rectangle(canvas, (x, y), (x + bw, y + bh), colour, thick)
        label = ev["id"]
        scale = max(0.6, max(h, w) / 1600)
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
        ty = max(th + 6, y - 4)
        cv2.rectangle(canvas, (x, ty - th - 6), (x + tw + 8, ty + 2), colour, -1)
        cv2.putText(canvas, label, (x + 4, ty - 2), cv2.FONT_HERSHEY_SIMPLEX, scale, (255, 255, 255), thick, cv2.LINE_AA)
    return encode_jpeg(canvas, 960, 70)
