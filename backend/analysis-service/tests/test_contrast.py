import unittest

import cv2
import numpy as np

from cv.contrast import measure_local_contrast


def _text_fixture(foreground, background, *, text="199", origin=(90, 90)):
    image = np.full((180, 360, 3), background, dtype=np.uint8)
    font = cv2.FONT_HERSHEY_SIMPLEX
    scale = 1.5
    thickness = 3
    (width, height), baseline = cv2.getTextSize(text, font, scale, thickness)
    cv2.putText(image, text, origin, font, scale, foreground, thickness, cv2.LINE_AA)
    x, baseline_y = origin
    box = [[x, baseline_y - height], [x + width, baseline_y - height],
           [x + width, baseline_y + baseline], [x, baseline_y + baseline]]
    evidence = {
        "value": float(text),
        "confidence": 0.98,
        "source_text": text,
        "source_box": box,
        "tokens": [{"text": text, "box": box}],
    }
    return image, evidence


class ContrastMeasurementTests(unittest.TestCase):
    def test_dark_on_light_and_light_on_dark_are_strong(self):
        for foreground, background, target in (
            ((10, 10, 10), (240, 240, 240), "NET_QUANTITY"),
            ((245, 245, 245), (15, 15, 15), "MRP"),
        ):
            with self.subTest(target=target):
                image, evidence = _text_fixture(foreground, background)
                result = measure_local_contrast(image, evidence, target)
                self.assertEqual(result["status"], "OK", result)
                self.assertEqual(result["engineering_interpretation"], "STRONG_ENGINEERING_CONTRAST")
                self.assertGreater(result["contrast_ratio"], 3.0)
                self.assertEqual(result["localization_method"], "ocr_token_geometry")

    def test_low_gray_on_gray_is_measured_as_low(self):
        image, evidence = _text_fixture((145, 145, 145), (150, 150, 150))
        result = measure_local_contrast(image, evidence, "MRP")
        self.assertEqual(result["status"], "OK", result)
        self.assertEqual(result["engineering_interpretation"], "LOW_ENGINEERING_CONTRAST")

    def test_color_difference_is_reported_for_colored_text(self):
        image, evidence = _text_fixture((180, 20, 20), (20, 220, 240))
        result = measure_local_contrast(image, evidence, "NET_QUANTITY")
        self.assertEqual(result["status"], "OK", result)
        self.assertGreater(result["lab_color_difference"], 35.0)

    def test_quality_problem_forces_review(self):
        image, evidence = _text_fixture((0, 0, 0), (235, 235, 235))
        result = measure_local_contrast(
            image, evidence, "MRP", image_quality={"issues": ["HIGH_GLARE"]}
        )
        self.assertEqual(result["status"], "REVIEW")
        self.assertTrue(any("HIGH_GLARE" in issue for issue in result["issues"]))

    def test_heterogeneous_background_forces_review(self):
        image, evidence = _text_fixture((0, 0, 0), (120, 120, 120))
        gradient = np.linspace(20, 245, image.shape[1], dtype=np.uint8)
        image[:] = gradient[np.newaxis, :, np.newaxis]
        box = evidence["tokens"][0]["box"]
        origin = (int(box[0][0]), int(box[2][1] - 10))
        cv2.putText(image, "199", origin, cv2.FONT_HERSHEY_SIMPLEX, 1.5, (0, 0, 0), 3, cv2.LINE_AA)
        result = measure_local_contrast(image, evidence, "NET_QUANTITY")
        self.assertEqual(result["status"], "REVIEW", result)
        self.assertIn("HETEROGENEOUS_LOCAL_BACKGROUND", result["issues"])

    def test_missing_geometry_and_boundary_ring_require_review(self):
        image = np.full((100, 200, 3), 220, dtype=np.uint8)
        self.assertEqual(measure_local_contrast(image, None, "MRP")["status"], "REVIEW")
        evidence = {
            "value": 50,
            "confidence": 0.99,
            "source_text": "50",
            "source_box": [[0, 10], [35, 10], [35, 45], [0, 45]],
            "tokens": [{"text": "50", "box": [[0, 10], [35, 10], [35, 45], [0, 45]]}],
        }
        cv2.putText(image, "50", (0, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 2)
        result = measure_local_contrast(image, evidence, "NET_QUANTITY")
        self.assertEqual(result["status"], "REVIEW")
        self.assertIn("BACKGROUND_RING_CLIPPED_AT_IMAGE_BOUNDARY", result["issues"])

    def test_manually_confirmed_polygon_is_supported_as_fallback(self):
        image, evidence = _text_fixture((0, 0, 0), (230, 230, 230))
        evidence["manual_target_polygon"] = evidence["source_box"]
        evidence["tokens"] = []
        result = measure_local_contrast(image, evidence, "MRP")
        self.assertEqual(result["localization_method"], "manually_confirmed_polygon")


if __name__ == "__main__":
    unittest.main()
