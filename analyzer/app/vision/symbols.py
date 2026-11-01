"""Vegetarian / non-vegetarian symbol detector.

FSS (Labelling & Display) Regulations, 2020: a green filled circle inside a green square outline
(vegetarian) or a brown filled triangle / circle inside a brown square outline (non-vegetarian). The
same dots are permitted on soaps, shampoos, toothpaste and cosmetics under LM (PC) Rule 6(4A)(d).

The detector looks for small, roughly square regions of strong green or brown / red hue whose border
band and centre are inked while the ring between them is not - the geometry of the symbol - and
reports the best candidate with a score. Absence is never proof: rules treat "not found" as REVIEW.
"""

from __future__ import annotations

import cv2
import numpy as np

# OpenCV hue is 0-180
_GREEN = ((35, 70, 50), (88, 255, 230))
_BROWN_LO = ((0, 70, 35), (16, 255, 210))
_BROWN_HI = ((165, 70, 35), (180, 255, 210))


def _mask(hsv: np.ndarray, kind: str) -> np.ndarray:
    if kind == "veg":
        return cv2.inRange(hsv, np.array(_GREEN[0]), np.array(_GREEN[1]))
    return cv2.inRange(hsv, np.array(_BROWN_LO[0]), np.array(_BROWN_LO[1])) | \
        cv2.inRange(hsv, np.array(_BROWN_HI[0]), np.array(_BROWN_HI[1]))


def _score(sub: np.ndarray) -> float:
    """sub: binary mask of one candidate square. High when border and centre are inked, ring empty."""
    s = sub.shape[0]
    if s < 10:
        return 0.0
    m = sub > 0
    band = max(1, int(round(s * 0.14)))
    border = np.ones_like(m)
    border[band:s - band, band:s - band] = False
    c0, c1 = int(s * 0.33), int(s * 0.67)
    centre = np.zeros_like(m)
    centre[c0:c1, c0:c1] = True
    ring = ~border & ~centre
    # keep a margin between centre and ring so a large dot is not penalised
    r0, r1 = int(s * 0.22), int(s * 0.78)
    ring[r0:r1, r0:r1] = False
    b = m[border].mean() if border.any() else 0
    c = m[centre].mean() if centre.any() else 0
    r = m[ring].mean() if ring.any() else 1
    return float(max(0.0, min(b, c) - r))


def detect_veg_mark(img: np.ndarray) -> dict:
    h, w = img.shape[:2]
    hsv = cv2.cvtColor(cv2.GaussianBlur(img, (3, 3), 0), cv2.COLOR_BGR2HSV)
    min_side = max(12, int(min(h, w) * 0.012))
    max_side = int(min(h, w) * 0.22)
    best = {"found": False, "kind": None, "score": 0.0, "box": None}
    for kind in ("veg", "non_veg"):
        mask = _mask(hsv, kind)
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            x, y, bw, bh = cv2.boundingRect(c)
            if not (min_side <= bw <= max_side and min_side <= bh <= max_side):
                continue
            if not 0.78 <= bw / max(bh, 1) <= 1.28:
                continue
            side = max(bw, bh)
            sub = cv2.resize(mask[y:y + bh, x:x + bw], (side, side), interpolation=cv2.INTER_NEAREST)
            score = _score(sub)
            if score > best["score"]:
                best = {"found": score >= 0.45, "kind": kind, "score": round(score, 3), "box": [int(x), int(y), int(bw), int(bh)]}
    if not best["found"]:
        best["kind"] = None
    return best
