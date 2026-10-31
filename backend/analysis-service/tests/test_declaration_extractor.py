import unittest

from services.declaration_extractor import enhance_extracted_fields


class DeclarationExtractorTests(unittest.TestCase):
    def make_fields(self, lines):
        return {
            "ocr_evidence": [
                {
                    "raw_text": text,
                    "normalized_text": text,
                    "confidence": 0.99,
                    "box": [0, index * 30, 300, index * 30 + 24],
                }
                for index, text in enumerate(lines)
            ]
        }

    def test_realistic_biscuit_declarations(self):
        fields = self.make_fields([
            "INGREDIENTS:",
            "SunBake",
            "BISCUITS",
            "MANUFACTURED BY:",
            "SunBake Foods Pvt. Ltd.",
            "Plot No. 45, Sector-2,",
            "Industrial Area,",
            "Greater Noida,",
            "Uttar Pradesh - 201308,",
            "India.",
            "Classic Tea Time Biscuit",
            "MRP ₹ (Incl. of all taxes):",
            "60.00",
            "USP ₹: 0.30 per g",
            "BATCH NO.: SB240501",
            "MFG. DATE: 01/MAY/2024",
            "USE BY: ——30/OCT/2024",
            "CONSUMER CARE:",
            "1800-123-4567",
            "or email: customercare@sunbake.com",
        ])

        result = enhance_extracted_fields(fields)

        self.assertEqual(result["product"]["value"], "Biscuit")
        self.assertEqual(result["mrp"]["value"], 60.0)
        self.assertTrue(result["mrp"]["inclusive_of_all_taxes"])
        self.assertEqual(result["unit_sale_price"]["value"], 0.30)
        self.assertEqual(result["unit_sale_price"]["unit"], "G")
        self.assertEqual(result["manufacture_date"]["normalized"], "2024-05-01")
        self.assertEqual(result["use_by_date"]["normalized"], "2024-10-30")

    def test_mrp_does_not_use_unit_sale_price(self):
        fields = self.make_fields([
            "MRP ₹ (Incl. of all taxes):",
            "60.00",
            "USP ₹: 0.30 per g",
        ])
        result = enhance_extracted_fields(fields)
        self.assertEqual(result["mrp"]["value"], 60.0)
        self.assertEqual(result["unit_sale_price"]["value"], 0.30)

    def test_date_labels_are_not_confused(self):
        fields = self.make_fields([
            "MFG. DATE: 01/MAY/2024",
            "USE BY: ——30/OCT/2024",
        ])
        result = enhance_extracted_fields(fields)
        self.assertEqual(result["manufacture_date"]["normalized"], "2024-05-01")
        self.assertEqual(result["use_by_date"]["normalized"], "2024-10-30")


if __name__ == "__main__":
    unittest.main()
