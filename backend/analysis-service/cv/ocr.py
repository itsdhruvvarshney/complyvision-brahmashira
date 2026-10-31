"""OCR output normalization and conservative quantity-crop recovery."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import cv2

from extract_fields import QUANTITY_LABEL_RE, is_standalone_quantity_candidate


EMBOSSED_DECLARATION_LABEL_RE = re.compile(
    r"\b(?:MFD|MFG|MANUFACTURE(?:D)?|BATCH\s*(?:NO\.?)?|M\s*\.?\s*R\s*\.?\s*P\.?)\b",
    re.I,
)
SEE_BELOW_RE = re.compile(r"\bSEE\s+BELOW\b", re.I)


def _as_box(value: Any, offset: tuple[int, int] = (0, 0)) -> list[int]:
    raw = value.tolist() if hasattr(value, "tolist") else list(value)
    x_offset, y_offset = offset
    if raw and isinstance(raw[0], (list, tuple)):
        xs = [float(point[0]) for point in raw]
        ys = [float(point[1]) for point in raw]
        raw = [min(xs), min(ys), max(xs), max(ys)]
    return [
        int(round(float(raw[0]) + x_offset)),
        int(round(float(raw[1]) + y_offset)),
        int(round(float(raw[2]) + x_offset)),
        int(round(float(raw[3]) + y_offset)),
    ]


def predict_ocr_items(
    ocr: Any,
    image: str | Any,
    *,
    offset: tuple[int, int] = (0, 0),
    prediction_metadata: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Normalize PaddleOCR lines and retain word-level geometry when available."""
    try:
        predictions = ocr.predict(image, return_word_box=True)
    except TypeError:  # Test doubles and older compatible wrappers.
        predictions = ocr.predict(image)
    items: list[dict[str, Any]] = []
    for prediction in predictions:
        if prediction_metadata is not None and "orientation_angle" not in prediction_metadata:
            preprocessor = prediction.get("doc_preprocessor_res")
            if preprocessor is not None:
                prediction_metadata["orientation_angle"] = int(preprocessor.get("angle", 0) or 0)
                prediction_metadata["oriented_image"] = preprocessor.get("output_img")
        texts = prediction.get("rec_texts")
        scores = prediction.get("rec_scores")
        boxes = prediction.get("rec_boxes")
        words = prediction.get("text_word")
        word_boxes = prediction.get("text_word_boxes")
        texts = [] if texts is None else texts
        scores = [] if scores is None else scores
        boxes = [] if boxes is None else boxes
        words = [] if words is None else words
        word_boxes = [] if word_boxes is None else word_boxes
        for index, (text, score, box) in enumerate(zip(texts, scores, boxes)):
            tokens = []
            if index < len(words) and index < len(word_boxes):
                for token, token_box in zip(words[index], word_boxes[index]):
                    if str(token).strip():
                        tokens.append({
                            "text": str(token),
                            "box": _as_box(token_box, offset),
                        })
            item = {
                "text": str(text),
                "confidence": float(score),
                "box": _as_box(box, offset),
            }
            if tokens:
                item["tokens"] = tokens
            items.append(item)
    return items


def recover_split_quantity_items(
    image_path: str | Path,
    items: list[dict[str, Any]],
    ocr: Any,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Retry tight label-adjacent crops when full-image OCR lost the value/unit.

    Recovery uses the already configured OCR instance and accepts only a complete,
    standalone number-plus-unit line. It never infers a unit from the label.
    """
    if any(is_standalone_quantity_candidate(str(item.get("text") or "")) for item in items):
        return items, {"attempted": False, "recovered": False, "reason": "quantity_candidate_already_present"}
    image = cv2.imread(str(image_path))
    if image is None:
        return items, {"attempted": False, "recovered": False, "reason": "image_unreadable"}
    height, width = image.shape[:2]
    labels = [item for item in items if QUANTITY_LABEL_RE.search(str(item.get("text") or ""))]
    if not labels:
        return items, {"attempted": False, "recovered": False, "reason": "quantity_label_not_found"}

    recovered: list[dict[str, Any]] = []
    attempted_crops = []
    for label in labels:
        x1, y1, x2, y2 = (int(value) for value in label["box"][:4])
        line_height = max(12, y2 - y1)
        label_width = max(line_height * 2, x2 - x1)
        crop_box = (
            max(0, x1 - int(line_height * 0.7)),
            max(0, y1 - int(line_height * 0.8)),
            min(width, max(x2 + int(label_width * 2.8), x1 + int(line_height * 9))),
            min(height, y2 + int(line_height * 4.2)),
        )
        cx1, cy1, cx2, cy2 = crop_box
        attempted_crops.append(list(crop_box))
        crop = image[cy1:cy2, cx1:cx2]
        for candidate in predict_ocr_items(ocr, crop, offset=(cx1, cy1)):
            text = re.sub(r"\s+", " ", candidate["text"]).strip()
            if not is_standalone_quantity_candidate(text):
                continue
            candidate["recovered_from_quantity_crop"] = True
            candidate["source_image"] = str(image_path)
            if not any(
                existing.get("text") == candidate["text"]
                and existing.get("box") == candidate["box"]
                for existing in items + recovered
            ):
                recovered.append(candidate)
    return items + recovered, {
        "attempted": True,
        "recovered": bool(recovered),
        "attempted_crops": attempted_crops,
        "recovered_item_count": len(recovered),
    }


def _embossed_variants(crop: Any) -> list[Any]:
    """Create OCR views that make shallow embossed strokes locally visible."""
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8)).apply(gray)
    sharpened = cv2.addWeighted(clahe, 2.1, cv2.GaussianBlur(clahe, (0, 0), 2.0), -1.1, 0)

    # Embossing may be lit from either direction. Morphological top/black-hat
    # views retain both the highlight and shadow halves of each stamped glyph.
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
    top_hat = cv2.morphologyEx(clahe, cv2.MORPH_TOPHAT, kernel)
    black_hat = cv2.morphologyEx(clahe, cv2.MORPH_BLACKHAT, kernel)
    relief = cv2.normalize(
        cv2.addWeighted(top_hat, 1.0, black_hat, 1.0, 0),
        None,
        0,
        255,
        cv2.NORM_MINMAX,
    )
    return [
        cv2.cvtColor(sharpened, cv2.COLOR_GRAY2BGR),
        cv2.cvtColor(relief, cv2.COLOR_GRAY2BGR),
    ]


def recover_embossed_declaration_items(
    image_path: str | Path,
    items: list[dict[str, Any]],
    ocr: Any,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Retry the area indicated by an explicit ``MFD/MRP -- see below`` cue.

    Manufacturers often stamp the actual date, batch and price into glossy
    plastic well below the printed declaration label. Full-image OCR commonly
    detects the printed cue but loses those low-contrast embossed characters.
    This recovery is deliberately gated by both parts of that cue.
    """
    labels = [
        item for item in items
        if EMBOSSED_DECLARATION_LABEL_RE.search(str(item.get("text") or ""))
    ]
    below_items = [item for item in items if SEE_BELOW_RE.search(str(item.get("text") or ""))]
    if not below_items:
        # OCR frequently splits the two-word cue into separate lines.
        see_items = [item for item in items if re.fullmatch(r"\s*SEE\s*", str(item.get("text") or ""), re.I)]
        below_words = [item for item in items if re.fullmatch(r"\s*BELOW\s*", str(item.get("text") or ""), re.I)]
        for see_item in see_items:
            sx1, sy1, sx2, sy2 = see_item.get("box") or [0, 0, 0, 0]
            for below_item in below_words:
                bx1, by1, bx2, by2 = below_item.get("box") or [0, 0, 0, 0]
                if (
                    abs(bx1 - sx1) <= max(80, sx2 - sx1)
                    and -max(20, sy2 - sy1) <= by1 - sy2 <= max(80, (sy2 - sy1) * 3)
                ):
                    below_items.extend((see_item, below_item))
                    break
            if below_items:
                break
    if not labels or not below_items:
        return items, {"attempted": False, "recovered": False, "reason": "embossed_declaration_cue_not_found"}

    image = cv2.imread(str(image_path))
    if image is None:
        return items, {"attempted": False, "recovered": False, "reason": "image_unreadable"}
    height, width = image.shape[:2]
    cue_boxes = [item.get("box") for item in labels + below_items if item.get("box")]
    if not cue_boxes:
        return items, {"attempted": False, "recovered": False, "reason": "cue_geometry_unavailable"}

    x1 = min(int(box[0]) for box in cue_boxes)
    y2 = max(int(box[3]) for box in cue_boxes)
    x2 = max(int(box[2]) for box in cue_boxes)
    cue_width = max(80, x2 - x1)
    cue_height = max(12, max(int(box[3]) - int(box[1]) for box in cue_boxes))
    crop_box = (
        max(0, x1 - cue_width // 3),
        min(height, y2 + cue_height),
        min(width, x2 + cue_width * 2),
        height,
    )
    cx1, cy1, cx2, cy2 = crop_box
    if cx2 <= cx1 or cy2 <= cy1:
        return items, {"attempted": False, "recovered": False, "reason": "empty_recovery_crop"}

    crop = image[cy1:cy2, cx1:cx2]
    recovered: list[dict[str, Any]] = []
    errors: list[str] = []
    for variant_index, variant in enumerate(_embossed_variants(crop), start=1):
        try:
            candidates = predict_ocr_items(ocr, variant, offset=(cx1, cy1))
        except Exception as exc:
            errors.append(str(exc))
            continue
        for candidate in candidates:
            text = re.sub(r"\s+", " ", str(candidate.get("text") or "")).strip()
            if not text:
                continue
            candidate.update({
                "text": text,
                "source_image": str(image_path),
                "recovered_from_declaration_crop": True,
                "recovery_variant": variant_index,
            })
            duplicate = next((
                existing for existing in items + recovered
                if str(existing.get("text") or "").casefold() == text.casefold()
                and existing.get("box") == candidate.get("box")
            ), None)
            if duplicate is None:
                recovered.append(candidate)

    # Restore reading order so semantic windows can associate recovered values
    # with the printed declaration cue even when many unrelated lines exist.
    combined = items + recovered
    combined.sort(key=lambda item: (
        int((item.get("box") or [0, 0, 0, 0])[1]),
        int((item.get("box") or [0, 0, 0, 0])[0]),
    ))
    return combined, {
        "attempted": True,
        "recovered": bool(recovered),
        "crop_box": list(crop_box),
        "recovered_item_count": len(recovered),
        "errors": errors,
    }
