"""Conservative local colour/luminance contrast evidence extraction.

The numerical thresholds in this module are prototype engineering thresholds;
they are not statutory Legal Metrology contrast thresholds.
"""

from __future__ import annotations

import math
import re
from pathlib import Path
from typing import Any, Sequence

import cv2
import numpy as np


ENGINEERING_STRONG_CONTRAST_RATIO = 3.0
ENGINEERING_STRONG_COLOR_DIFFERENCE = 35.0
ENGINEERING_LOW_CONTRAST_RATIO = 1.5
ENGINEERING_LOW_COLOR_DIFFERENCE = 12.0


def _bounds(box: Any) -> tuple[float, float, float, float] | None:
    if box is None:
        return None
    try:
        array = np.asarray(box, dtype=float)
    except (TypeError, ValueError):
        return None
    if array.size < 4:
        return None
    if array.ndim >= 2:
        points = array.reshape(-1, 2)
        return (
            float(points[:, 0].min()), float(points[:, 1].min()),
            float(points[:, 0].max()), float(points[:, 1].max()),
        )
    x1, y1, x2, y2 = (float(value) for value in array[:4])
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def _clamp_box(
    box: Sequence[float], width: int, height: int, padding: int = 0,
) -> tuple[int, int, int, int] | None:
    x1, y1, x2, y2 = (float(value) for value in box[:4])
    result = (
        max(0, int(math.floor(x1)) - padding),
        max(0, int(math.floor(y1)) - padding),
        min(width, int(math.ceil(x2)) + padding),
        min(height, int(math.ceil(y2)) + padding),
    )
    return result if result[2] > result[0] and result[3] > result[1] else None


def _number_matches(text: str) -> list[re.Match[str]]:
    return list(re.finditer(r"(?<!\d)\d+(?:[.,]\d+)?", text or ""))


def _choose_numeric_match(text: str, expected_value: Any) -> re.Match[str] | None:
    matches = _number_matches(text)
    if not matches:
        return None
    try:
        expected = float(expected_value)
    except (TypeError, ValueError):
        return matches[0]
    parsed = []
    for match in matches:
        try:
            value = float(match.group(0).replace(",", "."))
        except ValueError:
            continue
        parsed.append((abs(value - expected), match))
    return min(parsed, key=lambda item: item[0])[1] if parsed else matches[0]


def localize_contrast_value_region(
    evidence: dict[str, Any] | None,
    image_width: int,
    image_height: int,
) -> dict[str, Any] | None:
    """Locate an extracted numeric value using word boxes before text fallback."""
    if not isinstance(evidence, dict):
        return None
    manual_bounds = _bounds(evidence.get("manual_target_polygon"))
    if manual_bounds is not None:
        box = _clamp_box(manual_bounds, image_width, image_height, padding=2)
        if box:
            return {
                "box": list(box),
                "method": "manually_confirmed_polygon",
                "confidence": 1.0,
                "numeric_text": str(evidence.get("source_text") or ""),
            }
    expected_value = evidence.get("value")
    for token in evidence.get("tokens") or []:
        if not isinstance(token, dict):
            continue
        token_text = str(token.get("text") or "")
        match = _choose_numeric_match(token_text, expected_value)
        bounds = _bounds(token.get("box"))
        if match is None or bounds is None:
            continue
        try:
            token_value = float(match.group(0).replace(",", "."))
            expected = float(expected_value)
        except (TypeError, ValueError):
            token_value = expected = None
        if expected is not None and abs(token_value - expected) > max(0.01, abs(expected) * 0.001):
            continue
        x1, y1, x2, y2 = bounds
        token_length = max(1, len(token_text))
        left = x1 + (x2 - x1) * match.start() / token_length
        right = x1 + (x2 - x1) * match.end() / token_length
        box = _clamp_box([left, y1, right, y2], image_width, image_height, padding=2)
        if box:
            exact = match.start() == 0 and match.end() == len(token_text)
            return {
                "box": list(box),
                "method": "ocr_token_geometry",
                "confidence": 1.0 if exact else 0.90,
                "numeric_text": match.group(0),
            }

    source_text = str(evidence.get("source_text") or "")
    match = _choose_numeric_match(source_text, expected_value)
    source_bounds = _bounds(evidence.get("source_box"))
    if match is None or source_bounds is None:
        return None
    x1, y1, x2, y2 = source_bounds
    text_length = max(1, len(source_text))
    nominal = (x2 - x1) / text_length
    left = x1 + (x2 - x1) * match.start() / text_length - nominal * 0.5
    right = x1 + (x2 - x1) * match.end() / text_length + nominal * 0.5
    box = _clamp_box([left, y1, right, y2], image_width, image_height, padding=2)
    if not box:
        return None
    return {
        "box": list(box),
        "method": "substring_fallback",
        "confidence": 0.55,
        "numeric_text": match.group(0),
    }


def _relative_luminance(bgr_pixels: np.ndarray) -> np.ndarray:
    rgb = bgr_pixels[..., ::-1].astype(np.float64) / 255.0
    linear = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    return linear[..., 0] * 0.2126 + linear[..., 1] * 0.7152 + linear[..., 2] * 0.0722


def _review(target: str, reason: str, **evidence: Any) -> dict[str, Any]:
    return {
        "status": "REVIEW",
        "target": target,
        "confidence": 0.0,
        "issues": [reason],
        **evidence,
    }


def _save_debug(
    image: np.ndarray,
    target_box: tuple[int, int, int, int],
    outer_box: tuple[int, int, int, int],
    foreground_mask: np.ndarray,
    background_sample_mask: np.ndarray,
    source_box: Any,
    result: dict[str, Any],
    path: str | Path,
) -> bool:
    debug = image.copy()
    ox1, oy1, ox2, oy2 = outer_box
    tx1, ty1, tx2, ty2 = target_box
    outer_roi = debug[oy1:oy2, ox1:ox2]
    ring_mask = np.ones(outer_roi.shape[:2], dtype=bool)
    ring_mask[ty1 - oy1:ty2 - oy1, tx1 - ox1:tx2 - ox1] = False
    included = background_sample_mask.astype(bool)
    excluded = ring_mask & ~included
    if included.shape == outer_roi.shape[:2]:
        outer_roi[included] = (
            outer_roi[included].astype(float) * 0.55 + np.array([40, 200, 40]) * 0.45
        ).astype(np.uint8)
        outer_roi[excluded] = (
            outer_roi[excluded].astype(float) * 0.55 + np.array([30, 80, 220]) * 0.45
        ).astype(np.uint8)
    cv2.rectangle(debug, (ox1, oy1), (ox2, oy2), (0, 215, 255), 2)
    cv2.rectangle(debug, (tx1, ty1), (tx2, ty2), (255, 0, 0), 2)
    source_bounds = _bounds(source_box)
    if source_bounds is not None:
        try:
            source_points = np.asarray(source_box, dtype=float).reshape(-1, 2)
        except (TypeError, ValueError):
            source_points = np.empty((0, 2))
        if len(source_points) >= 3:
            source_points[:, 0] = np.clip(source_points[:, 0], 0, image.shape[1] - 1)
            source_points[:, 1] = np.clip(source_points[:, 1], 0, image.shape[0] - 1)
            cv2.polylines(
                debug, [np.rint(source_points).astype(np.int32)], True, (255, 255, 0), 2
            )
        else:
            source_rectangle = _clamp_box(source_bounds, image.shape[1], image.shape[0])
            if source_rectangle is not None:
                sx1, sy1, sx2, sy2 = source_rectangle
                cv2.rectangle(debug, (sx1, sy1), (sx2, sy2), (255, 255, 0), 2)
    roi = debug[ty1:ty2, tx1:tx2]
    if roi.shape[:2] == foreground_mask.shape:
        overlay = np.zeros_like(roi)
        overlay[:, :] = (255, 0, 255)
        selected = foreground_mask.astype(bool)
        roi[selected] = (
            roi[selected].astype(float) * 0.35 + overlay[selected].astype(float) * 0.65
        ).astype(np.uint8)
    label = (
        f"{result['target']} ratio={result.get('contrast_ratio', 0):.2f} "
        f"conf={result.get('confidence', 0):.2f} {result['status']}"
    )
    cv2.putText(
        debug, label, (max(5, tx1), max(22, ty1 - 8)),
        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (20, 80, 220), 2, cv2.LINE_AA,
    )
    cv2.putText(
        debug, "cyan=OCR blue=value magenta=foreground green=background red=excluded",
        (5, max(44, image.shape[0] - 10)), cv2.FONT_HERSHEY_SIMPLEX,
        0.38, (20, 20, 20), 1, cv2.LINE_AA,
    )
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    return bool(cv2.imwrite(str(output), debug))


def measure_local_contrast(
    image_source: str | Path | np.ndarray,
    target_evidence: dict[str, Any] | None,
    target: str,
    *,
    image_quality: dict[str, Any] | None = None,
    debug_image_path: str | Path | None = None,
) -> dict[str, Any]:
    """Measure local declaration contrast using OCR geometry and nearby background."""
    target_name = str(target).upper()
    image = (
        image_source.copy()
        if isinstance(image_source, np.ndarray)
        else cv2.imread(str(image_source))
    )
    if image is None or image.size == 0:
        return _review(target_name, "Source image could not be read")
    height, width = image.shape[:2]
    localization = localize_contrast_value_region(target_evidence, width, height)
    if localization is None:
        return _review(target_name, "OCR target/value geometry is unavailable")
    target_box = tuple(localization["box"])
    tx1, ty1, tx2, ty2 = target_box
    target_height = ty2 - ty1
    target_width = tx2 - tx1
    ring_padding = max(8, int(round(max(target_height * 0.75, target_width * 0.12))))
    outer_box = _clamp_box(target_box, width, height, padding=ring_padding)
    if outer_box is None:
        return _review(target_name, "Local background region is invalid")
    ox1, oy1, ox2, oy2 = outer_box
    clipped = ox1 == 0 or oy1 == 0 or ox2 == width or oy2 == height

    outer = image[oy1:oy2, ox1:ox2]
    ring_mask = np.ones(outer.shape[:2], dtype=bool)
    inner_x1, inner_y1 = tx1 - ox1, ty1 - oy1
    inner_x2, inner_y2 = tx2 - ox1, ty2 - oy1
    ring_mask[inner_y1:inner_y2, inner_x1:inner_x2] = False
    ring_pixels = outer[ring_mask]
    if ring_pixels.shape[0] < 50:
        return _review(target_name, "Too few local background pixels", localization=localization)

    ring_lab = cv2.cvtColor(ring_pixels.reshape(-1, 1, 3), cv2.COLOR_BGR2LAB).reshape(-1, 3).astype(float)
    initial_background_lab = np.median(ring_lab, axis=0)
    ring_distances = np.linalg.norm(ring_lab - initial_background_lab, axis=1)
    dominant_cutoff = float(np.percentile(ring_distances, 60))
    dominant = ring_distances <= max(2.0, dominant_cutoff)
    background_pixels = ring_pixels[dominant]
    background_sample_mask = np.zeros(outer.shape[:2], dtype=bool)
    background_sample_mask[ring_mask] = dominant
    background_lab = np.median(ring_lab[dominant], axis=0)
    background_luminances = _relative_luminance(background_pixels)
    background_luminance = float(np.median(background_luminances))
    luminance_range = float(
        np.percentile(background_luminances, 90)
        - np.percentile(background_luminances, 10)
    )
    background_dispersion = float(np.median(ring_distances[dominant]))

    target_roi = image[ty1:ty2, tx1:tx2]
    target_lab = cv2.cvtColor(target_roi, cv2.COLOR_BGR2LAB).astype(float)
    color_distance = np.linalg.norm(target_lab - background_lab, axis=2)
    scaled_distance = np.clip(color_distance / 80.0 * 255.0, 0, 255).astype(np.uint8)
    otsu_threshold, foreground_u8 = cv2.threshold(
        scaled_distance, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU
    )
    foreground_mask = foreground_u8.astype(bool)
    foreground_ratio = float(np.count_nonzero(foreground_mask) / foreground_mask.size)
    foreground_pixels = target_roi[foreground_mask]
    issues: list[str] = []
    if foreground_pixels.shape[0] < max(8, int(target_roi.size / 3 * 0.01)):
        issues.append("INSUFFICIENT_FOREGROUND_SAMPLES")
    if foreground_ratio > 0.65:
        issues.append("FOREGROUND_BACKGROUND_SEPARATION_AMBIGUOUS")
    if luminance_range > 0.22 or background_dispersion > 14.0:
        issues.append("HETEROGENEOUS_LOCAL_BACKGROUND")
    if clipped:
        issues.append("BACKGROUND_RING_CLIPPED_AT_IMAGE_BOUNDARY")

    quality = image_quality or {}
    critical_quality = {str(issue).upper() for issue in quality.get("issues") or []}
    if critical_quality:
        issues.append("IMAGE_QUALITY_" + "+".join(sorted(critical_quality)))
    hsv = cv2.cvtColor(outer, cv2.COLOR_BGR2HSV)
    local_glare_ratio = float(np.mean((hsv[:, :, 2] > 245) & (hsv[:, :, 1] < 35)))
    if local_glare_ratio > 0.18 and luminance_range > 0.15:
        issues.append("LOCAL_GLARE")

    if foreground_pixels.size:
        foreground_luminance = float(np.median(_relative_luminance(foreground_pixels)))
        foreground_lab = np.median(
            cv2.cvtColor(foreground_pixels.reshape(-1, 1, 3), cv2.COLOR_BGR2LAB).reshape(-1, 3),
            axis=0,
        ).astype(float)
        color_difference = float(np.linalg.norm(foreground_lab - background_lab))
    else:
        foreground_luminance = background_luminance
        color_difference = 0.0
    lighter = max(foreground_luminance, background_luminance)
    darker = min(foreground_luminance, background_luminance)
    contrast_ratio = (lighter + 0.05) / (darker + 0.05)
    luminance_difference = abs(foreground_luminance - background_luminance)

    try:
        ocr_confidence = float((target_evidence or {}).get("confidence", 0.0) or 0.0)
    except (TypeError, ValueError):
        ocr_confidence = 0.0
    localization_confidence = float(localization["confidence"])
    separation_score = min(1.0, color_difference / 35.0)
    uniformity_score = max(0.0, 1.0 - max(luminance_range / 0.22, background_dispersion / 14.0))
    sample_score = min(1.0, foreground_pixels.shape[0] / 80.0, background_pixels.shape[0] / 200.0)
    boundary_score = 0.55 if clipped else 1.0
    quality_score = 0.30 if critical_quality or "LOCAL_GLARE" in issues else 1.0
    confidence = (
        ocr_confidence * 0.20
        + localization_confidence * 0.20
        + separation_score * 0.25
        + uniformity_score * 0.15
        + sample_score * 0.10
        + boundary_score * 0.05
        + quality_score * 0.05
    )
    confidence = max(0.0, min(1.0, confidence))
    critical_issues = {
        "INSUFFICIENT_FOREGROUND_SAMPLES",
        "FOREGROUND_BACKGROUND_SEPARATION_AMBIGUOUS",
        "HETEROGENEOUS_LOCAL_BACKGROUND",
        "BACKGROUND_RING_CLIPPED_AT_IMAGE_BOUNDARY",
        "LOCAL_GLARE",
    }
    status = "REVIEW" if confidence < 0.65 or critical_issues.intersection(issues) or critical_quality else "OK"
    if contrast_ratio >= ENGINEERING_STRONG_CONTRAST_RATIO or color_difference >= ENGINEERING_STRONG_COLOR_DIFFERENCE:
        interpretation = "STRONG_ENGINEERING_CONTRAST"
    elif contrast_ratio < ENGINEERING_LOW_CONTRAST_RATIO and color_difference < ENGINEERING_LOW_COLOR_DIFFERENCE:
        interpretation = "LOW_ENGINEERING_CONTRAST"
    else:
        interpretation = "BORDERLINE_ENGINEERING_CONTRAST"

    result = {
        "status": status,
        "target": target_name,
        "ocr_text": (target_evidence or {}).get("source_text"),
        "numeric_text": localization.get("numeric_text"),
        "target_box": list(target_box),
        "background_ring_box": list(outer_box),
        "localization_method": localization["method"],
        "localization_confidence": round(localization_confidence, 3),
        "foreground_luminance": round(foreground_luminance, 6),
        "background_luminance": round(background_luminance, 6),
        "luminance_difference": round(luminance_difference, 6),
        "contrast_ratio": round(float(contrast_ratio), 3),
        "lab_color_difference": round(color_difference, 3),
        "engineering_interpretation": interpretation,
        "engineering_thresholds": {
            "strong_contrast_ratio": ENGINEERING_STRONG_CONTRAST_RATIO,
            "strong_lab_color_difference": ENGINEERING_STRONG_COLOR_DIFFERENCE,
            "low_contrast_ratio": ENGINEERING_LOW_CONTRAST_RATIO,
            "low_lab_color_difference": ENGINEERING_LOW_COLOR_DIFFERENCE,
            "statutory_threshold": None,
        },
        "foreground_sample_count": int(foreground_pixels.shape[0]),
        "background_sample_count": int(background_pixels.shape[0]),
        "foreground_fraction": round(foreground_ratio, 4),
        "background_luminance_range_p10_p90": round(luminance_range, 4),
        "background_lab_dispersion": round(background_dispersion, 3),
        "local_glare_ratio": round(local_glare_ratio, 4),
        "confidence": round(confidence, 3),
        "confidence_factors": {
            "ocr": round(max(0.0, min(1.0, ocr_confidence)), 3),
            "localization": round(localization_confidence, 3),
            "separation": round(separation_score, 3),
            "background_uniformity": round(uniformity_score, 3),
            "sample_size": round(sample_score, 3),
            "boundary": round(boundary_score, 3),
            "image_quality": round(quality_score, 3),
        },
        "issues": list(dict.fromkeys(issues)),
    }
    if debug_image_path is not None:
        result["debug_image_path"] = str(debug_image_path)
        result["debug_image_saved"] = _save_debug(
            image, target_box, outer_box, foreground_mask, background_sample_mask,
            (target_evidence or {}).get("source_box"), result, debug_image_path
        )
    return result
