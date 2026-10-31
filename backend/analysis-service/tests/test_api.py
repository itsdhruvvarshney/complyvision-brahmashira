import os
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

from api.main import SERVICE_NAME, _testing_ocr_factory, create_app
from extract_fields import extract_fields
from reporting.report import build_package_report
from services.declaration_extractor import enhance_extracted_fields
from services.analyzer import PackageAnalysisError


def _canonical_report(overall="REVIEW"):
    return {
        "report_version": "1.0",
        "disclaimer": "Prototype report",
        "image": {"filename": "uploaded_image.png"},
        "quality": {"usable": True},
        "ocr": {"success": True},
        "extracted_fields": {},
        "rule_results": [
            {
                "rule_id": "LM-R7-001", "status": "REVIEW", "applicable": True,
                "reason_codes": ["MEASUREMENT_NOT_VALIDATED"], "evidence": [],
            }
        ],
        "summary": {
            "overall_status": overall, "pass_count": 0, "fail_count": 0,
            "review_count": 1, "not_applicable_count": 0,
        },
        "evidence": {},
        "warnings": [],
    }


def _png_bytes():
    image = np.full((40, 60, 3), 180, dtype=np.uint8)
    ok, encoded = cv2.imencode(".png", image)
    assert ok
    return encoded.tobytes()


class FakeAnalyzer:
    def __init__(self, report=None, error=None, write_artifact=False):
        self.report = report or _canonical_report()
        self.error = error
        self.paths = []
        self.display_filenames = []
        self.write_artifact = write_artifact

    def analyze_package(self, image_path, *, display_filename):
        self.paths.append(Path(image_path))
        self.display_filenames.append(display_filename)
        if self.write_artifact:
            evidence = Path(image_path).parent / "evidence"
            evidence.mkdir()
            (evidence / "overlay.jpg").write_bytes(b"request-local")
        if self.error:
            raise self.error
        return self.report


class FastAPITests(unittest.TestCase):
    def test_testing_ocr_factory_uses_memory_bounded_models(self):
        with patch("services.rapidocr_adapter.RapidOCRAdapter") as adapter:
            ocr = _testing_ocr_factory()

        self.assertIs(ocr, adapter.return_value)
        adapter.assert_called_once_with()

    def test_health_does_not_touch_analyzer(self):
        analyzer = FakeAnalyzer(error=AssertionError("must not run"))
        response = TestClient(create_app(analyzer)).get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok", "service": SERVICE_NAME})
        self.assertEqual(analyzer.paths, [])

    def test_valid_upload_returns_canonical_report_without_wrapper(self):
        expected = _canonical_report()
        response = TestClient(create_app(FakeAnalyzer(expected))).post(
            "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), expected)

    def test_product_identity_reaches_the_api_response(self):
        fields = enhance_extracted_fields(extract_fields([
            {
                "text": "Himalaya Cocoa Butter Intensive Serum Body Lotion",
                "confidence": 0.99,
                "box": [40, 40, 900, 100],
            },
        ]))
        report = build_package_report({
            "image": "package.png",
            "extracted_fields": fields,
            "ocr": {"success": True},
            "image_quality": {"usable": True, "issues": [], "warnings": []},
        })

        response = TestClient(create_app(FakeAnalyzer(report))).post(
            "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
        )

        self.assertEqual(response.status_code, 200)
        returned = response.json()["extracted_fields"]
        self.assertEqual(returned["brand_name"]["normalized_value"]["value"], "Himalaya")
        self.assertEqual(
            returned["product_name"]["normalized_value"]["value"],
            "Cocoa Butter Intensive Serum Body Lotion",
        )
        self.assertEqual(returned["product"]["normalized_value"]["value"], "Body Lotion")

    def test_missing_file_is_rejected(self):
        response = TestClient(create_app(FakeAnalyzer())).post("/analyze")
        self.assertEqual(response.status_code, 422)

    def test_unsupported_extension_or_mime_is_rejected(self):
        client = TestClient(create_app(FakeAnalyzer()))
        response = client.post(
            "/analyze", files={"file": ("package.gif", b"GIF89a", "image/gif")}
        )
        self.assertEqual(response.status_code, 415)
        response = client.post(
            "/analyze", files={"file": ("package.txt", _png_bytes(), "image/png")}
        )
        self.assertEqual(response.status_code, 415)

    def test_corrupt_image_is_rejected(self):
        response = TestClient(create_app(FakeAnalyzer())).post(
            "/analyze", files={"file": ("package.jpg", b"not-a-jpeg", "image/jpeg")}
        )
        self.assertEqual(response.status_code, 400)
        self.assertNotIn("/Users/", response.text)

    def test_review_is_a_successful_api_response_and_rule_7_stays_review(self):
        response = TestClient(create_app(FakeAnalyzer(_canonical_report("REVIEW")))).post(
            "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
        )
        self.assertEqual(response.status_code, 200)
        report = response.json()
        self.assertEqual(report["summary"]["overall_status"], "REVIEW")
        rule_7 = next(rule for rule in report["rule_results"] if rule["rule_id"] == "LM-R7-001")
        self.assertEqual(rule_7["status"], "REVIEW")

    def test_temporary_file_is_removed_after_success(self):
        analyzer = FakeAnalyzer()
        response = TestClient(create_app(analyzer)).post(
            "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(analyzer.paths), 1)
        self.assertFalse(analyzer.paths[0].exists())

    def test_temporary_file_is_removed_after_processing_exception(self):
        analyzer = FakeAnalyzer(error=PackageAnalysisError("internal secret detail"))
        response = TestClient(create_app(analyzer)).post(
            "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
        )
        self.assertEqual(response.status_code, 500)
        self.assertFalse(analyzer.paths[0].exists())
        self.assertNotIn("internal secret detail", response.text)

    def test_client_path_traversal_is_not_used(self):
        analyzer = FakeAnalyzer()
        response = TestClient(create_app(analyzer)).post(
            "/analyze",
            files={"file": ("../../private/package.png", _png_bytes(), "image/png")},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(analyzer.paths[0].name, "upload.png")
        self.assertEqual(analyzer.display_filenames[0], "uploaded_image.png")
        self.assertNotIn("private", response.text)

    def test_configured_file_size_limit(self):
        with patch.dict(os.environ, {"SIH_MAX_UPLOAD_BYTES": "10"}):
            response = TestClient(create_app(FakeAnalyzer())).post(
                "/analyze", files={"file": ("package.png", _png_bytes(), "image/png")}
            )
        self.assertEqual(response.status_code, 413)

    def test_existing_sample_can_cross_http_validation_boundary(self):
        sample = Path("samples/1.jpg").read_bytes()
        analyzer = FakeAnalyzer()
        response = TestClient(create_app(analyzer)).post(
            "/analyze", files={"file": ("1.jpg", sample, "image/jpeg")}
        )
        self.assertEqual(response.status_code, 200)
        self.assertTrue(analyzer.paths)

    def test_sequential_requests_are_isolated_and_all_artifacts_are_cleaned(self):
        report = _canonical_report()
        report["evidence_images"] = [{
            "id": "safe", "type": "DECLARATION_CROP", "label": "MRP",
            "mime_type": "image/jpeg", "data_url": "data:image/jpeg;base64,YQ==",
        }]
        analyzer = FakeAnalyzer(report, write_artifact=True)
        client = TestClient(create_app(analyzer))
        first = client.post("/analyze", files={"file": ("one.png", _png_bytes(), "image/png")})
        second = client.post("/analyze", files={"file": ("two.png", _png_bytes(), "image/png")})
        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 200)
        self.assertNotEqual(analyzer.paths[0].parent, analyzer.paths[1].parent)
        self.assertTrue(all(not path.parent.exists() for path in analyzer.paths))
        self.assertNotIn("sih26034_upload_", first.text)
        self.assertEqual(first.json()["evidence_images"][0]["data_url"], "data:image/jpeg;base64,YQ==")


if __name__ == "__main__":
    unittest.main()
