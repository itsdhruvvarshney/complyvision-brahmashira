"""ArUco scale calibration: a printed marker of known size in the frame gives pixels-per-millimetre."""

from __future__ import annotations

import cv2
import numpy as np

_DICTIONARIES = (
    ("DICT_4X4_50", "DICT_4X4_50"),
    ("DICT_5X5_100", "DICT_5X5_100"),
    ("DICT_6X6_250", "DICT_6X6_250"),
    ("DICT_ARUCO_ORIGINAL", "DICT_ARUCO_ORIGINAL"),
)


def _detect(gray: np.ndarray, dictionary) -> tuple[list, np.ndarray | None]:
    aruco = cv2.aruco
    if hasattr(aruco, "ArucoDetector"):  # OpenCV >= 4.7
        params = aruco.DetectorParameters()
        params.cornerRefinementMethod = aruco.CORNER_REFINE_SUBPIX
        corners, ids, _ = aruco.ArucoDetector(dictionary, params).detectMarkers(gray)
    else:  # pragma: no cover - legacy contrib API
        params = aruco.DetectorParameters_create()
        params.cornerRefinementMethod = aruco.CORNER_REFINE_SUBPIX
        corners, ids, _ = aruco.detectMarkers(gray, dictionary, parameters=params)
    return corners, ids


def calibrate(img: np.ndarray, marker_mm: float) -> dict:
    if not hasattr(cv2, "aruco"):
        return {"detected": False, "reason": "OpenCV build has no ArUco module"}
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    for label, attr in _DICTIONARIES:
        if not hasattr(cv2.aruco, attr):
            continue
        dictionary = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, attr))
        corners, ids = _detect(gray, dictionary)
        if ids is None or len(ids) == 0:
            continue
        ids = np.asarray(ids).reshape(-1)

        # Use the largest marker (closest to the camera / least quantisation error)
        best_i = max(range(len(corners)), key=lambda i: cv2.contourArea(corners[i].reshape(4, 2)))
        quad = corners[best_i].reshape(4, 2).astype(np.float64)
        sides = [float(np.linalg.norm(quad[i] - quad[(i + 1) % 4])) for i in range(4)]
        side_px = float(np.mean(sides))
        distortion = max(sides) / max(min(sides), 1e-6)
        px_per_mm = side_px / marker_mm
        x, y, w, h = cv2.boundingRect(quad.astype(np.float32))
        result = {
            "detected": True,
            "dictionary": label,
            "marker_id": int(ids[best_i]),
            "markers_found": int(len(ids)),
            "marker_mm": marker_mm,
            "side_px": round(side_px, 2),
            "px_per_mm": round(px_per_mm, 4),
            "distortion": round(distortion, 3),
            "box": [int(x), int(y), int(w), int(h)],
        }
        if distortion > 1.12:
            result["warning"] = "Marker is viewed at an angle; physical measurements are approximate."
        return result

    return {"detected": False, "reason": "No ArUco marker found in the frame", "marker_mm": marker_mm}
