"""Adaptive OCR rescue.

The fast single pass is enough for most photos. When it leaves a mandatory declaration unread
(MRP, net quantity, a date) or finds almost no text, two cheaper-than-guessing fallbacks run:

1. Orientation: the photo may be turned 90 or 180 degrees. The best of the four orientations wins.
2. Tiles: the image is re-read as overlapping tiles at native resolution, so small print that the
   detector's size cap shrank away becomes legible. New lines are merged into the first pass.
"""

from __future__ import annotations

import numpy as np
import cv2

from .engine import OcrLine, sort_reading_order

REQUIRED_FIELDS = ("mrp", "net_quantity")
DATE_FIELDS = ("mfg_date", "best_before")


def needs_rescue(decl: dict, lines: list[OcrLine]) -> str | None:
    """Returns why a second look is worthwhile, or None when the first pass looks complete."""
    if len(lines) < 4:
        return "very little text was read"
    missing = [k for k in REQUIRED_FIELDS if not decl.get(k)]
    if missing:
        return "missing " + ", ".join(missing)
    if not any(decl.get(k) for k in DATE_FIELDS):
        return "no date declaration was read"
    return None


def _score(lines: list[OcrLine]) -> float:
    """Amount of confidently read text; rewards upright, legible orientations."""
    return sum(ln.confidence * min(len(ln.text), 30) for ln in lines if ln.confidence >= 0.5)


_ROTATIONS = {
    90: cv2.ROTATE_90_CLOCKWISE,
    180: cv2.ROTATE_180,
    270: cv2.ROTATE_90_COUNTERCLOCKWISE,
}


def best_orientation(engine, img: np.ndarray, lines: list[OcrLine]) -> tuple[np.ndarray, list[OcrLine], int]:
    """Tries the other three orientations and keeps the one that reads the most text."""
    best_img, best_lines, best_deg, best_score = img, lines, 0, _score(lines)
    for deg, code in _ROTATIONS.items():
        rotated = cv2.rotate(img, code)
        candidate = engine.read(rotated)
        score = _score(candidate)
        if score > best_score * 1.25 and score > 40:  # clear win only; avoids flip-flopping on noise
            best_img, best_lines, best_deg, best_score = rotated, candidate, deg, score
    return best_img, best_lines, best_deg


def _iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ix = max(0, min(ax + aw, bx + bw) - max(ax, bx))
    iy = max(0, min(ay + ah, by + bh) - max(ay, by))
    inter = ix * iy
    union = aw * ah + bw * bh - inter
    return inter / union if union else 0.0


def _same(a: OcrLine, b: OcrLine) -> bool:
    if _iou(a.box, b.box) >= 0.35:
        return True
    # a line clipped by a tile border sits inside the whole line from the first pass
    ix = max(0, min(a.box[0] + a.box[2], b.box[0] + b.box[2]) - max(a.box[0], b.box[0]))
    iy = max(0, min(a.box[1] + a.box[3], b.box[1] + b.box[3]) - max(a.box[1], b.box[1]))
    smaller = min(a.box[2] * a.box[3], b.box[2] * b.box[3])
    return smaller > 0 and ix * iy / smaller >= 0.7


def merge_lines(base: list[OcrLine], extra: list[OcrLine]) -> list[OcrLine]:
    """Adds lines from `extra` that are new, or better reads of lines already in `base`."""
    merged = list(base)
    for new in extra:
        clash = next((i for i, old in enumerate(merged) if _same(new, old)), None)
        if clash is None:
            merged.append(new)
        elif new.confidence > merged[clash].confidence + 0.05 and len(new.text) >= len(merged[clash].text) - 1:
            merged[clash] = new
    return sort_reading_order(merged)


def tile_read(engine, img: np.ndarray, lines: list[OcrLine], overlap: float = 0.18) -> list[OcrLine]:
    h, w = img.shape[:2]
    # Tall labels split into 1x2, wide ones 2x1, otherwise 2x2
    cols, rows = (1, 2) if h > 1.6 * w else (2, 1) if w > 1.6 * h else (2, 2)
    tw, th = w / cols, h / rows
    mx, my = int(tw * overlap), int(th * overlap)
    result = lines
    for r in range(rows):
        for c in range(cols):
            x0, y0 = max(0, int(c * tw) - mx), max(0, int(r * th) - my)
            x1, y1 = min(w, int((c + 1) * tw) + mx), min(h, int((r + 1) * th) + my)
            found = engine.read(img[y0:y1, x0:x1])
            for ln in found:
                bx, by, bw, bh = ln.box
                ln.box = (bx + x0, by + y0, bw, bh)
                ln.polygon = [[p[0] + x0, p[1] + y0] for p in ln.polygon]
            result = merge_lines(result, found)
    return result


def rescue(engine, img: np.ndarray, lines: list[OcrLine], reason: str) -> tuple[np.ndarray, list[OcrLine], dict]:
    info: dict = {"reason": reason, "rotated_deg": 0, "lines_before": len(lines)}
    if len(lines) < 4 or _score(lines) < 60:
        img, lines, info["rotated_deg"] = best_orientation(engine, img, lines)
    lines = tile_read(engine, img, lines)
    info["lines_after"] = len(lines)
    return img, lines, info
