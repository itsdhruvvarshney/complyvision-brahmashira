import tempfile
import unittest
from pathlib import Path

from benchmark_validation import aggregate, evaluate_row, load_ground_truth


def _report():
    return {
        "extracted_fields": {
            "mrp": {"normalized_value": {"value": 110.0}},
            "net_quantity": {"normalized_value": {"value": 500, "unit": "ML"}},
            "manufacturer": {"normalized_value": {"name": "Nature Oils Pvt Ltd"}},
        },
        "rule_results": [
            {"rule_id": "LM-R6-001", "status": "PASS"},
            {"rule_id": "LM-R6-006", "status": "PASS"},
            {"rule_id": "LM-R7-001", "status": "REVIEW"},
        ],
        "summary": {"overall_status": "REVIEW"},
        "quality": {"warnings": ["HIGH_GLARE"]},
        "evidence": {"calibration": {"detected": True}},
    }


class BenchmarkValidationTests(unittest.TestCase):
    def test_unknown_labels_are_excluded_and_values_are_normalized(self):
        record = {
            "image": "samples/1.jpg", "expected_mrp": "₹ 110.00",
            "expected_net_quantity_value": "500.0", "expected_net_quantity_unit": "ml",
            "expected_manufacturer": " nature  oils pvt ltd ",
            "expected_LM-R6-001": "UNKNOWN", "expected_LM-R6-006": "PASS",
            "quality_category": "", "notes": "",
        }
        row = evaluate_row(record, _report())
        self.assertFalse(row["rules"]["LM-R6-001"]["evaluated"])
        self.assertTrue(row["rules"]["LM-R6-006"]["correct"])
        self.assertTrue(row["extraction"]["mrp"]["normalized_match"])
        self.assertTrue(row["extraction"]["manufacturer"]["normalized_match"])
        self.assertEqual(row["quality_category"], "glare")

    def test_false_pass_and_confusion_metrics_are_explicit(self):
        record = {
            "image": "samples/1.jpg", "expected_LM-R6-001": "FAIL",
            "expected_LM-R6-006": "REVIEW", "expected_LM-R7-001": "REVIEW",
        }
        metrics = aggregate([evaluate_row(record, _report())])
        self.assertEqual(metrics["rule_metrics"]["false_passes"], 2)
        self.assertEqual(metrics["rule_metrics"]["confusion"]["FAIL->PASS"], 1)
        self.assertEqual(metrics["rule_metrics"]["confusion"]["REVIEW->PASS"], 1)
        self.assertEqual(metrics["rule_metrics"]["confusion"]["REVIEW->REVIEW"], 1)

    def test_empty_ground_truth_has_null_accuracy(self):
        metrics = aggregate([])
        self.assertIsNone(metrics["rule_metrics"]["accuracy"])
        self.assertEqual(metrics["images_processed"], 0)

    def test_invalid_status_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ground_truth.csv"
            path.write_text("image,expected_LM-R6-001\nsamples/1.jpg,MAYBE\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                load_ground_truth(path)


if __name__ == "__main__":
    unittest.main()
