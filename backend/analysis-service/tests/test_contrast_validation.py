import json
import tempfile
import unittest
from pathlib import Path

from validate_contrast_dataset import (
    AnnotationValidationError,
    calculate_evaluation_metrics,
    load_annotations,
    run_annotations,
    save_validation_reports,
    threshold_distribution_analysis,
    threshold_recommendation,
)


def _annotation(**overrides):
    value = {
        "id": "package_01_mrp",
        "image_filename": "package_01.jpg",
        "declaration_type": "MRP",
        "target_type": "MRP",
        "expected_target_text": "349.00",
        "human_label": "CLEAR_CONTRAST",
        "annotation_notes": "human observation",
        "glare_present": False,
        "gradient_background": False,
        "textured_background": False,
        "unusual_text_color": False,
        "coverage_categories": ["black_text_on_light"],
        "manual_target_polygon": None,
    }
    value.update(overrides)
    return value


def _result(label, status, ratio=None, lab=None, confidence=None):
    return {
        "human_label": label,
        "system_status": status,
        "correct": status == {
            "CLEAR_CONTRAST": "PASS",
            "LOW_CONTRAST": "FAIL",
            "UNCERTAIN": "REVIEW",
        }[label],
        "contrast_ratio": ratio,
        "lab_difference": lab,
        "confidence": confidence,
    }


class ContrastValidationTests(unittest.TestCase):
    def _write_annotations(self, directory, annotations):
        path = Path(directory) / "annotations.json"
        path.write_text(json.dumps({"annotations": annotations}), encoding="utf-8")
        return path

    def test_annotation_loading_and_normalization(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self._write_annotations(directory, [_annotation(target_type="mrp")])
            records = load_annotations(path)
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["target_type"], "MRP")
            self.assertEqual(records[0]["human_label"], "CLEAR_CONTRAST")

    def test_annotation_label_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self._write_annotations(
                directory, [_annotation(human_label="LEGALLY_COMPLIANT")]
            )
            with self.assertRaises(AnnotationValidationError):
                load_annotations(path)

    def test_missing_image_becomes_review_without_calling_pipeline(self):
        with tempfile.TemporaryDirectory() as directory:
            def provider(_path):
                self.fail("provider must not run for a missing image")

            records = run_annotations([_annotation()], directory, provider, debug_directory=None)
            self.assertEqual(records[0]["system_status"], "REVIEW")
            self.assertIn("MISSING_IMAGE", records[0]["issues"])
            self.assertEqual(records[0]["probable_failure_cause"], "INSUFFICIENT_EVIDENCE")

    def test_missing_ocr_target_becomes_review(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "package_01.jpg").write_bytes(b"placeholder")
            records = run_annotations(
                [_annotation()], directory,
                lambda _path: {"fields": {}, "image_quality": {}},
                debug_directory=None,
            )
            self.assertEqual(records[0]["system_status"], "REVIEW")
            self.assertIn("MISSING_OCR_TARGET", records[0]["issues"])
            self.assertEqual(records[0]["probable_failure_cause"], "LOCALIZATION_ERROR")

    def test_evaluation_metrics_count_confusion_and_false_pass(self):
        records = [
            _result("CLEAR_CONTRAST", "PASS"),
            _result("CLEAR_CONTRAST", "FAIL"),
            _result("LOW_CONTRAST", "PASS"),
            _result("LOW_CONTRAST", "FAIL"),
            _result("UNCERTAIN", "PASS"),
            _result("UNCERTAIN", "REVIEW"),
        ]
        metrics = calculate_evaluation_metrics(records)
        self.assertEqual(metrics["total_samples"], 6)
        self.assertEqual(metrics["correct_classifications"], 3)
        self.assertEqual(metrics["false_pass"], 1)
        self.assertEqual(metrics["false_fail"], 1)
        self.assertEqual(metrics["human_uncertain_but_system_decisive"], 1)
        self.assertEqual(metrics["pass_precision"], 0.3333)
        self.assertEqual(metrics["fail_precision"], 0.5)

    def test_coverage_categories_are_counted(self):
        records = [
            {**_result("CLEAR_CONTRAST", "PASS"), "coverage_categories": ["glossy", "angled"]},
            {**_result("LOW_CONTRAST", "FAIL"), "coverage_categories": ["glossy"]},
        ]
        metrics = calculate_evaluation_metrics(records)
        self.assertEqual(metrics["samples_by_coverage_category"], {"angled": 1, "glossy": 2})

    def test_threshold_distribution_analysis(self):
        records = [
            _result("CLEAR_CONTRAST", "PASS", 4.0, 40.0, 0.9),
            _result("CLEAR_CONTRAST", "PASS", 6.0, 60.0, 0.8),
            _result("LOW_CONTRAST", "FAIL", 1.1, 6.0, 0.75),
            _result("LOW_CONTRAST", "FAIL", 1.3, 8.0, 0.85),
            _result("UNCERTAIN", "REVIEW", None, None, 0.4),
        ]
        analysis = threshold_distribution_analysis(records)
        ratio = analysis["distributions"]["CLEAR_CONTRAST"]["contrast_ratio"]
        self.assertEqual(ratio["min"], 4.0)
        self.assertEqual(ratio["max"], 6.0)
        self.assertEqual(ratio["median"], 5.0)
        self.assertEqual(ratio["mean"], 5.0)
        self.assertFalse(analysis["clear_low_range_overlap"]["contrast_ratio"])

    def test_insufficient_dataset_prevents_threshold_tuning(self):
        records = [
            _result("CLEAR_CONTRAST", "PASS", 5.0, 50.0, 0.9),
            _result("LOW_CONTRAST", "FAIL", 1.1, 7.0, 0.9),
            _result("UNCERTAIN", "REVIEW", 2.0, 20.0, 0.5),
        ]
        metrics = calculate_evaluation_metrics(records)
        analysis = threshold_distribution_analysis(records)
        self.assertEqual(
            threshold_recommendation(metrics, analysis),
            "INSUFFICIENT_VALIDATION_DATA",
        )

    def test_json_csv_threshold_and_failure_reports_are_generated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record = {
                **_result("LOW_CONTRAST", "PASS", 3.4, 38.0, 0.89),
                "annotation_id": "package_07_mrp", "image": "package_07.jpg",
                "target_type": "MRP", "target_text": "349.00",
                "expected_system_status": "FAIL", "ocr_confidence": 0.95,
                "localization_confidence": 0.9, "background_uniformity": 0.8,
                "global_glare_ratio": 0.0, "local_glare_ratio": 0.0,
                "issues": [], "localization_method": "ocr_token_geometry",
                "probable_failure_cause": "BACKGROUND_SAMPLING",
                "annotation_notes": "clear human low-contrast label",
            }
            records = [record]
            metrics = calculate_evaluation_metrics(records)
            analysis = threshold_distribution_analysis(records)
            paths = [root / name for name in ("report.json", "report.csv", "threshold.csv", "failures.txt")]
            save_validation_reports(
                records, metrics, analysis, "INSUFFICIENT_VALIDATION_DATA",
                json_path=paths[0], csv_path=paths[1], threshold_path=paths[2],
                failure_path=paths[3],
            )
            self.assertTrue(all(path.is_file() for path in paths))
            report = json.loads(paths[0].read_text(encoding="utf-8"))
            self.assertEqual(report["lm_r7_001_status"], "REVIEW")
            self.assertEqual(report["threshold_recommendation"], "INSUFFICIENT_VALIDATION_DATA")
            self.assertIn("package_07.jpg", paths[1].read_text(encoding="utf-8"))
            self.assertIn("BACKGROUND_SAMPLING", paths[3].read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
