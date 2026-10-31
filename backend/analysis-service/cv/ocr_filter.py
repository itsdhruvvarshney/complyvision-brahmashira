"""Geometry-based filtering for OCR evidence that overlaps calibration markers."""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import Any


Box = Sequence[float] | Sequence[Sequence[float]]


def _bounds(box: Box | None) -> tuple[float, float, float, float] | None:
    if box is None or len(box) == 0:
        return None
    first = box[0]
    if isinstance(first, (list, tuple)):
        points = [point for point in box if len(point) >= 2]  # type: ignore[arg-type]
        if not points:
            return None
        xs = [float(point[0]) for point in points]
        ys = [float(point[1]) for point in points]
        return min(xs), min(ys), max(xs), max(ys)
    if len(box) < 4:
        return None
    x1, y1, x2, y2 = (float(value) for value in box[:4])  # type: ignore[misc]
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def box_overlap_ratio(box: Box | None, region: Box | None) -> float:
    """Return intersection area as a fraction of ``box`` area.

    The OCR box is the denominator so a small OCR artifact contained by a marker
    is removed even when the marker region itself is substantially larger.
    """
    box_bounds = _bounds(box)
    region_bounds = _bounds(region)
    if box_bounds is None or region_bounds is None:
        return 0.0

    x1, y1, x2, y2 = box_bounds
    region_x1, region_y1, region_x2, region_y2 = region_bounds
    box_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    if box_area == 0.0:
        return 0.0

    intersection_width = max(0.0, min(x2, region_x2) - max(x1, region_x1))
    intersection_height = max(0.0, min(y2, region_y2) - max(y1, region_y1))
    return (intersection_width * intersection_height) / box_area


def filter_ocr_items_near_aruco(
    ocr_items: Iterable[Mapping[str, Any]],
    marker_corners: Box | None,
    *,
    overlap_threshold: float = 0.30,
) -> list[Mapping[str, Any]]:
    """Return a new list without OCR boxes substantially overlapping ArUco.

    ``overlap_threshold`` is the fraction of each OCR box covered by the marker's
    bounding rectangle. The input objects are retained unchanged for raw evidence.
    """
    if not 0.0 <= overlap_threshold <= 1.0:
        raise ValueError("overlap_threshold must be between 0 and 1")
    if _bounds(marker_corners) is None:
        return list(ocr_items)
    return [
        item
        for item in ocr_items
        if box_overlap_ratio(item.get("box"), marker_corners) < overlap_threshold
    ]
