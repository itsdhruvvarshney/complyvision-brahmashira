import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from cv.glyph_measurement import (
    clamp_box,
    extract_numeric_text,
    filter_components,
    measure_net_quantity_numerals,
    merge_split_components,
    pixels_to_mm,
    robust_median_height,
)


class GlyphMeasurementTests(unittest.TestCase):
    @staticmethod
    def _synthetic_measurement(
        directory,
        *,
        light_text=False,
        noisy=False,
        rendered_text="Net Vol: 250 ml",
        source_text="Net Vol: 250 ml",
    ):
        background = 0 if light_text else 255
        foreground = (255, 255, 255) if light_text else (0, 0, 0)
        image = np.full((150, 500, 3), background, dtype=np.uint8)
        origin = (20, 90)
        font = cv2.FONT_HERSHEY_SIMPLEX
        scale = 1.2
        thickness = 2
        cv2.putText(
            image, rendered_text, origin, font, scale, foreground,
            thickness, cv2.LINE_AA,
        )
        if noisy:
            generator = np.random.default_rng(26034)
            for x, y in generator.integers([0, 0], [500, 150], size=(100, 2)):
                image[y, x] = foreground
        (width, height), baseline = cv2.getTextSize(
            rendered_text, font, scale, thickness
        )
        image_path = Path(directory) / "quantity.png"
        cv2.imwrite(str(image_path), image)
        quantity = {
            "value": 250,
            "source_text": source_text,
            "source_box": [18, origin[1] - height - 4, 22 + width, origin[1] + baseline + 4],
            "confidence": 0.98,
        }
        return measure_net_quantity_numerals(str(image_path), quantity, 10.0)

    def test_source_box_clamping_supports_padding_and_polygons(self):
        self.assertEqual(clamp_box([-5, -2, 120, 80], 100, 60, padding=2), (0, 0, 100, 60))
        polygon = [[5, 7], [90, 7], [90, 50], [5, 50]]
        self.assertEqual(clamp_box(polygon, 100, 60, padding=3), (2, 4, 93, 53))

    def test_numeric_substring_removes_label_and_unit(self):
        self.assertEqual(extract_numeric_text("Net Vol: 250 ml", 250.0), "250")
        self.assertEqual(extract_numeric_text("NET WEIGHT 500 g", 500), "500")
        self.assertEqual(extract_numeric_text("250 ml", 250), "250")
        for source, expected in {
            "100 g": "100",
            "500ml": "500",
            "1 kg": "1",
            "0.5 kg": "05",
            "750 ML": "750",
            "2L": "2",
        }.items():
            with self.subTest(source=source):
                self.assertEqual(extract_numeric_text(source), expected)

    def test_decimal_point_is_removed_from_numeric_glyphs(self):
        self.assertEqual(extract_numeric_text("Net Vol: 1.5 L", 1.5), "15")
        self.assertEqual(extract_numeric_text("Net Wt: 0,5 kg", 0.5), "05")

    def test_connected_component_filter_keeps_narrow_digits(self):
        components = [
            {"width": 2, "height": 25, "area": 38, "touches_crop_boundary": False},
            {"width": 15, "height": 26, "area": 180, "touches_crop_boundary": False},
            {"width": 2, "height": 2, "area": 3, "touches_crop_boundary": False},
            {"width": 50, "height": 40, "area": 1800, "touches_crop_boundary": False},
            {"width": 12, "height": 25, "area": 160, "touches_crop_boundary": True},
        ]
        filtered = filter_components(components, 100, 40, reference_height=30)
        self.assertEqual(filtered, components[:2])

    def test_robust_median_rejects_height_outlier(self):
        self.assertEqual(robust_median_height([30, 31, 29, 90]), 30.0)

    def test_large_consistent_digits_are_not_rejected_by_fixed_pixel_tolerance(self):
        self.assertEqual(robust_median_height([112, 116, 116]), 116.0)

    def test_conservative_split_component_merge(self):
        components = [
            {"x": 0, "y": 0, "width": 12, "height": 25, "area": 180,
             "box": [10, 10, 22, 35], "touches_crop_boundary": False},
            {"x": 13, "y": 8, "width": 3, "height": 10, "area": 22,
             "box": [23, 18, 26, 28], "touches_crop_boundary": False},
            {"x": 25, "y": 0, "width": 12, "height": 25, "area": 175,
             "box": [35, 10, 47, 35], "touches_crop_boundary": False},
        ]
        merged = merge_split_components(components, reference_height=30)
        self.assertEqual(len(merged), 2)
        self.assertEqual(merged[0]["box"], [10, 10, 26, 35])
        self.assertEqual(merged[0]["merged_parts"], 2)

    def test_pixels_to_mm_conversion(self):
        self.assertAlmostEqual(pixels_to_mm(31, 11.1172), 2.7885, places=4)

    def test_missing_calibration_returns_review(self):
        quantity = {"value": 250, "source_text": "250 ml", "source_box": [0, 0, 50, 20]}
        result = measure_net_quantity_numerals("unused.jpg", quantity, None)
        self.assertEqual(result["status"], "REVIEW")
        self.assertIn("calibration", result["reason"].lower())

    def test_missing_source_box_returns_review(self):
        quantity = {"value": 250, "source_text": "250 ml"}
        result = measure_net_quantity_numerals("unused.jpg", quantity, 10.0)
        self.assertEqual(result["status"], "REVIEW")
        self.assertIn("source_box", result["reason"])

    def test_bad_segmentation_returns_review(self):
        with tempfile.TemporaryDirectory() as directory:
            image_path = Path(directory) / "blank.png"
            image = np.full((80, 240, 3), 255, dtype=np.uint8)
            self.assertTrue(cv2.imwrite(str(image_path), image))
            quantity = {
                "value": 250,
                "source_text": "Net Vol: 250 ml",
                "source_box": [10, 10, 220, 60],
                "confidence": 0.99,
            }
            result = measure_net_quantity_numerals(str(image_path), quantity, 10.0)

        self.assertEqual(result["status"], "REVIEW")
        self.assertIn("segmentation", result["reason"].lower())

    def test_dark_text_on_light_background(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self._synthetic_measurement(directory)
        self.assertEqual(result["status"], "OK")
        self.assertEqual(result["threshold_polarity"], "dark_on_light")
        self.assertEqual(result["numeric_text"], "250")
        self.assertEqual(len(result["digit_boxes"]), 3)
        self.assertAlmostEqual(result["estimated_numeral_height_mm"], 2.9, places=1)

    def test_light_text_on_dark_background(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self._synthetic_measurement(directory, light_text=True)
        self.assertEqual(result["status"], "OK")
        self.assertEqual(result["threshold_polarity"], "light_on_dark")
        self.assertEqual(len(result["digit_heights_px"]), 3)

    def test_noisy_synthetic_image(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self._synthetic_measurement(directory, noisy=True)
        self.assertEqual(result["status"], "OK")
        self.assertGreaterEqual(result["confidence"], 0.65)

    def test_digit_count_mismatch_reduces_confidence(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self._synthetic_measurement(
                directory,
                rendered_text="Net Vol: 25     ",
                source_text="Net Vol: 250 ml",
            )
        self.assertEqual(result["status"], "REVIEW")
        self.assertLess(result["confidence"], 0.65)
        self.assertEqual(result["expected_digit_count"], 3)


if __name__ == "__main__":
    unittest.main()
