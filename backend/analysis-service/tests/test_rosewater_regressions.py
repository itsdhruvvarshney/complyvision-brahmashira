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

    def test_separate_label_lines_ignore_usage_copy_and_correct_confirmed_ocr_typo(self):
        result = extract_product_identity([
            {"text": "Dabur", "confidence": 1.0, "box": [98, 228, 134, 247]},
            {"text": "Gulabari", "confidence": 1.0, "box": [90, 241, 142, 260]},
            {"text": "PREMIUM", "confidence": 1.0, "box": [87, 253, 145, 272]},
            {"text": "Rose Wafer", "confidence": 0.86, "box": [80, 263, 149, 286]},
            {"text": "How to use Rose water:", "confidence": 0.98, "box": [77, 278, 152, 298]},
            {"text": "Use with cotton, face pack", "confidence": 0.89, "box": [76, 288, 152, 305]},
            # Contrast OCR can independently recognize the same title word.
            {"text": "Rose Water", "confidence": 0.93, "box": [79, 263, 149, 287]},
        ])

        self.assertEqual(result["brand_name"]["value"], "Dabur")
        self.assertEqual(result["product_name"]["value"], "Gulabari PREMIUM Rose Water")
        self.assertEqual(result["product"]["value"], "Rose Water")
        self.assertNotIn("How to use", result["product_name"]["value"])

    def test_short_brand_is_separated_from_four_word_product_title(self):
        result = extract_product_identity([
            {"text": "Dabur Gulabari PREMIUM Rose Water", "confidence": 0.98, "box": [98, 229, 149, 286]},
        ])

        self.assertEqual(result["brand_name"]["value"], "Dabur")
        self.assertEqual(result["product_name"]["value"], "Gulabari PREMIUM Rose Water")

    def test_quality_helpers_return_measured_values(self):
        gray = np.full((160, 220), 220, dtype=np.uint8)
        cv2.putText(gray, "ROSE WATER", (10, 90), cv2.FONT_HERSHEY_SIMPLEX, 1.1, 25, 2)
        self.assertIsInstance(estimate_skew_angle(gray), float)
        self.assertGreater(estimate_text_contrast(gray), 0)


if __name__ == "__main__":
    unittest.main()
