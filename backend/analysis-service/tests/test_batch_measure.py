import json
import tempfile
import unittest
from pathlib import Path

import numpy as np

from batch_measure import (
    calculate_manual_error,
    generate_quality_flag,
    make_json_safe,
    process_batch,
    save_reports,
    summarize_results,
)


def successful_result(index, confidence=0.8, height_mm=2.5):
    return {
        "image": f"samples/{index}.jpg",
        "status": "OK",
        "image_quality": {"usable": True, "warnings": []},
        "aruco": {"detected": True, "pixels_per_mm": 10.0},
        "net_quantity": {"value": 100, "unit": "G"},
        "ocr_box_measurement": {"height_px": 40, "height_mm": 4.0},
        "glyph_measurement": {
            "status": "OK",
            "confidence": confidence,
            "estimated_numeral_height_px": 25.0,
            "estimated_numeral_height_mm": height_mm,
            "digit_heights_px": [25, 25, 25],
            "expected_digit_count": 3,
        },
        "consistency_checks": [],
        "measurement_quality_flag": "GOOD",
    }


class BatchMeasurementTests(unittest.TestCase):
    def test_manual_error_calculation_and_zero_guard(self):
        self.assertEqual(
            calculate_manual_error(3.0, 2.0),
            {"absolute_error_mm": 1.0, "percentage_error": 50.0},
        )
        self.assertEqual(
            calculate_manual_error(3.0, 0.0),
            {"absolute_error_mm": 3.0, "percentage_error": None},
        )
        self.assertEqual(
            calculate_manual_error(None, 2.0),
            {"absolute_error_mm": None, "percentage_error": None},
        )

    def test_summary_statistics_and_readiness(self):
        results = [
            successful_result(1, 0.70, 2.0),
            successful_result(2, 0.80, 2.5),
            successful_result(3, 0.90, 3.0),
            successful_result(4, 1.00, 3.5),
            {
                "image": "samples/5.jpg",
                "image_quality": {"usable": True},
                "aruco": {"detected": False},
                "net_quantity": None,
                "glyph_measurement": {"status": "REVIEW", "confidence": 0.0},
                "consistency_checks": [],
                "measurement_quality_flag": "REVIEW",
            },
        ]
        summary = summarize_results(results)
        self.assertEqual(summary["images_processed"], 5)
        self.assertEqual(summary["glyph_measurement_ok_count"], 4)
        self.assertEqual(summary["mean_glyph_confidence"], 0.85)
        self.assertEqual(summary["median_glyph_confidence"], 0.85)
        self.assertEqual(summary["minimum_glyph_height_mm"], 2.0)
        self.assertEqual(summary["maximum_glyph_height_mm"], 3.5)
        self.assertEqual(summary["median_glyph_height_mm"], 2.75)
        self.assertEqual(summary["readiness"], "READY_FOR_RULE7_VALIDATION")

    def test_quality_flag_generation(self):
        result = successful_result(1, confidence=0.9)
        self.assertEqual(generate_quality_flag(result), ("GOOD", []))

        result["glyph_measurement"]["confidence"] = 0.75
        flag, reasons = generate_quality_flag(result)
        self.assertEqual(flag, "CHECK")
        self.assertTrue(any("Moderate glyph confidence" in reason for reason in reasons))

        result = successful_result(1, confidence=0.9)
        result["glyph_measurement"]["value_region_method"] = (
            "substring_position_approximation"
        )
        flag, reasons = generate_quality_flag(result)
        self.assertEqual(flag, "CHECK")
        self.assertTrue(any("approximate substring" in reason for reason in reasons))

        result["glyph_measurement"] = {
            "status": "REVIEW", "confidence": 0.4, "reason": "Segmentation failed"
        }
        flag, reasons = generate_quality_flag(result)
        self.assertEqual(flag, "REVIEW")
        self.assertIn("Segmentation failed", reasons)

    def test_batch_continues_after_per_image_exception(self):
        def processor(image_path, _ocr, *, debug_path):
            if str(image_path).endswith("1.jpg"):
                raise RuntimeError("synthetic failure")
            return successful_result(2)

        results = process_batch(
            ["samples/1.jpg", "samples/2.jpg"],
            object(),
            processor=processor,
        )
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]["status"], "REVIEW")
        self.assertEqual(results[0]["failure_stage"], "unexpected_exception")
        self.assertEqual(results[1]["status"], "OK")

    def test_json_safe_serialization_and_report_output(self):
        converted = make_json_safe({
            "array": np.asarray([1, 2]),
            "scalar": np.float32(2.5),
            "path": Path("samples/1.jpg"),
            "not_a_number": float("nan"),
        })
        self.assertEqual(converted["array"], [1, 2])
        self.assertEqual(converted["scalar"], 2.5)
        self.assertEqual(converted["path"], "samples/1.jpg")
        self.assertIsNone(converted["not_a_number"])
        json.dumps(converted, allow_nan=False)

        with tempfile.TemporaryDirectory() as directory:
            json_path = Path(directory) / "validation.json"
            csv_path = Path(directory) / "validation.csv"
            result = successful_result(1)
            result.update({
                "failure_stage": None,
                "reason": None,
                "manual_height_mm": None,
                "absolute_error_mm": None,
                "percentage_error": None,
            })
            summary = summarize_results([result])
            save_reports([result], summary, json_path=json_path, csv_path=csv_path)
            self.assertTrue(json_path.is_file())
            self.assertTrue(csv_path.is_file())
            report = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual(report["results"][0]["manual_height_mm"], None)


if __name__ == "__main__":
    unittest.main()
