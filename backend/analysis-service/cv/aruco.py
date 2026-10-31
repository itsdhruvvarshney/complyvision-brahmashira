import cv2
import numpy as np


def _failure_diagnostics(gray, rejected):
    contrast = float(gray.std())
    blur_score = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    rejected_count = len(rejected or [])
    warnings = []
    if contrast < 25:
        warnings.append("LOW_MARKER_CONTRAST_POSSIBLE")
    if blur_score < 50:
        warnings.append("MARKER_BLUR_POSSIBLE")
    if rejected_count:
        reason = "Quadrilateral candidates were found, but none decoded as the configured marker"
    else:
        reason = "No decodable marker candidate was found"
    return {
        "diagnostic": reason,
        "failure_reason": reason,
        "rejected_candidate_count": rejected_count,
        "image_contrast": round(contrast, 2),
        "image_blur_score": round(blur_score, 2),
        "diagnostic_warnings": warnings,
        "suggested_action": (
            "Retake with the complete marker in frame, front-facing, sharp, and evenly lit"
        ),
        "calibration_confidence": 0.0,
    }


def detect_aruco_scale(image_path, marker_size_mm=50.0):
    image = cv2.imread(image_path)

    if image is None:
        raise ValueError(f"Could not read image: {image_path}")

    gray = cv2.cvtColor(
        image,
        cv2.COLOR_BGR2GRAY
    )
    image_height, image_width = gray.shape

    dictionary = cv2.aruco.getPredefinedDictionary(
        cv2.aruco.DICT_4X4_50
    )

    detector_parameters = cv2.aruco.DetectorParameters()

    detector = cv2.aruco.ArucoDetector(
        dictionary,
        detector_parameters
    )

    corners, ids, rejected = detector.detectMarkers(gray)

    if ids is None or len(corners) == 0:
        return {
            "detected": False,
            "pixels_per_mm": None,
            "marker_id": None,
            "coordinate_metadata": {
                "image_width": int(image_width),
                "image_height": int(image_height),
                "coordinate_system": "original_image_pixels",
            },
            **_failure_diagnostics(gray, rejected),
        }

    marker = corners[0][0]

    top_left = marker[0]
    top_right = marker[1]
    bottom_right = marker[2]
    bottom_left = marker[3]

    top_width = np.linalg.norm(
        top_right - top_left
    )

    bottom_width = np.linalg.norm(
        bottom_right - bottom_left
    )

    left_height = np.linalg.norm(
        bottom_left - top_left
    )

    right_height = np.linalg.norm(
        bottom_right - top_right
    )

    average_size_px = np.mean([
        top_width,
        bottom_width,
        left_height,
        right_height
    ])

    pixels_per_mm = (
        average_size_px / marker_size_mm
    )

    side_lengths = [top_width, bottom_width, left_height, right_height]
    side_variation = float(np.std(side_lengths) / max(1.0, np.mean(side_lengths)))
    height, width = gray.shape
    border_margin = min(
        float(marker[:, 0].min()), float(marker[:, 1].min()),
        float(width - marker[:, 0].max()), float(height - marker[:, 1].max()),
    )
    border_score = max(0.0, min(1.0, border_margin / max(10.0, average_size_px * 0.15)))
    geometry_score = max(0.0, min(1.0, 1.0 - side_variation / 0.30))
    area_ratio = float(cv2.contourArea(marker.astype(np.float32)) / max(1, width * height))
    size_score = max(0.0, min(1.0, area_ratio / 0.01))
    confidence = geometry_score * 0.60 + border_score * 0.25 + size_score * 0.15
    warnings = []
    if side_variation > 0.20:
        warnings.append("MARKER_PERSPECTIVE_DISTORTION")
    if border_score < 0.8:
        warnings.append("MARKER_NEAR_IMAGE_BOUNDARY")
    if size_score < 0.7:
        warnings.append("MARKER_SMALL_IN_FRAME")

    return {
        "detected": True,
        "marker_id": int(ids[0][0]),
        "marker_size_mm": marker_size_mm,
        "average_size_px": round(
            float(average_size_px),
            2
        ),
        "pixels_per_mm": round(
            float(pixels_per_mm),
            4
        ),
        "corners": marker.tolist(),
        "side_lengths_px": [round(float(value), 2) for value in side_lengths],
        "relative_side_variation": round(side_variation, 4),
        "marker_area_ratio": round(area_ratio, 6),
        "border_margin_px": round(border_margin, 2),
        "calibration_confidence": round(float(confidence), 3),
        "diagnostic_warnings": warnings,
        "coordinate_metadata": {
            "image_width": int(width),
            "image_height": int(height),
            "coordinate_system": "original_image_pixels",
            "marker_corners_original_image_px": marker.tolist(),
            "marker_side_lengths_px": [round(float(value), 2) for value in side_lengths],
            "pixels_per_mm": round(float(pixels_per_mm), 4),
        },
    }
