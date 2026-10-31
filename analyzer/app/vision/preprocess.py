"""OpenCV pre-processing: perspective / tilt correction, glare repair, exposure and contrast normalisation.

Every step decides from measurements whether it is needed and reports what it did, so the final report
can show exactly how the evidence image was derived from the original capture.
"""

from __future__ import annotations

import math

import cv2
import numpy as np

from .quality import glare_mask


def _step(name: str, applied: bool, detail: str, **params) -> dict:
    return {"step": name, "applied": applied, "detail": detail, "params": params}


def resize_for_analysis(img: np.ndarray, max_long_edge: int) -> tuple[np.ndarray, dict]:
    h, w = img.shape[:2]
    long_edge = max(h, w)
    if long_edge > max_long_edge:
        s = max_long_edge / long_edge
        out = cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
        return out, _step("resize", True, f"Downscaled {w}x{h} to {out.shape[1]}x{out.shape[0]}", scale=round(s, 4))
    if long_edge < 1200:
        s = min(2.0, 1600 / long_edge)
        out = cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_CUBIC)
        return out, _step("resize", True, f"Upscaled {w}x{h} to {out.shape[1]}x{out.shape[0]} for small print", scale=round(s, 4))
    return img, _step("resize", False, f"Kept native {w}x{h}", scale=1.0)


def _order_quad(pts: np.ndarray) -> np.ndarray:
    pts = pts.reshape(4, 2).astype(np.float32)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def correct_perspective(img: np.ndarray) -> tuple[np.ndarray, dict]:
    """Flattens the label when its four corners are clearly visible (photo taken at an angle)."""
    h, w = img.shape[:2]
    scale = 900 / max(h, w)
    small = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else img.copy()
    scale = min(scale, 1.0)
    gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    edges = cv2.Canny(gray, 40, 120)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    area_total = small.shape[0] * small.shape[1]

    best = None
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:6]:
        area = cv2.contourArea(c)
        if area < 0.30 * area_total or area > 0.97 * area_total:
            continue
        approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
        if len(approx) == 4 and cv2.isContourConvex(approx):
            best = approx
            break
    if best is None:
        return img, _step("perspective", False, "No clear label boundary - perspective left unchanged")

    quad = _order_quad(best) / scale
    tl, tr, br, bl = quad
    width = int(max(np.linalg.norm(br - bl), np.linalg.norm(tr - tl)))
    height = int(max(np.linalg.norm(tr - br), np.linalg.norm(tl - bl)))
    if width < 200 or height < 200:
        return img, _step("perspective", False, "Detected boundary too small to rectify")

    # Only rectify when the shape is meaningfully non-rectangular
    angles = []
    for i in range(4):
        a, b, c = quad[i - 1], quad[i], quad[(i + 1) % 4]
        v1, v2 = a - b, c - b
        cosang = float(np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-6))
        angles.append(math.degrees(math.acos(np.clip(cosang, -1, 1))))
    distortion = max(abs(a - 90) for a in angles)
    if distortion < 4:
        return img, _step("perspective", False, f"Label is already square to the camera ({distortion:.1f} deg)")

    dst = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32)
    matrix = cv2.getPerspectiveTransform(quad.astype(np.float32), dst)
    out = cv2.warpPerspective(img, matrix, (width, height), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return out, _step("perspective", True, f"Rectified label viewed at an angle (max corner deviation {distortion:.1f} deg)",
                      corner_deviation_deg=round(distortion, 1))


def estimate_skew(gray: np.ndarray) -> float | None:
    """Dominant text-line angle in degrees (positive = counter-clockwise), from line-shaped blobs."""
    h, w = gray.shape[:2]
    s = 1200 / max(h, w)
    if s < 1:
        gray = cv2.resize(gray, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
    binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 31, 15)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(15, gray.shape[1] // 60), 3))
    lines = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(lines, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    angles, weights = [], []
    for c in contours:
        (cx, cy), (rw, rh), ang = cv2.minAreaRect(c)
        long_side, short_side = max(rw, rh), min(rw, rh)
        if long_side < gray.shape[1] * 0.06 or short_side < 4 or long_side / short_side < 3.5:
            continue
        # Normalise OpenCV's rectangle angle to the angle of the long side in (-45, 45]
        if rw < rh:
            ang = ang - 90
        while ang <= -45:
            ang += 90
        while ang > 45:
            ang -= 90
        angles.append(ang)
        weights.append(long_side)

    if len(angles) < 3:
        # Fallback: probabilistic Hough on edges
        edges = cv2.Canny(gray, 50, 150)
        segs = cv2.HoughLinesP(edges, 1, np.pi / 360, threshold=80, minLineLength=gray.shape[1] // 6, maxLineGap=10)
        if segs is None:
            return None
        for x1, y1, x2, y2 in segs[:, 0]:
            ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
            if abs(ang) <= 45:
                angles.append(ang)
                weights.append(math.hypot(x2 - x1, y2 - y1))
        if len(angles) < 3:
            return None

    order = np.argsort(angles)
    a = np.array(angles)[order]
    wts = np.array(weights)[order]
    cum = np.cumsum(wts)
    median = float(a[np.searchsorted(cum, cum[-1] / 2)])
    # OpenCV image coordinates have y pointing down; report the visual (counter-clockwise) tilt
    return -median


def deskew(img: np.ndarray) -> tuple[np.ndarray, dict, float]:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    angle = estimate_skew(gray)
    if angle is None:
        return img, _step("deskew", False, "Not enough text lines to measure tilt"), 0.0
    if abs(angle) < 0.6:
        return img, _step("deskew", False, f"Tilt {angle:.1f} deg is negligible", angle_deg=round(angle, 2)), angle

    h, w = img.shape[:2]
    center = (w / 2, h / 2)
    matrix = cv2.getRotationMatrix2D(center, -angle, 1.0)
    cos, sin = abs(matrix[0, 0]), abs(matrix[0, 1])
    new_w, new_h = int(h * sin + w * cos), int(h * cos + w * sin)
    matrix[0, 2] += new_w / 2 - center[0]
    matrix[1, 2] += new_h / 2 - center[1]
    out = cv2.warpAffine(img, matrix, (new_w, new_h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return out, _step("deskew", True, f"Rotated by {angle:.1f} deg to straighten text lines", angle_deg=round(angle, 2)), angle


def repair_glare(img: np.ndarray, protect: list | None = None) -> tuple[np.ndarray, dict]:
    mask = glare_mask(img, protect)
    ratio = float((mask > 0).mean())
    if ratio < 0.0015:
        return img, _step("glare", False, "No significant glare")
    if ratio > 0.30:
        return img, _step("glare", False, f"Glare covers {ratio:.0%} of the frame - too large to reconstruct; recapture advised",
                          coverage=round(ratio, 4))
    mask = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)), iterations=1)
    radius = 5 if ratio < 0.02 else 9
    out = cv2.inpaint(img, mask, radius, cv2.INPAINT_TELEA)
    return out, _step("glare", True, f"Reconstructed {ratio:.2%} of the frame affected by glare", coverage=round(ratio, 4))


def normalise_illumination(img: np.ndarray) -> tuple[np.ndarray, dict]:
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    l_chan = lab[:, :, 0].astype(np.float32)
    # Estimate the lighting field on a small copy (a huge blur kernel at full size is slow)
    h, w = l_chan.shape
    tiny = cv2.resize(l_chan, (max(8, w // 8), max(8, h // 8)), interpolation=cv2.INTER_AREA)
    tiny = cv2.GaussianBlur(tiny, (0, 0), sigmaX=max(tiny.shape) / 14)
    background = cv2.resize(tiny, (w, h), interpolation=cv2.INTER_LINEAR)
    unevenness = float(background.std() / max(background.mean(), 1.0))
    if unevenness < 0.16:
        return img, _step("illumination", False, f"Lighting is even ({unevenness:.2f})")
    flat = l_chan / np.maximum(background, 1.0) * float(background.mean())
    lab[:, :, 0] = np.clip(flat, 0, 255).astype(np.uint8)
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR), _step(
        "illumination", True, f"Flattened shadows / uneven lighting (unevenness {unevenness:.2f})", unevenness=round(unevenness, 3))


def correct_exposure(img: np.ndarray) -> tuple[np.ndarray, dict]:
    mean = float(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).mean())
    if 85 <= mean <= 215:
        return img, _step("exposure", False, f"Exposure is fine (mean {mean:.0f})")
    target = 135.0 if mean < 85 else 190.0  # light packaging is legitimately bright - only tame clipping
    gamma = math.log(target / 255.0) / math.log(max(min(mean, 254.0), 1.0) / 255.0)
    gamma = float(np.clip(gamma, 0.35, 1.8))
    table = np.array([((i / 255.0) ** gamma) * 255 for i in range(256)], dtype=np.uint8)
    out = cv2.LUT(img, table)
    kind = "Brightened under-exposed" if mean < 85 else "Toned down over-exposed"
    return out, _step("exposure", True, f"{kind} image (mean {mean:.0f}, gamma {gamma:.2f})", gamma=round(gamma, 3))


def enhance_contrast(img: np.ndarray, contrast: float) -> tuple[np.ndarray, dict]:
    clip = 3.2 if contrast < 30 else 2.4 if contrast < 50 else 1.6
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    clahe = cv2.createCLAHE(clipLimit=clip, tileGridSize=(8, 8))
    lab[:, :, 0] = clahe.apply(lab[:, :, 0])
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR), _step(
        "contrast", True, f"Adaptive local contrast (CLAHE, clip {clip})", clip_limit=clip)


def denoise(img: np.ndarray, brightness: float) -> tuple[np.ndarray, dict]:
    if brightness >= 80:
        return img, _step("denoise", False, "Noise level acceptable")
    out = cv2.fastNlMeansDenoisingColored(img, None, 5, 5, 7, 21)
    return out, _step("denoise", True, "Removed low-light sensor noise (non-local means)")


def sharpen(img: np.ndarray, blur_variance: float) -> tuple[np.ndarray, dict]:
    if blur_variance >= 120:
        return img, _step("sharpen", False, "Image is sharp")
    amount = 1.2 if blur_variance < 60 else 0.7
    soft = cv2.GaussianBlur(img, (0, 0), sigmaX=1.6)
    out = cv2.addWeighted(img, 1 + amount, soft, -amount, 0)
    return out, _step("sharpen", True, f"Unsharp mask (amount {amount}) for soft focus", amount=amount)


def correct_geometry(img: np.ndarray, max_long_edge: int) -> tuple[np.ndarray, list[dict], dict]:
    """Resize, perspective and tilt correction. Photometric steps run afterwards, so calibration
    markers can be found on an undistorted but otherwise untouched image."""
    steps: list[dict] = []
    out, s = resize_for_analysis(img, max_long_edge); steps.append(s)
    out, s = correct_perspective(out); steps.append(s)
    out, s, skew = deskew(out); steps.append(s)
    return out, steps, {"skew_deg": round(skew, 2), "width": out.shape[1], "height": out.shape[0]}


def correct_photometry(img: np.ndarray, quality: dict, protect: list | None = None) -> tuple[np.ndarray, list[dict]]:
    steps: list[dict] = []
    out, s = repair_glare(img, protect); steps.append(s)
    out, s = normalise_illumination(out); steps.append(s)
    out, s = correct_exposure(out); steps.append(s)
    out, s = denoise(out, quality.get("brightness", 128)); steps.append(s)
    out, s = enhance_contrast(out, quality.get("contrast", 50)); steps.append(s)
    out, s = sharpen(out, quality.get("blur_variance", 200)); steps.append(s)
    return out, steps


def preprocess(img: np.ndarray, quality: dict, max_long_edge: int) -> tuple[np.ndarray, list[dict], dict]:
    geo, steps, geometry = correct_geometry(img, max_long_edge)
    out, more = correct_photometry(geo, quality)
    return out, steps + more, geometry
