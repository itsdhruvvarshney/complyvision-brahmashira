import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from batch_measure import build_before_after_comparison
from cv.aruco import detect_aruco_scale
from cv.glyph_measurement import measure_net_quantity_numerals
from cv.measurement_confidence import aggregate_measurement_confidence, image_quality_factor
from cv.ocr import predict_ocr_items
from extract_fields import extract_fields


class _FakeOCR:
    def predict(self, _image, return_word_box=False):
        result = {
            "rec_texts": ["Net Vol: 500 ml"],
            "rec_scores": np.asarray([0.98]),
            "rec_boxes": np.asarray([[10, 20, 210, 60]]),
        }
        if return_word_box:
            result.update({
                "text_word": [["Net", " ", "Vol", ": ", "500", " ", "ml"]],
                "text_word_boxes": [[
                    [10, 20, 40, 60], [40, 20, 45, 60], [45, 20, 75, 60],
                    [75, 20, 85, 60], [90, 20, 140, 60], [140, 20, 145, 60],
                    [150, 20, 175, 60],
                ]],
            })
        return [result]


class MeasurementHardeningTests(unittest.TestCase):
    def test_split_quantity_below_and_above_label(self):
        for items, layout in (
            ([
                {"text": "Net Vol:", "confidence": 0.99, "box": [10, 10, 100, 35]},
                {"text": "1 L", "confidence": 0.98, "box": [15, 42, 70, 80]},
            ], "below_label"),
            ([
                {"text": "500 g", "confidence": 0.98, "box": [15, 10, 85, 40]},
                {"text": "Net Wt:", "confidence": 0.99, "box": [10, 46, 100, 72]},
            ], "above_label"),
        ):
            with self.subTest(layout=layout):
                quantity = extract_fields(items)["net_quantity"]
                self.assertEqual(quantity["source_layout"], layout)
                self.assertIn(quantity["unit"], {"L", "G"})

    def test_split_quantity_rejects_semantic_numbers(self):
        fields = extract_fields([
            {"text": "Net Wt:", "confidence": 0.99, "box": [10, 10, 100, 35]},
            {"text": "MRP Rs 500", "confidence": 0.99, "box": [10, 40, 140, 70]},
            {"text": "FSSAI 10012345678901", "confidence": 0.99, "box": [10, 75, 220, 105]},
        ])
        self.assertIsNone(fields["net_quantity"])

    def test_word_geometry_is_preserved_for_localization(self):
        items = predict_ocr_items(_FakeOCR(), "unused")
        quantity = extract_fields(items)["net_quantity"]
        token = next(token for token in quantity["tokens"] if token["text"] == "500")
        self.assertEqual(token["box"], [90, 20, 140, 60])

    def test_token_geometry_rejects_adjacent_text_and_retries_segmentation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "line.png"
            image = np.full((130, 520, 3), 255, dtype=np.uint8)
            cv2.putText(image, "ABC", (10, 45), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 2)
            cv2.putText(image, "500 ml", (220, 92), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 2)
            cv2.imwrite(str(path), image)
            quantity = {
                "value": 500,
                "source_text": "Net Vol: 500 ml",
                "source_box": [0, 15, 480, 105],
                "confidence": 0.98,
                "tokens": [{"text": "500", "box": [218, 55, 290, 100]}],
            }
            result = measure_net_quantity_numerals(str(path), quantity, 10.0)
        self.assertEqual(result["status"], "OK")
        self.assertEqual(result["localization_method"], "ocr_token_geometry")
        self.assertEqual(result["expected_digit_count"], 3)
        self.assertEqual(result["segmentation_attempt_count"], 6)
        self.assertTrue(all(box[0] > 200 for box in result["digit_boxes"]))

    def test_quality_and_calibration_are_bounded_and_penalize_glare(self):
        glyph = {
            "status": "OK", "confidence": 0.95, "segmentation_confidence": 0.95,
            "localization_confidence": 1.0,
            "confidence_factors": {"ocr": 0.98, "digit_count": 1.0, "height_agreement": 1.0},
        }
        clean = aggregate_measurement_confidence(
            glyph, {"usable": True, "warnings": []},
            {"detected": True, "calibration_confidence": 0.9},
        )
        glare = aggregate_measurement_confidence(
            glyph, {"usable": True, "warnings": ["HIGH_GLARE"]},
            {"detected": True, "calibration_confidence": 0.9},
        )
        self.assertGreater(clean["measurement_confidence"], glare["measurement_confidence"])
        self.assertEqual(image_quality_factor({"usable": True, "warnings": ["HIGH_GLARE"]}), 0.48)
        self.assertTrue(0 <= clean["calibration_confidence"] <= 1)

    def test_aruco_failure_has_actionable_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "blank.png"
            cv2.imwrite(str(path), np.full((200, 200, 3), 255, dtype=np.uint8))
            result = detect_aruco_scale(str(path))
        self.assertFalse(result["detected"])
        self.assertIn("failure_reason", result)
        self.assertIn("suggested_action", result)
        self.assertEqual(result["calibration_confidence"], 0.0)

    def test_before_after_serialization_retains_manual_ground_truth(self):
        before = [{
            "image": "samples/1.jpg", "glyph_measurement": {"status": "REVIEW"},
            "manual_height_mm": 3.2,
        }]
        after = [{
            "image": "samples/1.jpg", "glyph_measurement": {"status": "OK", "confidence": 0.8},
            "manual_height_mm": 3.2, "absolute_error_mm": 0.1, "percentage_error": 3.125,
        }]
        row = build_before_after_comparison(before, after)[0]
        self.assertEqual(row["before_glyph_status"], "REVIEW")
        self.assertEqual(row["after_glyph_status"], "OK")
        self.assertEqual(row["manual_height_mm"], 3.2)


if __name__ == "__main__":
    unittest.main()
