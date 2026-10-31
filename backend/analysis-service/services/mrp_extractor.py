"""Robust MRP extraction from OCR evidence with spatial label context."""

from __future__ import annotations

import re
from typing import Any

MRP_LABEL = re.compile(
    r"\b(?:M\s*\.?\s*R\s*\.?\s*P\.?|MAX(?:IMUM)?\s+RETAIL\s+PRICE|RETAIL\s+SALE\s+PRICE)\b",
    re.I,
)
PRICE = re.compile(r"(?:₹|\bRS\.?\b|\bINR\b)?\s*(\d{1,6}(?:\.\d{1,2})?)\s*(?:/-)?", re.I)
UNIT_PRICE = re.compile(
    r"\b(?:USP|UNIT\s+SALE\s+PRICE)\b|\bPER\s+(?:G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT)\b",
    re.I,
)


def _box_center(box: Any) -> tuple[float, float] | None:
    if not isinstance(box, (list, tuple)) or len(box) < 4:
        return None
    try:
        x1, y1, x2, y2 = map(float, box[:4])
        return ((x1 + x2) / 2.0, (y1 + y2) / 2.0)
    except (TypeError, ValueError):
        return None


def _items(fields: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    for index, raw in enumerate(fields.get("ocr_evidence") or []):
        if not isinstance(raw, dict):
            continue
        text = str(raw.get("normalized_text") or raw.get("raw_text") or "").strip()
        if text:
            result.append({
                **raw,
                "text": text,
                "index": index,
                "confidence": float(raw.get("confidence", 0) or 0),
            })
    return result


def _candidate_score(label: dict[str, Any], candidate: dict[str, Any], amount: float) -> float:
    score = candidate["confidence"] * 20.0
    text = candidate["text"]

    # A currency marker on the amount is strong evidence that this is a price.
    if re.search(r"₹|\bRS\.?\b|\bINR\b|/-", text, re.I):
        score += 45
    elif amount < 10:
        # A lone nearby digit is usually a barcode/check digit. Sub-ten MRP
        # values require an explicit currency marker to be reliable.
        return -10_000
    # MRP values printed as money commonly retain two decimal places.
    if re.fullmatch(r"\d{1,6}\.\d{2}", text):
        score += 30
    elif re.fullmatch(r"\d{1,6}(?:\.\d{1,2})?", text):
        score += 12

    # Never select unit-sale-price or nutrition percentage evidence.
    if UNIT_PRICE.search(text) or "%" in text:
        return -10_000
    if candidate.get("recovered_from_declaration_crop"):
        score += 80

    label_center = _box_center(label.get("box"))
    candidate_center = _box_center(candidate.get("box"))
    if label_center and candidate_center:
        lx, ly = label_center
        cx, cy = candidate_center
        dx = abs(cx - lx)
        dy = cy - ly
        # The amount printed immediately below/right of the MRP label is the
        # strongest spatial relationship. Penalize distant OCR noise heavily.
        score += max(0.0, 90.0 - (dx * 0.12 + abs(dy) * 0.08))
        if -40 <= dy <= 220:
            score += 35
        if abs(dx) <= 350:
            score += 20

    # A zero value is not a useful retail price and is often OCR noise.
    if amount <= 0:
        return -10_000
    return score


def correct_mrp(fields: dict[str, Any] | None) -> dict[str, Any]:
    """Replace a suspicious MRP extraction when OCR contains stronger evidence."""
    result = dict(fields or {})
    current = result.get("mrp")
    items = _items(result)
    if not items:
        return result

    labels = [item for item in items if MRP_LABEL.search(item["text"])]
    stamped = []
    for item in items:
        match = re.search(
            r"(?:₹|￥|\bRS\.?\b|\bINR\b)?\s*(\d{2,5}\.\d{2})\s*[,.;#]+\s*#?(?:0?[1-9]|1[0-2])[-/]\d{2}\b",
            item["text"],
            re.I,
        )
        if match and not UNIT_PRICE.search(item["text"]):
            stamped.append((item["confidence"], item, float(match.group(1))))
    if labels and stamped:
        _, candidate, amount = max(stamped, key=lambda entry: entry[0])
        label = labels[0]
        result["mrp"] = {
            "currency": "INR",
            "value": amount,
            "inclusive_of_all_taxes": bool(re.search(
                r"(?:INCLUSIVE|INCL\.?)\s*(?:OF)?\s+ALL\s+TAXES",
                " ".join(item["text"] for item in items),
                re.I,
            )),
            "label_text": label.get("raw_text", label["text"]),
            "label_box": label.get("box"),
            "confidence": candidate["confidence"],
            "source_text": candidate.get("raw_text", candidate["text"]),
            "source_box": candidate.get("box"),
            "source_image": candidate.get("source_image"),
            "extraction_method": "stamped_mrp_date_pair",
        }
        return result
    if isinstance(current, dict) and current.get("confidence", 0) >= 0.82:
        return result

    best: tuple[float, dict[str, Any], float, dict[str, Any]] | None = None
    for label in items:
        if not MRP_LABEL.search(label["text"]):
            continue

        inline = MRP_LABEL.sub("", label["text"], count=1)
        inline_match = PRICE.search(inline)
        if inline_match and not UNIT_PRICE.search(inline) and "%" not in inline:
            amount = float(inline_match.group(1))
            if amount > 0:
                best = (10_000.0, label, amount, label)
                break

        for candidate in items:
            text = candidate["text"].strip()
            match = PRICE.fullmatch(text)
            if not match or UNIT_PRICE.search(text) or "%" in text:
                continue
            amount = float(match.group(1))
            score = _candidate_score(label, candidate, amount)
            if score <= -10_000:
                continue
            # Without coordinates, retain only a modest OCR-order window.
            if not label.get("box") or not candidate.get("box"):
                distance = abs(candidate["index"] - label["index"])
                if distance > 12:
                    continue
                score += max(0.0, 35.0 - distance * 3.0)
            if best is None or score > best[0]:
                best = (score, label, amount, candidate)

    if best is None:
        return result

    _, label, amount, candidate = best
    result["mrp"] = {
        "currency": "INR",
        "value": amount,
        "inclusive_of_all_taxes": bool(
            re.search(r"(?:INCLUSIVE|INCL\.?\s*OF)\s+ALL\s+TAXES", " ".join(item["text"] for item in items), re.I)
        ),
        "label_text": label.get("raw_text", label["text"]),
        "label_box": label.get("box"),
        "confidence": candidate["confidence"],
        "source_text": candidate.get("raw_text", candidate["text"]),
        "source_box": candidate.get("box"),
        "source_image": candidate.get("source_image"),
        "extraction_method": "mrp_label_spatial_candidate",
    }
    return result
