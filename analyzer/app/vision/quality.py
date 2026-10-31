"""Image quality assessment: sharpness, exposure, glare, contrast, resolution."""

from __future__ import annotations

import cv2
import numpy as np


def _working_copy(img: np.ndarray, long_edge: int = 1400) -> np.ndarray:
    h, w = img.shape[:2]
    scale = long_edge / max(h, w)
    if scale < 1:
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    return img


def glare_mask(img: np.ndarray, protect: list | None = None) -> np.ndarray:
    """Specular highlights: near-saturated, colourless, compact blobs with a bright halo.

    White packaging and white print are bright too, so a blob only counts as glare when it is compact,
    its surrounding ring is bright but clearly darker (the halo of a reflection - white text on a
    coloured background has a dark ring instead), and it is not a large part of the frame.
    """
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    candidate = ((hsv[:, :, 2] >= 250) & (hsv[:, :, 1] <= 40)).astype(np.uint8) * 255
    for box in protect or []:
        x, y, w, h = [int(v) for v in box]
        candidate[max(0, y - 8):y + h + 8, max(0, x - 8):x + w + 8] = 0
    candidate = cv2.morphologyEx(candidate, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    mask = np.zeros_like(candidate)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(candidate, connectivity=8)
    total = candidate.size
    ring_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (17, 17))
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 40 or area > 0.25 * total or min(w, h) < 8 or area / float(w * h) < 0.45:
            continue
        x0, y0 = max(0, x - 12), max(0, y - 12)
        x1, y1 = min(gray.shape[1], x + w + 12), min(gray.shape[0], y + h + 12)
        blob = (labels[y0:y1, x0:x1] == i).astype(np.uint8)
        ring = cv2.dilate(blob, ring_kernel) & (1 - blob)
        if ring.sum() == 0:
            continue
        patch = gray[y0:y1, x0:x1]
        ring_mean = float(patch[ring > 0].mean())
        blob_mean = float(patch[blob > 0].mean())
        if blob_mean - 60 < ring_mean < blob_mean - 6:
            mask[y0:y1, x0:x1][blob > 0] = 255
    return mask


def assess(img: np.ndarray) -> dict:
    small = _working_copy(img)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    h, w = img.shape[:2]

    blur_var = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    brightness = float(gray.mean())
    contrast = float(gray.std())
    dark_ratio = float((gray < 35).mean())
    bright_ratio = float((gray > 235).mean())

    gmask = glare_mask(small)
    glare_ratio = float((gmask > 0).mean())
    largest_glare = 0.0
    if glare_ratio > 0:
        n, _, stats, _ = cv2.connectedComponentsWithStats(gmask, connectivity=8)
        if n > 1:
            largest_glare = float(stats[1:, cv2.CC_STAT_AREA].max()) / gmask.size

    # Uneven lighting: spread of a heavily blurred luminance map
    tiny = cv2.resize(gray, (max(8, gray.shape[1] // 8), max(8, gray.shape[0] // 8)), interpolation=cv2.INTER_AREA)
    background = cv2.GaussianBlur(tiny, (0, 0), sigmaX=max(tiny.shape) / 12)
    unevenness = float(background.std() / max(background.mean(), 1.0))

    # Component scores in 0..1
    sharp_s = float(np.clip((blur_var - 30) / 220, 0, 1))
    expo_s = float(np.clip(1 - abs(brightness - 140) / 110, 0, 1))
    glare_s = float(np.clip(1 - glare_ratio * 12 - largest_glare * 20, 0, 1))
    contrast_s = float(np.clip((contrast - 18) / 45, 0, 1))
    res_s = float(np.clip((min(h, w) - 480) / 720, 0, 1))
    score = round(100 * (0.34 * sharp_s + 0.2 * expo_s + 0.18 * glare_s + 0.16 * contrast_s + 0.12 * res_s), 1)

    warnings: list[dict] = []

    def warn(code: str, severity: str, message: str) -> None:
        warnings.append({"code": code, "severity": severity, "message": message})

    if blur_var < 45:
        warn("BLUR", "high", "Image is blurred; text may be misread. Hold the camera steady and refocus.")
    elif blur_var < 110:
        warn("SOFT_FOCUS", "medium", "Image is slightly soft; sharpening was applied.")
    if brightness < 70 or dark_ratio > 0.45:
        warn("LOW_LIGHT", "medium", "Image is under-exposed; brightness was corrected.")
    if brightness > 205 or bright_ratio > 0.4:
        warn("OVEREXPOSED", "medium", "Image is over-exposed; highlights may hide print.")
    if glare_ratio > 0.012 or largest_glare > 0.006:
        warn("GLARE", "high" if glare_ratio > 0.06 else "medium",
             "Glare detected on the packaging; affected regions were reconstructed.")
    if contrast < 28:
        warn("LOW_CONTRAST", "medium", "Low overall contrast; local contrast enhancement was applied.")
    if unevenness > 0.22:
        warn("UNEVEN_LIGHT", "low", "Uneven lighting / shadows; illumination was normalised.")
    if min(h, w) < 720:
        warn("LOW_RESOLUTION", "medium", f"Low resolution ({w}x{h}); small print may be unreadable.")

    grade = "Good" if score >= 72 else "Fair" if score >= 48 else "Poor"
    if any(wn["severity"] == "high" for wn in warnings) and grade == "Good":
        grade = "Fair"

    return {
        "score": score,
        "grade": grade,
        "blur_variance": round(blur_var, 1),
        "brightness": round(brightness, 1),
        "contrast": round(contrast, 1),
        "glare_ratio": round(glare_ratio, 4),
        "dark_ratio": round(dark_ratio, 4),
        "illumination_unevenness": round(unevenness, 3),
        "resolution": {"width": w, "height": h},
        "warnings": warnings,
    }
