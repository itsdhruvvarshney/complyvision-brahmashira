"""Conservative numeral-glyph measurement for calibrated net quantities."""

from __future__ import annotations

import itertools
import math
import re
from pathlib import Path
from typing import Any, Iterable, Sequence

import cv2
import numpy as np

from cv.validation import coordinate_scale_metadata


Box = Sequence[float] | Sequence[Sequence[float]]


def _box_bounds(box: Box | None) -> tuple[float, float, float, float] | None:
    if box is None or len(box) == 0:
        return None
    first = box[0]
    if isinstance(first, (list, tuple, np.ndarray)):
        points = np.asarray(box, dtype=float).reshape(-1, 2)
        return (
            float(points[:, 0].min()),
            float(points[:, 1].min()),
            float(points[:, 0].max()),
            float(points[:, 1].max()),
        )
    if len(box) < 4:
        return None
    x1, y1, x2, y2 = (float(value) for value in box[:4])  # type: ignore[misc]
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def clamp_box(
    box: Box | None,
    image_width: int,
    image_height: int,
    padding: int = 0,
) -> tuple[int, int, int, int] | None:
    """Clamp a rectangle or OCR polygon to valid image coordinates."""
    bounds = _box_bounds(box)
    if bounds is None or image_width <= 0 or image_height <= 0:
        return None
    x1, y1, x2, y2 = bounds
    padding = max(0, int(padding))
    clamped = (
        max(0, int(math.floor(x1)) - padding),
        max(0, int(math.floor(y1)) - padding),
        min(image_width, int(math.ceil(x2)) + padding),
        min(image_height, int(math.ceil(y2)) + padding),
    )
    if clamped[2] <= clamped[0] or clamped[3] <= clamped[1]:
        return None
    return clamped


def _number_matches(source_text: str) -> list[re.Match[str]]:
    return list(re.finditer(r"(?<!\d)\d+(?:[.,]\d+)?", source_text or ""))


def _numeric_span(source_text: str, value: Any = None) -> tuple[str, int, int] | None:
    matches = _number_matches(source_text)
    if not matches:
        return None

    chosen = matches[0]
    try:
        expected = float(value)
    except (TypeError, ValueError):
        expected = None
    if expected is not None:
        parsed_matches = []
        for match in matches:
            try:
                parsed = float(match.group(0).replace(",", "."))
            except ValueError:
                continue
            parsed_matches.append((abs(parsed - expected), match))
        if parsed_matches:
            chosen = min(parsed_matches, key=lambda entry: entry[0])[1]
    return chosen.group(0).replace(",", "."), chosen.start(), chosen.end()


def extract_numeric_token(source_text: str, value: Any = None) -> str | None:
    """Extract the quantity token, retaining a decimal separator for geometry."""
    span = _numeric_span(source_text, value)
    return span[0] if span else None


def extract_numeric_text(source_text: str, value: Any = None) -> str | None:
    """Extract only numeral glyphs from the quantity (``1.5 L`` -> ``15``)."""
    token = extract_numeric_token(source_text, value)
    return re.sub(r"\D", "", token) if token else None


def pixels_to_mm(height_px: float, pixels_per_mm: float) -> float | None:
    if height_px <= 0 or pixels_per_mm <= 0:
        return None
    return float(height_px) / float(pixels_per_mm)


def _height_inliers(heights: Iterable[float]) -> list[float]:
    values = np.asarray([float(height) for height in heights if height > 0], dtype=float)
    if values.size <= 2:
        return values.tolist()
    median = float(np.median(values))
    deviations = np.abs(values - median)
    mad = float(np.median(deviations))
    # Keep the outlier test scale-aware. A fixed two-pixel tolerance is useful
    # for small label text but incorrectly rejects normal anti-aliased variation
    # in large printed numerals (for example 112/116/116 px).
    tolerance = max(2.0, median * 0.12, 2.5 * mad)
    return values[deviations <= tolerance].tolist()


def robust_median_height(heights: Iterable[float]) -> float | None:
    """Return a median after rejecting clear height outliers."""
    inliers = _height_inliers(heights)
    return float(np.median(inliers)) if inliers else None


def filter_components(
    components: Iterable[dict[str, Any]],
    region_width: int,
    region_height: int,
    reference_height: float,
    maximum_scale: float = 1.30,
) -> list[dict[str, Any]]:
    """Remove obvious connected-component noise while retaining narrow digits."""
    minimum_height = max(3, int(round(reference_height * 0.20)))
    maximum_height = max(minimum_height, int(round(reference_height * maximum_scale)))
    minimum_area = max(3, int(round(reference_height * 0.45)))
    maximum_area = max(minimum_area, int(region_width * region_height * 0.20))
    maximum_width = max(3, int(round(reference_height * max(1.35, maximum_scale))))

    filtered = []
    for component in components:
        width = int(component["width"])
        height = int(component["height"])
        area = int(component["area"])
        if component.get("touches_crop_boundary"):
            continue
        if not (1 <= width <= maximum_width):
            continue
        if not (minimum_height <= height <= maximum_height):
            continue
        if not (minimum_area <= area <= maximum_area):
            continue
        if area / max(1, width * height) < 0.05:
            continue
        filtered.append(component)
    return filtered


def merge_split_components(
    components: Iterable[dict[str, Any]],
    reference_height: float,
) -> list[dict[str, Any]]:
    """Conservatively merge fragments that plausibly belong to one glyph.

    Fragments must be very close horizontally and strongly overlap vertically.
    The width guard deliberately prevents normal neighbouring digits from being
    joined. This is intentionally conservative: an uncertain split remains two
    candidates and lowers confidence instead of fabricating one glyph.
    """
    pending = sorted((dict(component) for component in components), key=lambda c: c["box"][0])
    merged: list[dict[str, Any]] = []
    maximum_gap = max(1, int(round(reference_height * 0.03)))
    maximum_combined_width = max(2, int(round(reference_height * 0.65)))

    index = 0
    while index < len(pending):
        current = pending[index]
        if index + 1 < len(pending):
            following = pending[index + 1]
            ax1, ay1, ax2, ay2 = current["box"]
            bx1, by1, bx2, by2 = following["box"]
            gap = bx1 - ax2
            vertical_overlap = max(0, min(ay2, by2) - max(ay1, by1))
            smaller_height = max(1, min(ay2 - ay1, by2 - by1))
            combined_width = max(ax2, bx2) - min(ax1, bx1)
            if (
                -maximum_gap <= gap <= maximum_gap
                and vertical_overlap / smaller_height >= 0.65
                and combined_width <= maximum_combined_width
                and smaller_height < reference_height * 0.55
                and min(current["area"], following["area"])
                < 0.45 * max(current["area"], following["area"])
            ):
                box = [min(ax1, bx1), min(ay1, by1), max(ax2, bx2), max(ay2, by2)]
                current = {
                    "x": min(current["x"], following["x"]),
                    "y": min(current["y"], following["y"]),
                    "width": box[2] - box[0],
                    "height": box[3] - box[1],
                    "area": current["area"] + following["area"],
                    "box": box,
                    "touches_crop_boundary": bool(
                        current.get("touches_crop_boundary")
                        or following.get("touches_crop_boundary")
                    ),
                    "merged_parts": int(current.get("merged_parts", 1))
                    + int(following.get("merged_parts", 1)),
                }
                index += 1
        merged.append(current)
        index += 1
    return merged


def _review(reason: str, confidence: float = 0.0, **evidence: Any) -> dict[str, Any]:
    return {
        "status": "REVIEW",
        "method": "connected_components",
        "reason": reason,
        "confidence": round(float(max(0.0, min(1.0, confidence))), 3),
        **evidence,
    }


def _foreground_mask(gray: np.ndarray) -> tuple[np.ndarray, str, float]:
    normalized = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(4, 4)).apply(gray)
    masks = []
    for mode, name in (
        (cv2.THRESH_BINARY_INV, "dark_on_light"),
        (cv2.THRESH_BINARY, "light_on_dark"),
    ):
        _, mask = cv2.threshold(normalized, 0, 255, mode | cv2.THRESH_OTSU)
        ratio = float(np.count_nonzero(mask) / mask.size)
        masks.append((abs(ratio - 0.15), mask, name, ratio))
    _, mask, polarity, ratio = min(masks, key=lambda entry: entry[0])
    return mask, polarity, ratio


def _foreground_masks(gray: np.ndarray) -> list[tuple[np.ndarray, str, float]]:
    """Return conservative preprocessing variants for difficult print/lighting."""
    normalized = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(4, 4)).apply(gray)
    attempts: list[tuple[np.ndarray, str, float]] = []
    for source, prefix in ((gray, "raw"), (normalized, "clahe")):
        for mode, polarity in (
            (cv2.THRESH_BINARY_INV, "dark_on_light"),
            (cv2.THRESH_BINARY, "light_on_dark"),
        ):
            _, mask = cv2.threshold(source, 0, 255, mode | cv2.THRESH_OTSU)
            attempts.append((mask, f"{prefix}_otsu_{polarity}", float(np.count_nonzero(mask) / mask.size)))
    block_size = max(11, min(51, (min(gray.shape) // 4) | 1))
    for mode, polarity in (
        (cv2.THRESH_BINARY_INV, "dark_on_light"),
        (cv2.THRESH_BINARY, "light_on_dark"),
    ):
        mask = cv2.adaptiveThreshold(
            normalized, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, mode, block_size, 7
        )
        attempts.append((mask, f"clahe_adaptive_{polarity}", float(np.count_nonzero(mask) / mask.size)))
    return attempts


def _connected_components(mask: np.ndarray, crop_box: tuple[int, int, int, int]) -> list[dict[str, Any]]:
    count, _, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    crop_x1, crop_y1, _, _ = crop_box
    height, width = mask.shape
    components = []
    for label in range(1, count):
        x, y, component_width, component_height, area = (
            int(value) for value in stats[label, :5]
        )
        components.append({
            "x": x,
            "y": y,
            "width": component_width,
            "height": component_height,
            "area": area,
            "box": [
                crop_x1 + x,
                crop_y1 + y,
                crop_x1 + x + component_width,
                crop_y1 + y + component_height,
            ],
            "touches_crop_boundary": (
                x <= 0
                or y <= 0
                or x + component_width >= width
                or y + component_height >= height
            ),
        })
    return components


def _select_digit_components(
    components: list[dict[str, Any]],
    expected_count: int,
    expected_center_x: float,
    reference_width: float,
    numeric_text: str = "",
) -> list[dict[str, Any]]:
    ordered = sorted(components, key=lambda component: component["box"][0])
    if len(ordered) <= expected_count:
        return ordered

    best: tuple[float, tuple[dict[str, Any], ...]] | None = None
    for group in itertools.combinations(ordered, expected_count):
        heights = np.asarray([component["height"] for component in group], dtype=float)
        centers = np.asarray(
            [(component["box"][0] + component["box"][2]) / 2 for component in group],
            dtype=float,
        )
        consistency = float(np.std(heights) / max(1.0, np.mean(heights)))
        center_distance = abs(float(np.mean(centers)) - expected_center_x) / max(1.0, reference_width)
        order_gaps = np.diff(centers)
        gap_penalty = (
            float(np.std(order_gaps) / max(1.0, np.mean(order_gaps)))
            if order_gaps.size > 1
            else 0.0
        )
        widths = np.asarray([component["width"] for component in group], dtype=float)
        width_penalty = float(np.std(widths) / max(1.0, np.mean(widths)))
        # A genuine '1' can be much narrower than its neighbours. Without a 1,
        # a single narrow full-height candidate is more likely label punctuation.
        width_weight = 0.05 if "1" in numeric_text else 0.35
        shape_penalty = 0.0
        if len(numeric_text) == len(group):
            for digit, width, height in zip(numeric_text, widths, heights):
                aspect = float(width / max(1.0, height))
                if digit == "1":
                    shape_penalty += aspect * 0.45 + max(0.0, aspect - 0.48) * 1.8
                elif aspect < 0.16:
                    shape_penalty += (0.16 - aspect) * 1.5
            shape_penalty /= len(group)
        baselines = np.asarray([component["box"][3] for component in group], dtype=float)
        line_penalty = float(np.std(baselines) / max(1.0, np.mean(heights)))
        size_reward = min(1.5, float(np.mean(heights)) / max(1.0, reference_width * 0.18))
        score = (
            consistency
            + center_distance * 0.35
            + gap_penalty * 0.15
            + width_penalty * width_weight
            + shape_penalty
            + line_penalty * 1.5
            - size_reward * 0.35
        )
        if best is None or score < best[0]:
            best = (score, group)
    return list(best[1]) if best else []


def _geometry_value_region(
    net_quantity: dict[str, Any],
    numeric_text: str,
    numeric_token: str,
    crop_box: tuple[int, int, int, int],
) -> tuple[list[int], str] | None:
    """Use optional character/token geometry when an upstream OCR supplies it."""
    crop_x1, crop_y1, crop_x2, crop_y2 = crop_box
    for key in ("character_boxes", "char_boxes"):
        entries = net_quantity.get(key)
        if not isinstance(entries, list):
            continue
        digit_bounds = []
        for entry in entries:
            if not isinstance(entry, dict) or not str(entry.get("text", "")).isdigit():
                continue
            bounds = _box_bounds(entry.get("box"))
            if bounds is not None:
                digit_bounds.append(bounds)
        if len(digit_bounds) >= len(numeric_text):
            x1 = max(crop_x1, int(math.floor(min(box[0] for box in digit_bounds))))
            x2 = min(crop_x2, int(math.ceil(max(box[2] for box in digit_bounds))))
            if x2 > x1:
                y1 = max(crop_y1, int(math.floor(min(box[1] for box in digit_bounds))))
                y2 = min(crop_y2, int(math.ceil(max(box[3] for box in digit_bounds))))
                return [x1, y1, x2, y2], "ocr_character_geometry"

    for key in ("source_tokens", "ocr_tokens", "tokens"):
        entries = net_quantity.get(key)
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            token_text = str(entry.get("text") or "")
            if numeric_token not in token_text.replace(",", "."):
                continue
            bounds = _box_bounds(entry.get("box"))
            if bounds is None:
                continue
            x1, y1, x2, y2 = bounds
            token_start = token_text.replace(",", ".").find(numeric_token)
            token_length = max(1, len(token_text))
            left = x1 + (x2 - x1) * token_start / token_length
            right = x1 + (x2 - x1) * (token_start + len(numeric_token)) / token_length
            left_i = max(crop_x1, int(math.floor(left)))
            right_i = min(crop_x2, int(math.ceil(right)))
            if right_i > left_i:
                return [
                    left_i,
                    max(crop_y1, int(math.floor(y1))),
                    right_i,
                    min(crop_y2, int(math.ceil(y2))),
                ], "ocr_token_geometry"
    return None


def _perspective_score(source_box: Box) -> float:
    """Return a conservative geometry score for a polygonal OCR source box."""
    if source_box is None or len(source_box) == 0:
        return 0.0
    if not isinstance(source_box[0], (list, tuple, np.ndarray)):
        return 1.0
    points = np.asarray(source_box, dtype=float).reshape(-1, 2)
    if len(points) != 4:
        return 0.7
    top = np.linalg.norm(points[1] - points[0])
    bottom = np.linalg.norm(points[2] - points[3])
    left = np.linalg.norm(points[3] - points[0])
    right = np.linalg.norm(points[2] - points[1])
    ratios = [min(top, bottom) / max(1.0, max(top, bottom)), min(left, right) / max(1.0, max(left, right))]
    return float(max(0.0, min(ratios)))


def _save_debug_image(
    image: np.ndarray,
    source_box: tuple[int, int, int, int],
    value_region_box: list[int],
    digit_boxes: list[list[int]],
    rejected_boxes: list[list[int]],
    estimated_height_mm: float | None,
    annotation: str,
    debug_image_path: str,
    marker_corners: Any = None,
) -> bool:
    debug = image.copy()
    cv2.rectangle(debug, source_box[:2], source_box[2:], (255, 0, 0), 2)
    cv2.rectangle(debug, value_region_box[:2], value_region_box[2:], (0, 165, 255), 2)
    for box in rejected_boxes:
        cv2.rectangle(debug, box[:2], box[2:], (0, 0, 180), 1)
    for box in digit_boxes:
        cv2.rectangle(debug, box[:2], box[2:], (0, 255, 0), 2)
    if marker_corners:
        try:
            marker = np.asarray(marker_corners, dtype=np.int32).reshape(-1, 1, 2)
            cv2.polylines(debug, [marker], True, (255, 0, 255), 3)
            marker_points = marker.reshape(-1, 2)
            side_lengths = [
                float(np.linalg.norm(marker_points[(index + 1) % 4] - marker_points[index]))
                for index in range(4)
            ]
            cv2.putText(
                debug,
                f"ArUco mean side={np.mean(side_lengths):.1f}px",
                tuple(marker_points[0]),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.48,
                (180, 0, 180),
                1,
                cv2.LINE_AA,
            )
        except (TypeError, ValueError):
            pass
    if estimated_height_mm is not None:
        label_y = max(20, source_box[1] - 8)
        accepted_heights = [box[3] - box[1] for box in digit_boxes if len(box) >= 4]
        height_px_text = (
            f"{float(np.median(accepted_heights)):.1f}px / " if accepted_heights else ""
        )
        cv2.putText(
            debug,
            f"numeral height: {height_px_text}{estimated_height_mm:.3f}mm",
            (source_box[0], label_y),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            (0, 120, 0),
            2,
            cv2.LINE_AA,
        )
    cv2.putText(
        debug,
        annotation,
        (source_box[0], min(debug.shape[0] - 8, source_box[3] + 24)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.48,
        (80, 80, 0),
        1,
        cv2.LINE_AA,
    )
    path = Path(debug_image_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    return bool(cv2.imwrite(str(path), debug))


def measure_net_quantity_numerals(
    image_path: str,
    net_quantity: dict[str, Any] | None,
    pixels_per_mm: float | None,
    *,
    padding_px: int | None = None,
    debug: bool = False,
    debug_dir: str | Path | None = None,
    debug_image_path: str | None = None,
    marker_corners: Any = None,
) -> dict[str, Any]:
    """Estimate printed numeral height using calibrated connected components.

    The crop expands enough to complete glyphs that intersect the OCR source box,
    but selection remains constrained to the source line and estimated numeric span.
    Unreliable segmentation produces ``REVIEW`` rather than a fabricated height.
    """
    if not image_path:
        return _review("Source image path is missing")
    if not net_quantity:
        return _review("Net quantity is missing")
    source_text = str(net_quantity.get("source_text") or "")
    source_box_value = net_quantity.get("source_box")
    common_evidence = {"source_text": source_text, "source_box": source_box_value}
    try:
        source_box_missing = source_box_value is None or len(source_box_value) == 0
    except TypeError:
        source_box_missing = True
    if source_box_missing:
        return _review("Net quantity source_box is missing", **common_evidence)
    if not source_text.strip():
        return _review("Net quantity source_text is missing", **common_evidence)
    try:
        calibration_value = float(pixels_per_mm) if pixels_per_mm is not None else 0.0
    except (TypeError, ValueError):
        calibration_value = 0.0
    if calibration_value <= 0:
        return _review("ArUco calibration is missing", **common_evidence)

    numeric_span = _numeric_span(source_text, net_quantity.get("value"))
    if numeric_span is None:
        return _review("Numeric quantity text was not found", **common_evidence)
    numeric_token, numeric_start, numeric_end = numeric_span
    numeric_text = re.sub(r"\D", "", numeric_token)
    expected_digit_count = len(numeric_text)
    if expected_digit_count == 0:
        return _review("Numeric quantity contains no visible digits", numeric_text=numeric_text, **common_evidence)

    image = cv2.imread(str(image_path))
    if image is None:
        return _review("Source image could not be read", numeric_text=numeric_text, **common_evidence)
    image_height, image_width = image.shape[:2]
    source_box = clamp_box(source_box_value, image_width, image_height)
    if source_box is None:
        return _review("Net quantity source_box is outside the image", numeric_text=numeric_text, **common_evidence)
    source_x1, source_y1, source_x2, source_y2 = source_box
    source_width = source_x2 - source_x1
    source_height = source_y2 - source_y1

    full_image_box = (0, 0, image_width, image_height)
    full_geometry_region = _geometry_value_region(
        net_quantity, numeric_text, numeric_token, full_image_box
    )
    label_bounds = _box_bounds(net_quantity.get("label_box"))
    label_height = (
        max(1.0, label_bounds[3] - label_bounds[1]) if label_bounds is not None else source_height
    )
    display_line_geometry = bool(
        full_geometry_region is not None
        and net_quantity.get("source_layout") != "same_item"
        and source_height >= label_height * 1.7
    )

    # Start with only a small configurable padding. If token geometry shows a
    # compact OCR declaration above a larger display line, include the adjacent
    # line and let exact-count/baseline/size scoring choose the numeral group.
    # component is visibly clipped by the top/bottom crop boundary, the crop is
    # extended only in that direction to complete it. This recovery is important
    # for OCR rectangles whose vertical placement is imperfect, while avoiding a
    # broad search that could silently select a neighbouring declaration.
    search_padding = (
        max(2, int(round(source_height * 0.12)))
        if padding_px is None
        else max(0, int(padding_px))
    )
    crop_strategy = "small_padding"
    if display_line_geometry:
        crop_box = clamp_box(
            [
                source_x1 - source_width * 0.18,
                source_y1 - search_padding,
                source_x2 + source_width * 0.25,
                source_y2 + search_padding,
            ],
            image_width,
            image_height,
        )
        crop_strategy = "ocr_geometry_display_line"
    elif full_geometry_region is not None:
        token_region, value_region_method = full_geometry_region
        token_height = max(1, token_region[3] - token_region[1])
        crop_box = clamp_box(
            [
                source_x1 - source_width * 0.45,
                min(source_y1, token_region[1]) - token_height * 0.35,
                source_x2 + source_width * 0.18,
                max(source_y2, token_region[3]) + token_height * 3.2,
            ],
            image_width,
            image_height,
        )
        crop_strategy = "ocr_geometry_adjacent_line_search"
    else:
        crop_box = clamp_box(source_box_value, image_width, image_height, search_padding)
    if crop_box is None:
        return _review("Net quantity crop is invalid", numeric_text=numeric_text, **common_evidence)
    crop_x1, crop_y1, crop_x2, crop_y2 = crop_box
    crop = image[crop_y1:crop_y2, crop_x1:crop_x2]
    if crop.size == 0:
        return _review("Net quantity crop is empty", numeric_text=numeric_text, **common_evidence)

    if display_line_geometry:
        token_region, value_region_method = full_geometry_region
        token_width = max(1, token_region[2] - token_region[0])
        token_height = max(1, token_region[3] - token_region[1])
        value_region_box = [
            max(crop_x1, int(token_region[0] - token_width * 0.18)),
            max(crop_y1, int(token_region[1] - token_height * 0.12)),
            min(crop_x2, int(token_region[2] + token_width * 0.24)),
            min(crop_y2, int(token_region[3] + token_height * 0.12)),
        ]
        value_x1, value_x2 = value_region_box[0], value_region_box[2]
        expected_center_x = (token_region[0] + token_region[2]) / 2
    elif full_geometry_region is not None:
        _, value_region_method = full_geometry_region
        value_region_box = [crop_x1, crop_y1, crop_x2, crop_y2]
        value_x1, value_x2 = crop_x1, crop_x2
        expected_center_x = (source_x1 + source_x2) / 2
    else:
        text_length = max(1, len(source_text))
        approximate_character_width = source_width / text_length
        numeric_x1 = source_x1 + source_width * (numeric_start / text_length)
        numeric_x2 = source_x1 + source_width * (numeric_end / text_length)
        # Without character geometry this is deliberately approximate. Variable
        # character widths can move the true token by roughly half a nominal
        # character, so keep a narrow guard band and let component scoring choose
        # the height-consistent group nearest the expected centre.
        value_x1 = max(
            crop_x1,
            int(math.floor(numeric_x1 - approximate_character_width * 0.60)),
        )
        value_x2 = min(crop_x2, int(math.ceil(numeric_x2 + approximate_character_width)))
        value_region_box = [value_x1, crop_y1, value_x2, crop_y2]
        expected_center_x = (numeric_x1 + numeric_x2) / 2
        value_region_method = "substring_fallback"

    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    mask, polarity, foreground_ratio = _foreground_mask(gray)
    raw_components = _connected_components(mask, crop_box)

    if padding_px is None:
        boundary_targets = []
        for component in raw_components:
            box_x1, _, box_x2, _ = component["box"]
            center_x = (box_x1 + box_x2) / 2
            if (
                value_x1 <= center_x <= value_x2
                and source_height * 0.15 <= component["height"] <= source_height * 1.3
                and component["width"] <= source_height * 1.3
                and component.get("touches_crop_boundary")
            ):
                boundary_targets.append(component)
        extend_top = any(component["y"] <= 0 for component in boundary_targets)
        extend_bottom = any(
            component["y"] + component["height"] >= crop.shape[0]
            for component in boundary_targets
        )
        if extend_top or extend_bottom:
            completion_margin = max(search_padding, int(math.ceil(source_height * 0.60)))
            expanded_y1 = max(0, crop_y1 - completion_margin) if extend_top else crop_y1
            expanded_y2 = min(image_height, crop_y2 + completion_margin) if extend_bottom else crop_y2
            crop_box = (crop_x1, expanded_y1, crop_x2, expanded_y2)
            crop_x1, crop_y1, crop_x2, crop_y2 = crop_box
            crop = image[crop_y1:crop_y2, crop_x1:crop_x2]
            if value_region_method == "substring_fallback":
                value_region_box[1] = crop_y1
                value_region_box[3] = crop_y2
            gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            mask, polarity, foreground_ratio = _foreground_mask(gray)
            raw_components = _connected_components(mask, crop_box)
            crop_strategy = "adaptive_vertical_completion"

    # Retry several conservative segmentation variants. Prefer exact digit count,
    # then height agreement and proximity to the OCR token's visual line.
    best_attempt: tuple[float, str, float, list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]] | None = None
    value_y1, value_y2 = value_region_box[1], value_region_box[3]
    for attempt_mask, attempt_name, attempt_ratio in _foreground_masks(gray):
        attempt_raw = _connected_components(attempt_mask, crop_box)
        attempt_components = merge_split_components(
            filter_components(
                attempt_raw,
                crop.shape[1],
                crop.shape[0],
                source_height,
                maximum_scale=3.2 if full_geometry_region is not None and not display_line_geometry else 1.3,
            ),
            source_height,
        )
        attempt_candidates = []
        for component in attempt_components:
            box_x1, box_y1, box_x2, box_y2 = component["box"]
            center_x = (box_x1 + box_x2) / 2
            visual_line_overlap = max(0, min(box_y2, value_y2) - max(box_y1, value_y1))
            source_overlap = max(0, min(box_y2, source_y2) - max(box_y1, source_y1))
            if value_x1 <= center_x <= value_x2 and max(visual_line_overlap, source_overlap) > 0:
                attempt_candidates.append(component)
        attempt_selected = _select_digit_components(
            attempt_candidates, expected_digit_count, expected_center_x, source_width, numeric_text
        )
        heights = [float(component["height"]) for component in attempt_selected]
        count_error = abs(len(attempt_selected) - expected_digit_count)
        height_variation = float(np.std(heights) / max(1.0, np.mean(heights))) if len(heights) > 1 else 0.0
        foreground_penalty = abs(attempt_ratio - 0.15)
        retry_penalty = 0.60 if "adaptive" in attempt_name else (0.10 if attempt_name.startswith("raw") else 0.0)
        score = count_error * 10.0 + height_variation * 3.0 + foreground_penalty + retry_penalty
        if best_attempt is None or score < best_attempt[0]:
            best_attempt = (
                score, attempt_name, attempt_ratio, attempt_components,
                attempt_candidates, attempt_selected,
            )
    assert best_attempt is not None
    _, preprocessing_method, foreground_ratio, components, candidates, preselected = best_attempt
    polarity = "dark_on_light" if preprocessing_method.endswith("dark_on_light") else "light_on_dark"

    evidence = {
        **common_evidence,
        "numeric_text": numeric_text,
        "numeric_token": numeric_token,
        "source_box": list(source_box),
        "crop_box": list(crop_box),
        "crop_dimensions": {"width": int(crop.shape[1]), "height": int(crop.shape[0])},
        "crop_strategy": crop_strategy,
        "adjacent_display_line_search": bool(
            full_geometry_region is not None and not display_line_geometry
        ),
        "padding_px": search_padding,
        "value_region_box": value_region_box,
        "value_region_method": value_region_method,
        "pixels_per_mm": round(calibration_value, 4),
        "threshold_polarity": polarity,
        "preprocessing_method": preprocessing_method,
        "segmentation_attempt_count": len(_foreground_masks(gray)),
        "foreground_ratio": round(foreground_ratio, 4),
        "expected_digit_count": expected_digit_count,
    }

    def segmentation_review(
        reason: str,
        confidence: float,
        selected_components: list[dict[str, Any]],
        heights: list[float] | None = None,
    ) -> dict[str, Any]:
        digit_boxes = [component["box"] for component in selected_components]
        extra: dict[str, Any] = {
            "candidate_count": len(candidates),
            "digit_boxes": digit_boxes,
        }
        if heights is not None:
            extra["digit_heights_px"] = heights
        result = _review(reason, confidence=confidence, **extra, **evidence)
        requested_path = debug_image_path
        if debug and not requested_path:
            output_directory = Path(debug_dir) if debug_dir is not None else Path("debug")
            requested_path = str(
                output_directory / f"glyph_measurement_{Path(image_path).stem}.jpg"
            )
        if requested_path:
            rejected_boxes = [
                component["box"]
                for component in components
                if component not in selected_components
            ]
            result["debug_image_path"] = requested_path
            result["debug_image_saved"] = _save_debug_image(
                image,
                source_box,
                value_region_box,
                digit_boxes,
                rejected_boxes,
                None,
                f"numeric={numeric_text} | {value_region_method} | seg_conf={confidence:.3f}",
                requested_path,
                marker_corners,
            )
        return result

    selected = preselected
    if len(selected) != expected_digit_count:
        return segmentation_review(
            "Numeral segmentation did not match the expected digit count",
            min(0.49, len(selected) / expected_digit_count * 0.5),
            selected,
        )

    raw_heights = [float(component["height"]) for component in selected]
    inlier_heights = _height_inliers(raw_heights)
    if len(inlier_heights) != expected_digit_count:
        return segmentation_review(
            "Digit heights were inconsistent",
            0.45,
            selected,
            raw_heights,
        )
    estimated_height_px = robust_median_height(raw_heights)
    if estimated_height_px is None:
        return segmentation_review("No usable digit heights were found", 0.0, selected)
    estimated_height_mm = pixels_to_mm(estimated_height_px, calibration_value)
    if estimated_height_mm is None:
        return _review("Pixel-to-millimetre conversion failed", **evidence)

    mean_height = float(np.mean(raw_heights))
    variation = float(np.std(raw_heights) / max(1.0, mean_height))
    consistency_score = max(0.0, 1.0 - variation * 3.0)
    try:
        ocr_confidence = float(net_quantity.get("confidence", 0.5) or 0.5)
    except (TypeError, ValueError):
        ocr_confidence = 0.5
    ocr_confidence = max(0.0, min(1.0, ocr_confidence))
    foreground_score = max(0.0, 1.0 - abs(foreground_ratio - 0.15) / 0.35)
    count_score = min(len(selected), expected_digit_count) / expected_digit_count
    region_score = 1.0 if value_region_method in {"ocr_character_geometry", "ocr_token_geometry"} else 0.72
    perspective_score = _perspective_score(source_box_value)
    boundary_score = 0.6 if any(component.get("touches_crop_boundary") for component in selected) else 1.0
    source_overlap_score = float(np.mean([
        max(0, min(component["box"][3], source_y2) - max(component["box"][1], source_y1))
        / max(1, component["height"])
        for component in selected
    ]))
    confidence = (
        consistency_score * 0.25
        + ocr_confidence * 0.15
        + foreground_score * 0.10
        + count_score * 0.10
        + region_score * 0.10
        + perspective_score * 0.05
        + boundary_score * 0.05
        + source_overlap_score * 0.20
    )
    digit_boxes = [component["box"] for component in selected]
    rejected_boxes = [component["box"] for component in components if component not in selected]
    if confidence < 0.65:
        return segmentation_review(
            "Numeral segmentation confidence was too low",
            confidence,
            selected,
            raw_heights,
        )
    result = {
        "status": "OK",
        "method": "connected_components",
        **evidence,
        "candidate_count": len(candidates),
        "confidence_factors": {
            "ocr": round(ocr_confidence, 3),
            "digit_count": round(count_score, 3),
            "height_agreement": round(consistency_score, 3),
            "segmentation": round(foreground_score, 3),
            "value_region": round(region_score, 3),
            "crop_boundary": round(boundary_score, 3),
            "perspective": round(perspective_score, 3),
            "source_box_overlap": round(source_overlap_score, 3),
        },
        "digit_boxes": digit_boxes,
        "digit_heights_px": [round(height, 2) for height in raw_heights],
        "median_digit_height_px": round(float(estimated_height_px), 2),
        "estimated_numeral_height_px": round(float(estimated_height_px), 2),
        "estimated_numeral_height_mm": round(float(estimated_height_mm), 3),
        "confidence": round(float(min(1.0, confidence)), 3),
        "segmentation_confidence": round(float(min(1.0, confidence)), 3),
        "localization_method": value_region_method,
        "localization_confidence": round(region_score, 3),
    }
    coordinate_metadata = coordinate_scale_metadata(
        original_width=image_width,
        original_height=image_height,
        measurement_width=image_width,
        measurement_height=image_height,
        glyph_height_measurement_px=estimated_height_px,
        coordinates_already_original=True,
        pixels_per_mm=calibration_value,
    )
    coordinate_metadata.update({
        "segmentation_crop_width": int(crop.shape[1]),
        "segmentation_crop_height": int(crop.shape[0]),
        "coordinate_system": "original_image_pixels_with_crop_offset",
    })
    result["coordinate_metadata"] = coordinate_metadata
    if debug and not debug_image_path:
        output_directory = Path(debug_dir) if debug_dir is not None else Path("debug")
        debug_image_path = str(output_directory / f"glyph_measurement_{Path(image_path).stem}.jpg")
    if debug_image_path:
        result["debug_image_path"] = debug_image_path
        result["debug_image_saved"] = _save_debug_image(
            image,
            source_box,
            value_region_box,
            digit_boxes,
            rejected_boxes,
            estimated_height_mm,
            f"numeric={numeric_text} | {value_region_method} | seg_conf={confidence:.3f}",
            debug_image_path,
            marker_corners,
        )
    return result


def measure_net_quantity_numeral_height(*args: Any, **kwargs: Any) -> dict[str, Any]:
    """Backward-compatible alias for the original singular function name."""
    return measure_net_quantity_numerals(*args, **kwargs)
