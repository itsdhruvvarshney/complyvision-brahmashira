import unittest
import cv2
import numpy as np

from product_name_extractor import extract_product_identity
from cv.quality import estimate_skew_angle, estimate_text_contrast


class RoseWaterProductNameTests(unittest.TestCase):
    def test_prefers_pack_title_and_rejects_ingredient_line(self):
        items = [
            {"raw_text": "DABUR", "normalized_text": "DABUR", "confidence": 0.98, "box": [300, 90, 620, 145]},
            {"raw_text": "Gulabari Premium", "normalized_text": "Gulabari Premium", "confidence": 0.97, "box": [280, 155, 640, 205]},
            {"raw_text": "Rose Water", "normalized_text": "Rose Water", "confidence": 0.97, "box": [320, 210, 600, 260]},
            {"raw_text": "Aqua, Fragrance (Rooh Gulab), Rose Oil", "normalized_text": "Aqua, Fragrance (Rooh Gulab), Rose Oil", "confidence": 0.99, "box": [250, 650, 720, 695]},
            {"raw_text": "Phenoxyethanol, Triethylene Glycol, Sodium Benzoate", "normalized_text": "Phenoxyethanol, Triethylene Glycol, Sodium Benzoate", "confidence": 0.99, "box": [250, 700, 720, 745]},
        ]
        result = extract_product_identity(items)
        self.assertEqual(result["brand_name"]["value"], "DABUR")
        self.assertIn("Gulabari Premium Rose Water", result["product_name"]["value"])
        self.assertEqual(result["product"]["value"], "Rose Water")
        self.assertNotIn("Aqua, Fragrance", result["product_name"]["value"])

    def test_title_survives_when_ocr_merges_instructions_into_same_region(self):
        result = extract_product_identity([
            {"raw_text": "Dabur Gulabari PREMIUM Rose Water How to use Rose water: Use with Cotton, face pack or glycerine for fresh skin.", "confidence": 0.98, "box": [180, 90, 820, 235]},
            {"raw_text": "Aqua, Fragrance (Rooh Gulab), Rose Oil", "confidence": 0.99, "box": [200, 650, 800, 700]},
        ])
        full_title = f"{result['brand_name']['value']} {result['product_name']['value']}"
        self.assertIn("Dabur Gulabari PREMIUM Rose Water", full_title)
        self.assertEqual(result["product"]["value"], "Rose Water")

    def test_quality_helpers_return_measured_values(self):
        gray = np.full((160, 220), 220, dtype=np.uint8)
        cv2.putText(gray, "ROSE WATER", (10, 90), cv2.FONT_HERSHEY_SIMPLEX, 1.1, 25, 2)
        self.assertIsInstance(estimate_skew_angle(gray), float)
        self.assertGreater(estimate_text_contrast(gray), 0)


if __name__ == "__main__":
    unittest.main()
