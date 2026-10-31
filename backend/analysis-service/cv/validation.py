"""Manual physical ground-truth and engineering diagnostic helpers.

These functions validate measurement evidence only. They do not implement any
Legal Metrology threshold or Rule 7 PASS/FAIL decision.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import numpy as np


def _positive_number(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def calculate_manual_error(
    cv_height_mm: float | None,
    manual_height_mm: float | None,
) -> dict[str, float | None]:
    """Calculate CV-vs-manual error, safely handling null and zero values."""
    cv_height = _positive_number(cv_height_mm)
    try:
        manual_height = float(manual_height_mm) if manual_height_mm is not None else None
    except (TypeError, ValueError):
        manual_height = None
    if manual_height is not None and (not math.isfinite(manual_height) or manual_height < 0):
        manual_height = None
    if cv_height is None or manual_height is None:
        return {"absolute_error_mm": None, "percentage_error": None}
    absolute_error = abs(cv_height - manual_height)
    return {
        "absolute_error_mm": round(absolute_error, 6),
        "percentage_error": (
            round(absolute_error / manual_height * 100.0, 6)
            if manual_height > 0
            else None
        ),
    }


def calculate_known_size_sanity(
    known_actual_mm: float | None,
    measured_pixel_length: float | None,
    pixels_per_mm: float | None,
) -> dict[str, Any]:
    """Check a same-plane known length against the ArUco-derived scale."""
    actual = _positive_number(known_actual_mm)
    pixels = _positive_number(measured_pixel_length)
    scale = _positive_number(pixels_per_mm)
    if actual is None or pixels is None or scale is None:
        return {
            "available": False,
            "known_actual_mm": actual,
            "measured_pixel_length": pixels,
            "pixels_per_mm": scale,
            "estimated_mm": None,
            "known_size_absolute_error_mm": None,
            "known_size_percentage_error": None,
            "status": "AWAITING_INPUT" if actual is None or pixels is None else "CALIBRATION_UNAVAILABLE",
        }
    estimated = pixels / scale
    absolute_error = abs(estimated - actual)
    percentage_error = absolute_error / actual * 100.0
    return {
        "available": True,
        "known_actual_mm": round(actual, 6),
        "measured_pixel_length": round(pixels, 6),
        "pixels_per_mm": round(scale, 6),
        "estimated_mm": round(estimated, 6),
        "known_size_absolute_error_mm": round(absolute_error, 6),
        "known_size_percentage_error": round(percentage_error, 6),
        "status": "WITHIN_5_PERCENT" if percentage_error <= 5.0 else "OUTSIDE_5_PERCENT",
    }


def coordinate_scale_metadata(
    *,
    original_width: int,
    original_height: int,
    measurement_width: int,
    measurement_height: int,
    glyph_height_measurement_px: float | None,
    coordinates_already_original: bool = False,
    pixels_per_mm: float | None = None,
) -> dict[str, Any]:
    """Expose and apply measurement-to-original pixel scaling explicitly."""
    dimensions_valid = all(
        isinstance(value, (int, float)) and value > 0
        for value in (original_width, original_height, measurement_width, measurement_height)
    )
    if not dimensions_valid:
        x_scale = y_scale = None
    elif coordinates_already_original:
        x_scale = y_scale = 1.0
    else:
        x_scale = float(original_width) / float(measurement_width)
        y_scale = float(original_height) / float(measurement_height)
    measured_height = _positive_number(glyph_height_measurement_px)
    original_height_px = (
        measured_height * y_scale
        if measured_height is not None and y_scale is not None
        else None
    )
    scale = _positive_number(pixels_per_mm)
    final_mm = original_height_px / scale if original_height_px is not None and scale else None
    return {
        "original_image_width": int(original_width),
        "original_image_height": int(original_height),
        "measurement_image_width": int(measurement_width),
        "measurement_image_height": int(measurement_height),
        "x_scale": round(x_scale, 6) if x_scale is not None else None,
        "y_scale": round(y_scale, 6) if y_scale is not None else None,
        "coordinates_already_original": bool(coordinates_already_original),
        "resize_applied": bool(
            dimensions_valid
            and (measurement_width != original_width or measurement_height != original_height)
        ),
        "glyph_height_measurement_image_px": round(measured_height, 6) if measured_height else None,
        "glyph_height_original_image_px": round(original_height_px, 6) if original_height_px else None,
        "final_height_mm": round(final_mm, 6) if final_mm else None,
        "coordinate_consistent": bool(x_scale is not None and y_scale is not None),
    }


def marker_target_distance_ratio(
    marker_corners: Any,
    digit_boxes: Any,
    image_width: int | None,
    image_height: int | None,
) -> float | None:
    """Return marker-to-target center distance normalized by image diagonal."""
    if not marker_corners or not digit_boxes or not image_width or not image_height:
        return None
    try:
        marker = np.asarray(marker_corners, dtype=float).reshape(-1, 2)
        boxes = np.asarray(digit_boxes, dtype=float).reshape(-1, 4)
    except (TypeError, ValueError):
        return None
    marker_center = marker.mean(axis=0)
    target_center = np.asarray([
        np.mean((boxes[:, 0] + boxes[:, 2]) / 2),
        np.mean((boxes[:, 1] + boxes[:, 3]) / 2),
    ])
    diagonal = math.hypot(float(image_width), float(image_height))
    return float(np.linalg.norm(marker_center - target_center) / diagonal) if diagonal else None


def classify_probable_failure_source(result: dict[str, Any]) -> dict[str, Any]:
    """Classify the most probable engineering issue without claiming certainty."""
    aruco = result.get("aruco") or {}
    glyph = result.get("glyph_measurement") or {}
    coordinate = glyph.get("coordinate_metadata") or {}
    sanity = result.get("known_size_sanity") or {}
    manual_error = result.get("percentage_error")
    evidence: list[str] = []

    if not aruco.get("detected"):
        return {
            "probable_failure_source": "CALIBRATION",
            "diagnostic_confidence": "HIGH",
            "diagnostic_evidence": ["ArUco calibration is unavailable; physical scale cannot be inferred"],
        }
    sanity_error = sanity.get("known_size_percentage_error")
    if isinstance(sanity_error, (int, float)) and sanity_error > 10.0:
        return {
            "probable_failure_source": "CALIBRATION",
            "diagnostic_confidence": "MODERATE",
            "diagnostic_evidence": [f"Same-plane known-size error is {sanity_error:.2f}%"],
        }
    x_scale, y_scale = coordinate.get("x_scale"), coordinate.get("y_scale")
    if coordinate and (
        not coordinate.get("coordinate_consistent", False)
        or not isinstance(x_scale, (int, float))
        or not isinstance(y_scale, (int, float))
    ):
        return {
            "probable_failure_source": "COORDINATE_SCALING",
            "diagnostic_confidence": "MODERATE",
            "diagnostic_evidence": ["Measurement-to-original coordinate conversion is incomplete"],
        }
    if glyph.get("localization_method") == "substring_fallback":
        return {
            "probable_failure_source": "GLYPH_LOCALIZATION",
            "diagnostic_confidence": "MODERATE",
            "diagnostic_evidence": ["Numeral localization used substring geometry fallback"],
        }
    if glyph.get("status") != "OK":
        reason = str(glyph.get("reason") or "Glyph measurement is unavailable")
        if any(term in reason.lower() for term in ("segment", "digit count", "height")):
            return {
                "probable_failure_source": "SEGMENTATION",
                "diagnostic_confidence": "MODERATE",
                "diagnostic_evidence": [reason],
            }
    distance = result.get("marker_target_distance_ratio")
    perspective_warning = "MARKER_PERSPECTIVE_DISTORTION" in (aruco.get("diagnostic_warnings") or [])
    if (
        isinstance(manual_error, (int, float))
        and manual_error > 10.0
        and sanity.get("status") == "WITHIN_5_PERCENT"
        and (perspective_warning or isinstance(distance, (int, float)) and distance > 0.30)
    ):
        evidence.append(f"Manual glyph error is {manual_error:.2f}% while the known-size check passes")
        if isinstance(distance, (int, float)):
            evidence.append(f"Marker-to-glyph distance is {distance:.3f} of the image diagonal")
        return {
            "probable_failure_source": "PERSPECTIVE",
            "diagnostic_confidence": "LOW",
            "diagnostic_evidence": evidence,
        }
    return {
        "probable_failure_source": "INSUFFICIENT_EVIDENCE",
        "diagnostic_confidence": "LOW",
        "diagnostic_evidence": ["Manual glyph and same-plane known-size measurements are needed"],
    }


def load_ground_truth(path: str | Path) -> dict[str, dict[str, Any]]:
    """Load editable ground-truth entries keyed by both path and basename."""
    source = Path(path)
    if not source.exists():
        return {}
    payload = json.loads(source.read_text(encoding="utf-8"))
    entries = payload.get("measurements") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        return {}
    indexed: dict[str, dict[str, Any]] = {}
    for entry in entries:
        if not isinstance(entry, dict) or not entry.get("image"):
            continue
        image = str(entry["image"])
        indexed[image] = entry
        indexed[Path(image).name] = entry
    return indexed
