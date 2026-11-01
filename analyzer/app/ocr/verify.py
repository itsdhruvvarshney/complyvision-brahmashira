"""Conditional OCR verification.

Lines that matter for a compliance decision (prices, quantities, dates) and lines read with low
confidence are re-read from a tight crop in one batched recognition call. When the verifier is a
stronger model than the main pass (the "fast" profile), its reading of a critical line is preferred
unless it is clearly worse; otherwise a re-read must be noticeably more confident to be used. Either
way it must still look like the same kind of content.
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


def _upscaled(crop: np.ndarray) -> np.ndarray:
    h, w = crop.shape[:2]
    scale = max(1.0, min(3.0, 48.0 / max(h, 1)))
    return cv2.resize(crop, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_CUBIC) if scale > 1 else crop


def _binarised(crop: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(_upscaled(crop), cv2.COLOR_BGR2GRAY)
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    if (otsu == 0).mean() > 0.5:  # light text on a dark background -> dark on light
        otsu = 255 - otsu
    return cv2.cvtColor(otsu, cv2.COLOR_GRAY2BGR)


def _shape(text: str) -> str:
    return re.sub(r"[A-Za-z]+", "a", re.sub(r"\d+", "9", text.lower())).replace(" ", "")


def _digits(text: str) -> str:
    return re.sub(r"\D", "", text)


def verify_lines(engine, img: np.ndarray, lines: list[OcrLine], below: float, max_lines: int = 10) -> dict:
    h, w = img.shape[:2]
    stronger = bool(getattr(engine, "verifier_is_stronger", False))

    # Critical lines first (they decide rules), then the weakest of the rest
    picked = []
    for ln in lines:
        critical = bool(CRITICAL.search(ln.text))
        if critical or ln.confidence < below:
            picked.append((0 if critical else 1, ln.confidence, ln, critical))
    picked.sort(key=lambda t: (t[0], t[1]))
    picked = picked[:max_lines]

    jobs = []  # (line, critical, crop variant)
    for _, _, ln, critical in picked:
        x, y, bw, bh = ln.box
        pad = max(4, int(bh * 0.25))
        crop = img[max(0, y - pad):min(h, y + bh + pad), max(0, x - pad):min(w, x + bw + pad)]
        if crop.size == 0 or crop.shape[0] < 6 or crop.shape[1] < 6:
            continue
        jobs.append((ln, critical, _upscaled(crop)))
        if ln.confidence < 0.7:  # very weak reads also get a binarised attempt
            jobs.append((ln, critical, _binarised(crop)))

    if not jobs:
        return {"checked": 0, "improved": 0}

    batch = getattr(engine, "recognize_batch", None)
    try:
        reads = batch([j[2] for j in jobs]) if batch else [engine.recognize(j[2]) for j in jobs]
    except Exception:  # a failed re-read never breaks the pipeline
        return {"checked": 0, "improved": 0, "error": "verification failed"}

    best: dict[int, tuple[str, float]] = {}
    for (ln, critical, _), (text, conf) in zip(jobs, reads):
        if not text:
            continue
        similar = _shape(text)[:6] == _shape(ln.text)[:6] or ln.confidence < 0.6
        # The numbers in a price / quantity / date must not change wholesale on a re-read
        same_numbers = _digits(ln.text)[-4:] == _digits(text)[-4:] or not _digits(ln.text)
        if stronger and critical:
            ok = similar and same_numbers and conf >= max(0.8, ln.confidence - 0.05)
        else:
            ok = similar and conf > ln.confidence + 0.03
        cur = best.get(id(ln))
        if ok and (cur is None or conf > cur[1]):
            best[id(ln)] = (text, conf)

    improved = 0
    for ln in {id(j[0]): j[0] for j in jobs}.values():
        if id(ln) in best and best[id(ln)][0] != ln.text:
            text, conf = best[id(ln)]
            ln.original_text, ln.original_confidence = ln.text, ln.confidence
            ln.text, ln.confidence, ln.verified = text, conf, True
            improved += 1
    return {"checked": len({id(j[0]) for j in jobs}), "improved": improved}
