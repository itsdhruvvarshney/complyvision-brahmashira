"""Physical measurements on the rectified label: numeral glyph height and local contrast."""

from __future__ import annotations

import re

import cv2
import numpy as np

from ..ocr.engine import OcrLine

# LM (PC) Rules 2011, Rule 7(2), Table-I as substituted by G.S.R. 629(E) (2017) with corrigendum
# G.S.R. 1373(E): minimum height of numerals and letters by principal display panel area (cm²) -
# (upper bound of the band, normal printing mm, blown / formed / moulded on the container mm)
NUMERAL_HEIGHT_TABLE = (
    (50.0, 1.0, 2.0),
    (100.0, 1.5, 3.0),
    (500.0, 2.5, 4.0),
    (2500.0, 4.0, 6.0),
    (float("inf"), 6.0, 6.0),
)


def required_numeral_height_mm(pdp_area_cm2: float, moulded: bool = False) -> float:
    for limit, normal, blown in NUMERAL_HEIGHT_TABLE:
        if pdp_area_cm2 <= limit:
            return blown if moulded else normal
    return 6.0


def estimate_pdp_area_cm2(lines: list[OcrLine], image_shape, px_per_mm: float) -> dict | None:
    """Principal display panel area from the calibrated photo (Rule 7(4)).

    The label text block (padded by 10 % on each side, never beyond the photo) is a conservative lower
    bound of the panel the declarations sit on; the whole cropped photo is the upper bound.
    """
    if px_per_mm <= 0 or not lines:
        return None
    h, w = image_shape[:2]
    x0 = min(ln.box[0] for ln in lines)
    y0 = min(ln.box[1] for ln in lines)
    x1 = max(ln.box[0] + ln.box[2] for ln in lines)
    y1 = max(ln.box[1] + ln.box[3] for ln in lines)
    bw, bh = (x1 - x0) * 1.2, (y1 - y0) * 1.2
    bw, bh = min(bw, w), min(bh, h)
    to_cm2 = 1.0 / (px_per_mm * px_per_mm) / 100.0
    return {"estimate_cm2": round(bw * bh * to_cm2, 1), "frame_cm2": round(w * h * to_cm2, 1),
            "width_cm": round(bw / px_per_mm / 10, 1), "height_cm": round(bh / px_per_mm / 10, 1), "method": "label text block"}


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
