"""Conditional OCR verification.

Lines that matter for a compliance decision (prices, quantities, dates) or that were read with low
confidence are re-read from a tight crop under several enhancement variants. A re-read replaces the
original only when it is clearly more confident and still looks like the same kind of content.
"""

from __future__ import annotations

import re

import cv2
import numpy as np

from .engine import OcrLine

CRITICAL = re.compile(
    r"(m\.?\s*r\.?\s*p|rs\.?|₹|inr|price|net|qty|quantity|wt|weight|vol|\d\s*(kg|g|gm|ml|l|ltr)\b|"
    r"mfg|mfd|pkd|best|before|exp|use\s*by|batch|lot|\d{1,2}[/-]\d{2,4})",
    re.IGNORECASE,
)


def _variants(crop: np.ndarray) -> list[tuple[str, np.ndarray]]:
    h, w = crop.shape[:2]
    scale = max(1.0, min(3.0, 64.0 / max(h, 1)))
    up = cv2.resize(crop, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC)
    gray = cv2.cvtColor(up, cv2.COLOR_BGR2GRAY)
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    if (otsu == 0).mean() > 0.5:  # light text on dark background -> make it dark on light
        otsu = 255 - otsu
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(4, 4)).apply(gray)
    sharp = cv2.addWeighted(clahe, 1.6, cv2.GaussianBlur(clahe, (0, 0), 1.2), -0.6, 0)
    return [
        ("upscaled", up),
        ("binarised", cv2.cvtColor(otsu, cv2.COLOR_GRAY2BGR)),
        ("contrast", cv2.cvtColor(sharp, cv2.COLOR_GRAY2BGR)),
    ]


def _shape(text: str) -> str:
    return re.sub(r"[A-Za-z]+", "a", re.sub(r"\d+", "9", text.lower())).replace(" ", "")


def verify_lines(engine, img: np.ndarray, lines: list[OcrLine], below: float) -> dict:
    checked = improved = 0
    h, w = img.shape[:2]
    for ln in lines:
        critical = bool(CRITICAL.search(ln.text))
        if ln.confidence >= below and not (critical and ln.confidence < 0.97):
            continue
        x, y, bw, bh = ln.box
        pad = max(4, int(bh * 0.25))
        x0, y0 = max(0, x - pad), max(0, y - pad)
        x1, y1 = min(w, x + bw + pad), min(h, y + bh + pad)
        crop = img[y0:y1, x0:x1]
        if crop.size == 0 or crop.shape[0] < 6 or crop.shape[1] < 6:
            continue
        checked += 1
        best_text, best_conf = ln.text, ln.confidence
        for _, variant in _variants(crop):
            try:
                text, conf = engine.recognize(variant)
            except Exception:  # a failed re-read never breaks the pipeline
                continue
            if not text:
                continue
            # A re-read must keep the same broad structure unless the original was very weak
            similar = _shape(text)[:6] == _shape(ln.text)[:6] or ln.confidence < 0.6
            if conf > best_conf + 0.03 and similar:
                best_text, best_conf = text, conf
        if best_text != ln.text or best_conf > ln.confidence:
            ln.original_text, ln.original_confidence = ln.text, ln.confidence
            ln.text, ln.confidence, ln.verified = best_text, best_conf, True
            improved += 1
    return {"checked": checked, "improved": improved}
