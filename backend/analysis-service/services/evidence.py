"""Build bounded, request-local visual evidence for canonical reports."""

from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

import cv2
import numpy as np


MAX_IMAGE_BYTES = 768 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024
MAX_DIMENSION = 1400
JPEG_QUALITY = 80


DECLARATIONS = (
    ("net_quantity", "declaration-net-quantity", "Net quantity location", "LM-R6-006"),
    ("mrp", "declaration-mrp", "MRP location", "LM-R6-008"),
    ("manufacturer", "declaration-manufacturer", "Manufacturer declaration location", "LM-R6-001"),
)


def _encode_jpeg(image: np.ndarray) -> tuple[str, int] | None:
    if image.size == 0:
        return None
    height, width = image.shape[:2]
    scale = min(1.0, MAX_DIMENSION / max(height, width))
    if scale < 1.0:
        image = cv2.resize(
            image,
            (max(1, round(width * scale)), max(1, round(height * scale))),
            interpolation=cv2.INTER_AREA,
        )
    quality = JPEG_QUALITY
    encoded: np.ndarray | None = None
    while quality >= 50:
        ok, candidate = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok and candidate.nbytes <= MAX_IMAGE_BYTES:
            encoded = candidate
            break
        quality -= 10
    if encoded is None:
        return None
    raw = encoded.tobytes()
    return "data:image/jpeg;base64," + base64.b64encode(raw).decode("ascii"), len(raw)


def _points(box: Any) -> np.ndarray | None:
    try:
        values = np.asarray(box, dtype=np.float32).reshape(-1, 2)
    except (TypeError, ValueError):
        return None
    if len(values) < 2 or not np.isfinite(values).all():
        return None
    return values


def _declaration_crop(image: np.ndarray, box: Any, label: str) -> np.ndarray | None:
    points = _points(box)
    if points is None:
        return None
    height, width = image.shape[:2]
    x1, y1 = np.floor(points.min(axis=0)).astype(int)
    x2, y2 = np.ceil(points.max(axis=0)).astype(int)
    padding = max(16, round(max(x2 - x1, y2 - y1) * 0.16))
    left, top = max(0, x1 - padding), max(0, y1 - padding - 24)
    right, bottom = min(width, x2 + padding), min(height, y2 + padding)
    if right <= left or bottom <= top:
        return None
    crop = image[top:bottom, left:right].copy()
    shifted = np.rint(points - np.array([left, top])).astype(np.int32)
    if len(shifted) == 2:
        cv2.rectangle(crop, tuple(shifted[0]), tuple(shifted[1]), (0, 176, 255), 3)
    else:
        cv2.polylines(crop, [shifted], True, (0, 176, 255), 3, cv2.LINE_AA)
    cv2.putText(
        crop,
        label,
        (8, min(22, max(14, crop.shape[0] - 5))),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        (10, 35, 55),
        3,
        cv2.LINE_AA,
    )
    cv2.putText(
        crop,
        label,
        (8, min(22, max(14, crop.shape[0] - 5))),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        (255, 255, 255),
        1,
        cv2.LINE_AA,
    )
    return crop


def _safe_debug_image(path_value: Any, evidence_root: Path) -> np.ndarray | None:
    if not path_value:
        return None
    try:
        candidate = Path(str(path_value)).resolve(strict=True)
        root = evidence_root.resolve(strict=True)
        candidate.relative_to(root)
    except (OSError, ValueError):
        return None
    if candidate.suffix.lower() not in {".jpg", ".jpeg", ".png"} or not candidate.is_file():
        return None
    return cv2.imread(str(candidate), cv2.IMREAD_COLOR)


def build_evidence_images(
    image_path: str | Path,
    batch_result: dict[str, Any],
    evidence_root: str | Path,
) -> list[dict[str, Any]]:
    """Return embedded JPEG evidence, never filesystem paths or arbitrary files."""
    source = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
    if source is None:
        return []
    root = Path(evidence_root)
    results: list[dict[str, Any]] = []
    total_bytes = 0

    def add(image: np.ndarray | None, descriptor: dict[str, Any]) -> None:
        nonlocal total_bytes
        if image is None:
            return
        encoded = _encode_jpeg(image)
        if encoded is None:
            return
        data_url, raw_size = encoded
        if total_bytes + raw_size > MAX_TOTAL_BYTES:
            return
        total_bytes += raw_size
        results.append({**descriptor, "mime_type": "image/jpeg", "data_url": data_url})

    fields = batch_result.get("extracted_fields") or {}
    for field_name, evidence_id, label, rule_id in DECLARATIONS:
        field = fields.get(field_name)
        if isinstance(field, dict):
            add(
                _declaration_crop(source, field.get("source_box"), label),
                {
                    "id": evidence_id,
                    "type": "DECLARATION_CROP",
                    "label": label,
                    "related_declaration": field_name,
                    "related_rule_id": rule_id,
                },
            )

    glyph = batch_result.get("glyph_measurement") or {}
    add(
        _safe_debug_image(glyph.get("debug_image_path"), root),
        {
            "id": "numeral-height-overlay",
            "type": "NUMERAL_HEIGHT_OVERLAY",
            "label": "Numeral height measurement overlay",
            "related_declaration": "net_quantity",
            "related_rule_id": "LM-R7-001",
        },
    )
    targets = (batch_result.get("contrast_evidence") or {}).get("targets") or {}
    for target, declaration in (("NET_QUANTITY", "net_quantity"), ("MRP", "mrp")):
        item = targets.get(target) or {}
        add(
            _safe_debug_image(item.get("debug_image_path"), root),
            {
                "id": f"contrast-{declaration.replace('_', '-')}",
                "type": "CONTRAST_OVERLAY",
                "label": f"{target.replace('_', ' ').title()} contrast overlay",
                "related_declaration": declaration,
                "related_rule_id": "LM-R9-002",
            },
        )
    return results


def scrub_local_paths(value: Any) -> Any:
    """Remove internal debug path metadata before a result reaches reporting."""
    if isinstance(value, dict):
        return {
            key: scrub_local_paths(item)
            for key, item in value.items()
            if key not in {
                "debug_image_path", "debug_overlay_path", "debug_image_saved",
                "analysis_image_path",
            }
        }
    if isinstance(value, list):
        return [scrub_local_paths(item) for item in value]
    return value
