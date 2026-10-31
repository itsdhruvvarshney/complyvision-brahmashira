import cv2
import numpy as np


def calculate_blur_score(image):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    score = cv2.Laplacian(
        gray,
        cv2.CV_64F
    ).var()

    return float(score)


def calculate_brightness(image):
    gray = cv2.cvtColor(
        image,
        cv2.COLOR_BGR2GRAY
    )

    return float(np.mean(gray))


def calculate_glare_ratio(image):
    """
    Estimate specular glare.

    Bright low-saturation pixels are candidates,
    but large bright regions touching the image
    border are treated as background and removed.
    """

    hsv = cv2.cvtColor(
        image,
        cv2.COLOR_BGR2HSV
    )

    saturation = hsv[:, :, 1]
    value = hsv[:, :, 2]

    # Candidate white/specular regions
    glare_mask = (
        (value > 245) &
        (saturation < 35)
    ).astype(np.uint8) * 255

    # Find connected bright regions
    num_labels, labels, stats, _ = (
        cv2.connectedComponentsWithStats(
            glare_mask,
            connectivity=8
        )
    )

    height, width = glare_mask.shape

    filtered_mask = np.zeros_like(glare_mask)

    for label in range(1, num_labels):

        x = stats[label, cv2.CC_STAT_LEFT]
        y = stats[label, cv2.CC_STAT_TOP]
        w = stats[label, cv2.CC_STAT_WIDTH]
        h = stats[label, cv2.CC_STAT_HEIGHT]
        area = stats[label, cv2.CC_STAT_AREA]

        touches_border = (
            x <= 2
            or y <= 2
            or x + w >= width - 2
            or y + h >= height - 2
        )

        # Ignore border-connected white background
        if touches_border:
            continue

        # Ignore tiny noise
        if area < 20:
            continue

        filtered_mask[labels == label] = 255

    glare_pixels = np.count_nonzero(filtered_mask)

    total_pixels = height * width

    return float(glare_pixels / total_pixels)


def estimate_skew_angle(gray):
    """Estimate dominant near-horizontal text/label tilt in degrees."""
    edges = cv2.Canny(gray, 60, 160)
    lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=45,
                            minLineLength=max(24, min(gray.shape) // 20), maxLineGap=12)
    if lines is None:
        return 0.0
    angles = []
    for line in np.asarray(lines).reshape(-1, 4):
        x1, y1, x2, y2 = map(float, line)
        angle = float(np.degrees(np.arctan2(y2 - y1, x2 - x1)))
        if abs(angle) <= 25:
            angles.append(angle)
    return float(np.median(angles)) if angles else 0.0


def estimate_text_contrast(gray):
    """Estimate contrast from edge-bearing image tiles (not a legal test)."""
    height, width = gray.shape
    tile = max(24, min(height, width) // 24)
    scores = []
    for y in range(0, height, tile):
        for x in range(0, width, tile):
            patch = gray[y:min(y + tile, height), x:min(x + tile, width)]
            if patch.size < 64:
                continue
            edges = cv2.Canny(patch, 50, 140)
            if np.count_nonzero(edges) / patch.size >= 0.035:
                scores.append(float(np.std(patch)))
    return float(np.median(scores)) if scores else float(np.std(gray))

def analyze_image_quality(image_path):
    image = cv2.imread(image_path)

    if image is None:
        raise ValueError(
            f"Could not read image: {image_path}"
        )

    height, width = image.shape[:2]

    blur_score = calculate_blur_score(image)

    brightness = calculate_brightness(image)

    glare_ratio = calculate_glare_ratio(image)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    skew_angle = estimate_skew_angle(gray)
    text_contrast = estimate_text_contrast(gray)

    critical_issues = []

    # Prototype thresholds only.
    # These are NOT legal thresholds.
    warnings = []

    if blur_score < 80:
        critical_issues.append("IMAGE_BLURRY")

    if brightness < 60:
        critical_issues.append("IMAGE_TOO_DARK")

    if brightness > 235:
        critical_issues.append("IMAGE_TOO_BRIGHT")

    if glare_ratio > 0.10:
        critical_issues.append("HIGH_GLARE")
    
    if glare_ratio > 0.05:
        warnings.append("MODERATE_GLARE")

    if width < 1000 or height < 1000:
        warnings.append("LOW_RESOLUTION")

    if abs(skew_angle) > 5:
        warnings.append("SKEW_ANGLE")

    if text_contrast < 28:
        warnings.append("LOW_TEXT_CONTRAST")

    usable = len(critical_issues) == 0

    return {
        "width": width,
        "height": height,

        "blur_score": round(
            blur_score,
            2
        ),

        "brightness": round(
            brightness,
            2
        ),

        "glare_ratio": round(
            glare_ratio,
            4
        ),

        "skew_angle_degrees": round(skew_angle, 2),
        "text_contrast": round(text_contrast, 2),

        "usable": usable,

        "issues": critical_issues,
        "warnings": warnings

    }
