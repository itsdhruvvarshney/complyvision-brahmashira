"""Physical measurements on the rectified label: numeral glyph height and local contrast."""

from __future__ import annotations

import re

import cv2
import numpy as np

from ..ocr.engine import OcrLine

# LM (PC) Rules 2011, Rule 7: minimum height of net-quantity numerals (normal printing)
NUMERAL_HEIGHT_TABLE = (
    (50.0, 1.0),      # up to 50 g / ml
    (200.0, 2.0),     # above 50 up to 200
    (1000.0, 4.0),    # above 200 up to 1000
    (float("inf"), 6.0),  # above 1 kg / 1 L
)


def required_numeral_height_mm(base_value: float) -> float:
    for limit, mm in NUMERAL_HEIGHT_TABLE:
        if base_value <= limit:
            return mm
    return 6.0


def _crop(img: np.ndarray, box, pad_ratio: float = 0.15) -> tuple[np.ndarray, int, int]:
    x, y, w, h = box
    pad = int(max(2, h * pad_ratio))
    x0, y0 = max(0, x - pad), max(0, y - pad)
    x1, y1 = min(img.shape[1], x + w + pad), min(img.shape[0], y + h + pad)
    return img[y0:y1, x0:x1], x0, y0


def _text_mask(gray: np.ndarray) -> np.ndarray:
    _, mask = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    # The minority class is the ink; flip when the label is light text on a dark background
    if (mask > 0).mean() > 0.5:
        mask = 255 - mask
    return mask


def numeral_height(img: np.ndarray, line: OcrLine, px_per_mm: float) -> dict | None:
    """Median height of the digit glyphs in the net-quantity line, converted to millimetres."""
    crop, ox, _ = _crop(img, line.box, 0.2)
    if crop.size == 0:
        return None
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    mask = _text_mask(gray)

    # Restrict to the horizontal span of the numeric token (proportional to its position in the text)
    text = line.text
    m = re.search(r"\d+(?:[.,]\d+)?", text)
    if m and len(text) > 0:
        x_line, w_line = line.box[0] - ox, line.box[2]
        start = x_line + int(w_line * m.start() / len(text)) - 4
        end = x_line + int(w_line * m.end() / len(text)) + 4
        span = mask[:, max(0, start):min(mask.shape[1], end)]
        if span.shape[1] >= 4:
            mask = span

    n, _, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    heights = []
    line_h = line.box[3]
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if h < line_h * 0.35 or h > line_h * 1.4:
            continue
        aspect = w / max(h, 1)
        if 0.12 <= aspect <= 1.1 and area >= h * 1.2:
            heights.append(h)
    if not heights:
        return None
    h_px = float(np.median(heights))
    return {"height_px": round(h_px, 2), "height_mm": round(h_px / px_per_mm, 2), "glyphs": len(heights)}


def _luminance(rgb: np.ndarray) -> float:
    c = rgb.astype(np.float64) / 255.0
    c = np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return float(0.2126 * c[2] + 0.7152 * c[1] + 0.0722 * c[0])  # input is BGR


def local_contrast(img: np.ndarray, box) -> dict | None:
    """WCAG-style contrast ratio between ink and background inside a declaration's box."""
    crop, _, _ = _crop(img, box, 0.1)
    if crop.size == 0 or min(crop.shape[:2]) < 6:
        return None
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    mask = _text_mask(gray) > 0
    ink, bg = crop[mask], crop[~mask]
    if len(ink) < 10 or len(bg) < 10:
        return None
    l_ink = _luminance(np.median(ink, axis=0))
    l_bg = _luminance(np.median(bg, axis=0))
    hi, lo = max(l_ink, l_bg), min(l_ink, l_bg)
    return {"ratio": round((hi + 0.05) / (lo + 0.05), 2), "ink_luminance": round(l_ink, 3), "background_luminance": round(l_bg, 3)}
