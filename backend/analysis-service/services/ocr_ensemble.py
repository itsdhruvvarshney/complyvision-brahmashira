"""Multi-view OCR consensus for package-label extraction.

The primary analysis already performs the full-image OCR pass. This module reuses
that evidence and performs only one CLAHE contrast pass, records agreement
when the two views match spatially and textually. It does not inflate confidence.
"""

from __future__ import annotations

import re
from typing import Any

import cv2

from cv.ocr import predict_ocr_items


def _norm(text: Any) -> str:
    return re.sub(r"\s+", " ", str(text or "").strip().lower())


def _iou(a: list[int], b: list[int]) -> float:
    ax1, ay1, ax2, ay2 = a[:4]
    bx1, by1, bx2, by2 = b[:4]
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0, ix2 - ix1), max(0, iy2 - iy1)
    intersection = iw * ih
    if intersection <= 0:
        return 0.0
    area_a = max(1, ax2 - ax1) * max(1, ay2 - ay1)
    area_b = max(1, bx2 - bx1) * max(1, by2 - by1)
    return intersection / float(area_a + area_b - intersection)


def _similar(a: str, b: str) -> bool:
    left, right = _norm(a), _norm(b)
    if not left or not right:
        return False
    # Numeric or partial-text disagreement is not independent confirmation.
    return left == right


def _clahe_variant(image_path: str) -> Any:
    image = cv2.imread(image_path)
    if image is None:
        return image_path
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
    return cv2.cvtColor(clahe, cv2.COLOR_GRAY2BGR)


def _merge_passes(passes: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
    clusters: list[list[dict[str, Any]]] = []
    for pass_index, items in enumerate(passes):
        for item in items:
            item = dict(item)
            item["text"] = str(item.get("text") or item.get("normalized_text") or item.get("raw_text") or "")
            if not item["text"].strip():
                continue
            item["_pass"] = pass_index
            placed = False
            for cluster in clusters:
                reference = cluster[0]
                if item.get("box") and reference.get("box") and _iou(item["box"], reference["box"]) >= 0.25 and _similar(item.get("text"), reference.get("text")):
                    cluster.append(item)
                    placed = True
                    break
            if not placed:
                clusters.append([item])

    merged: list[dict[str, Any]] = []
    for cluster in clusters:
        best = max(cluster, key=lambda item: float(item.get("confidence", 0.0) or 0.0))
        result = {key: value for key, value in best.items() if not key.startswith("_")}
        votes = len({item["_pass"] for item in cluster})
        result["ocr_votes"] = votes
        result["ocr_ensemble"] = True
        merged.append(result)
    return merged


def run_ocr_ensemble(
    ocr: Any,
    image_path: str,
    *,
    primary_items: list[dict[str, Any]] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Reuse primary OCR and add one contrast-enhanced verification pass."""
    passes: list[list[dict[str, Any]]] = []
    errors: list[str] = []

    if primary_items:
        passes.append([dict(item) for item in primary_items if isinstance(item, dict)])

    try:
        contrast_items = predict_ocr_items(ocr, _clahe_variant(image_path))
        if contrast_items:
            passes.append(contrast_items)
    except Exception as exc:
        errors.append(str(exc))

    if not passes:
        return [], {"passes": 0, "consensus_items": 0, "errors": errors}

    merged = _merge_passes(passes)
    return merged, {
        "passes": len(passes),
        "raw_items": sum(len(items) for items in passes),
        "consensus_items": sum(int(item.get("ocr_votes", 1)) >= 2 for item in merged),
        "errors": errors,
    }
