import json
import tempfile
import unittest
from pathlib import Path

from reporting.report import (
    aggregate_package_status,
    build_package_report,
    render_markdown_report,
    save_package_report,
)


def _box():
    return [[10, 10], [100, 10], [100, 35], [10, 35]]


def _contrast(target):
    return {
        "status": "OK", "target": target, "ocr_text": "500",
        "target_box": [10, 10, 100, 35], "contrast_ratio": 6.0,
        "lab_color_difference": 50.0, "foreground_luminance": 0.02,
        "background_luminance": 0.8, "confidence": 0.9, "issues": [],
        "debug_image_path": f"debug/{target.lower()}.jpg",
    }


def _batch_result():
    contrast = {
        "method": "local_relative_luminance_and_lab_color_difference",
        "targets": {"NET_QUANTITY": _contrast("NET_QUANTITY"), "MRP": _contrast("MRP")},
    }
    fields = {
        "product": "Mustard Oil",
        "net_quantity": {"value": 500, "unit": "ML", "confidence": 0.96,
                         "source_text": "Net Vol: 500 ml", "source_box": _box()},
        "mrp": {"currency": "INR", "value": 110.0, "inclusive_of_all_taxes": True,
                "confidence": 0.95, "source_text": "MRP ₹110 incl. taxes", "source_box": _box()},
        "manufacture_date": {"raw": "AUG 2026", "normalized": "2026-08",
                             "type": "manufacture_month_year", "confidence": 0.94,
                             "source_text": "Mfg AUG 2026", "source_box": _box()},
        "manufacturer": {"name": "Nature Oils Pvt Ltd", "address": "Jaipur",
                         "confidence": 0.92, "source_text": "Nature Oils Pvt Ltd, Jaipur",
                         "source_box": _box()},
        "consumer_care": {"phone": "18001234567", "email": "care@example.com",
                          "confidence": 0.93, "source_text": "Care 18001234567",
                          "source_box": _box()},
        "country_of_origin": "India",
        "ocr_evidence": [
            {"raw_text": "Mustard Oil", "normalized_text": "Mustard Oil",
             "confidence": 0.91, "box": _box()},
            {"raw_text": "Net Vol: 500 ml", "normalized_text": "Net Vol: 500 ml",
             "confidence": 0.96, "box": _box()},
            {"raw_text": "g", "normalized_text": "g", "confidence": 0.99, "box": _box()},
            {"raw_text": "Made in India", "normalized_text": "Made in India",
             "confidence": 0.9, "box": _box(),
             "source_image": "/private/temp/request/upload.png"},
        ],
        "mrp_netqty_contrast": contrast,
    }
    return {
        "image": "samples/2.jpg",
        "image_quality": {"width": 1414, "height": 2000, "usable": True,
                          "blur_score": 120.0, "brightness": 130.0,
                          "glare_ratio": 0.01, "issues": [], "warnings": []},
        "ocr": {"success": True, "raw_item_count": 10, "filtered_item_count": 10},
        "extracted_fields": fields,
        "net_quantity": fields["net_quantity"], "mrp": fields["mrp"],
        "contrast_evidence": contrast,
        "aruco": {"detected": True, "pixels_per_mm": 10.0},
        "glyph_measurement": {"status": "OK", "estimated_numeral_height_mm": 2.5,
                              "measurement_confidence": 0.8,
                              "debug_image_path": "debug/glyph.jpg"},
        "validation_status": "AWAITING_MANUAL_GROUND_TRUTH",
        "failure_stage": None, "reason": None,
    }


class ReportingTests(unittest.TestCase):
    def test_report_structure(self):
        report = build_package_report(_batch_result(), processing_timestamp="2026-01-01T00:00:00Z")
        self.assertEqual(report["report_version"], "1.0")
        for key in ("image", "quality", "ocr", "extracted_fields", "rule_results",
                    "summary", "evidence", "warnings", "evidence_images"):
            self.assertIn(key, report)
        self.assertEqual(report["image"]["processing_timestamp"], "2026-01-01T00:00:00Z")
        self.assertEqual(report["ocr"]["evidence"][-1]["source_image"], "upload.png")

    def test_rule_evidence_links_to_extracted_field(self):
        report = build_package_report(_batch_result())
        mrp_rule = next(rule for rule in report["rule_results"] if rule["field_name"] == "mrp")
        self.assertEqual(mrp_rule["evidence"][0]["field"], "mrp")
        self.assertEqual(mrp_rule["evidence"][0]["raw_text"], "MRP ₹110 incl. taxes")
        self.assertEqual(mrp_rule["evidence"][0]["confidence"], 0.95)
        product = report["extracted_fields"]["product"]
        self.assertEqual(product["raw_text"], "Mustard Oil")
        self.assertEqual(product["ocr_confidence"], 0.91)
        self.assertEqual(report["extracted_fields"]["country_of_origin"]["raw_text"], "Made in India")

    def test_missing_evidence_remains_review(self):
        batch = _batch_result()
        batch["extracted_fields"]["mrp"] = None
        batch["mrp"] = None
        report = build_package_report(batch)
        mrp_rule = next(rule for rule in report["rule_results"] if rule["field_name"] == "mrp")
        self.assertEqual(mrp_rule["status"], "REVIEW")
        self.assertIn("FIELD_MISSING", mrp_rule["reason_codes"])

    def test_pass_aggregation(self):
        summary = aggregate_package_status([{"status": "PASS"}, {"status": "PASS"}])
        self.assertEqual(summary["overall_status"], "PASS")

    def test_fail_aggregation_has_priority(self):
        summary = aggregate_package_status([{"status": "REVIEW"}, {"status": "FAIL"}])
        self.assertEqual(summary["overall_status"], "FAIL")

    def test_review_aggregation(self):
        summary = aggregate_package_status([{"status": "PASS"}, {"status": "REVIEW"}])
        self.assertEqual(summary["overall_status"], "REVIEW")

    def test_not_applicable_does_not_override_pass(self):
        summary = aggregate_package_status([{"status": "PASS"}, {"status": "NOT_APPLICABLE"}])
        self.assertEqual(summary["overall_status"], "PASS")
        self.assertEqual(summary["not_applicable_count"], 1)

    def test_all_not_applicable_aggregates_to_not_applicable(self):
        summary = aggregate_package_status([{"status": "NOT_APPLICABLE"}])
        self.assertEqual(summary["overall_status"], "NOT_APPLICABLE")

    def test_rule_7_remains_review_with_measurement_evidence(self):
        report = build_package_report(_batch_result())
        rule = next(item for item in report["rule_results"] if item["rule_id"] == "LM-R7-001")
        self.assertEqual(rule["status"], "REVIEW")
        self.assertIn("MEASUREMENT_NOT_VALIDATED", rule["reason_codes"])
        self.assertTrue(any(item["evidence_type"] == "NUMERAL_HEIGHT_MEASUREMENT" for item in rule["evidence"]))

    def test_good_rule_7_measurement_has_no_review_limitation(self):
        batch = _batch_result()
        batch["glyph_measurement"]["measurement_confidence"] = 0.98
        report = build_package_report(batch)
        rule = next(item for item in report["rule_results"] if item["rule_id"] == "LM-R7-001")
        self.assertEqual(rule["status"], "PASS")
        self.assertEqual(rule["reason_codes"], ["MEASUREMENT_COMPLIANT"])
        self.assertIsNone(report["evidence"]["numeral_height"]["unresolved_reason"])

    def test_contrast_evidence_is_linked(self):
        report = build_package_report(_batch_result())
        rule = next(item for item in report["rule_results"] if item["rule_id"] == "LM-R9-002")
        self.assertEqual(rule["status"], "PASS")
        self.assertEqual(len(rule["evidence"]), 2)
        self.assertEqual(rule["reason_codes"], ["CONTRAST_CLEAR"])
        self.assertNotIn("debug_overlay_path", rule["evidence"][0])
        self.assertNotIn("debug_overlay_path", report["evidence"]["numeral_height"])

    def test_embedded_evidence_is_preserved_without_local_debug_paths(self):
        batch = _batch_result()
        batch["evidence_images"] = [{
            "id": "declaration-mrp", "type": "DECLARATION_CROP",
            "label": "MRP location", "mime_type": "image/jpeg",
            "data_url": "data:image/jpeg;base64,YQ==", "related_declaration": "mrp",
        }]
        report = build_package_report(batch)
        self.assertEqual(report["evidence_images"], batch["evidence_images"])
        self.assertNotIn("debug/", json.dumps(report))

    def test_json_serialization_and_markdown_generation(self):
        report = build_package_report(_batch_result())
        serialized = json.dumps(report, allow_nan=False)
        self.assertIn("Package Compliance", render_markdown_report(report))
        self.assertIn("not an official government compliance certificate", render_markdown_report(report))
        with tempfile.TemporaryDirectory() as directory:
            json_path = Path(directory) / "report.json"
            markdown_path = Path(directory) / "report.md"
            save_package_report(report, json_path, markdown_path)
            self.assertEqual(json.loads(json_path.read_text())["report_version"], "1.0")
            self.assertTrue(markdown_path.read_text().startswith("# Package Compliance Report"))
        self.assertTrue(serialized)


if __name__ == "__main__":
    unittest.main()
