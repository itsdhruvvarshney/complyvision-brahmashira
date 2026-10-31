import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from services.evidence import MAX_IMAGE_BYTES, build_evidence_images, scrub_local_paths


class EvidenceImageTests(unittest.TestCase):
    def test_builds_crops_and_only_reads_overlays_inside_request_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image_path = root / "upload.jpg"
            image = np.full((240, 360, 3), 230, dtype=np.uint8)
            cv2.putText(image, "MRP 110", (50, 90), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 2)
            cv2.imwrite(str(image_path), image)
            evidence_root = root / "evidence"
            evidence_root.mkdir()
            glyph_path = evidence_root / "glyph.jpg"
            cv2.imwrite(str(glyph_path), image)
            outside = root / "outside.jpg"
            cv2.imwrite(str(outside), image)
            result = {
                "extracted_fields": {
                    "mrp": {"source_box": [[40, 50], [180, 50], [180, 100], [40, 100]]},
                },
                "glyph_measurement": {"debug_image_path": str(glyph_path)},
                "contrast_evidence": {
                    "targets": {"MRP": {"debug_image_path": str(outside)}}
                },
            }
            evidence = build_evidence_images(image_path, result, evidence_root)
        self.assertEqual({item["id"] for item in evidence}, {"declaration-mrp", "numeral-height-overlay"})
        self.assertTrue(all(item["data_url"].startswith("data:image/jpeg;base64,") for item in evidence))
        self.assertTrue(all(len(item["data_url"]) < MAX_IMAGE_BYTES * 2 for item in evidence))
        self.assertNotIn(str(root), str(evidence))

    def test_missing_or_invalid_boxes_are_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image_path = root / "upload.jpg"
            cv2.imwrite(str(image_path), np.zeros((30, 30, 3), dtype=np.uint8))
            evidence_root = root / "evidence"
            evidence_root.mkdir()
            result = {"extracted_fields": {"mrp": {"source_box": "invalid"}}}
            self.assertEqual(build_evidence_images(image_path, result, evidence_root), [])

    def test_scrub_removes_nested_debug_metadata(self):
        value = {
            "path": "kept",
            "nested": [{"debug_image_path": "/tmp/private.jpg", "value": 2}],
            "debug_overlay_path": "also-private",
            "debug_image_saved": True,
        }
        self.assertEqual(scrub_local_paths(value), {"path": "kept", "nested": [{"value": 2}]})


if __name__ == "__main__":
    unittest.main()
