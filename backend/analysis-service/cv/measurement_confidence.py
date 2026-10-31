"""Quality-aware confidence aggregation for calibrated glyph measurement."""

from __future__ import annotations

import math
from typing import Any


def image_quality_factor(quality: dict[str, Any] | None) -> float:
    quality = quality or {}
    if not quality.get("usable", False):
        return 0.25
    labels = " ".join(
        str(value).upper()
        for key in ("issues", "warnings")
        for value in (quality.get(key) or [])
    )
    factor = 1.0
    penalties = {
        "HIGH_GLARE": 0.48,
        "BLUR": 0.55,
        "DARK": 0.60,
        "BRIGHT": 0.60,
        "LOW_CONTRAST": 0.65,
    }
    for label, penalty in penalties.items():
        if label in labels:
            factor = min(factor, penalty)
    if quality.get("warnings") and factor == 1.0:
        factor = 0.90
    return factor


def aggregate_measurement_confidence(
    glyph: dict[str, Any] | None,
    quality: dict[str, Any] | None,
    calibration: dict[str, Any] | None,
) -> dict[str, Any]:
    """Combine independent trust links with a weighted geometric mean.

    The score is ``exp(sum(weight_i * log(clamp(factor_i))))``. Weights sum
    to one: segmentation .27, localization .15, OCR .08, expected digit count
    .10, height agreement .10, image quality .20, and calibration .10. Capture
    quality receives material weight because glare/blur can invalidate otherwise
    clean component geometry. This
    keeps the score interpretable while ensuring one weak trust link cannot be
    hidden by several strong arithmetic-average terms.
    """
    glyph = glyph or {}
    calibration = calibration or {}
    factors = glyph.get("confidence_factors") or {}
    segmentation = float(glyph.get("segmentation_confidence", glyph.get("confidence", 0.0)) or 0.0)
    localization = float(glyph.get("localization_confidence", factors.get("value_region", segmentation)) or 0.0)
    ocr = float(factors.get("ocr", segmentation) or 0.0)
    digit_count = float(factors.get("digit_count", segmentation) or 0.0)
    height_agreement = float(factors.get("height_agreement", segmentation) or 0.0)
    image_factor = image_quality_factor(quality)
    calibration_factor = float(
        calibration.get("calibration_confidence", 1.0 if calibration.get("detected") else 0.0) or 0.0
    )
    values = {
        "segmentation": (segmentation, 0.27),
        "localization": (localization, 0.15),
        "ocr": (ocr, 0.08),
        "digit_count": (digit_count, 0.10),
        "height_agreement": (height_agreement, 0.10),
        "image_quality": (image_factor, 0.20),
        "calibration": (calibration_factor, 0.10),
    }
    if glyph.get("status") != "OK" or not calibration.get("detected"):
        confidence = 0.0
    else:
        confidence = math.exp(sum(weight * math.log(max(0.001, min(1.0, value))) for value, weight in values.values()))
    return {
        "measurement_confidence": round(max(0.0, min(1.0, confidence)), 3),
        "image_quality_factor": round(image_factor, 3),
        "calibration_confidence": round(calibration_factor, 3),
        "measurement_confidence_factors": {
            key: round(max(0.0, min(1.0, value)), 3)
            for key, (value, _) in values.items()
        },
        "aggregation_method": "weighted_geometric_mean",
    }
