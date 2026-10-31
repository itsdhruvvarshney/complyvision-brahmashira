import unittest

from product_name_extractor import extract_product_identity


def line(text, confidence, box):
    return {"text": text, "confidence": confidence, "box": box}


class ProductNameExtractorTests(unittest.TestCase):
    def test_explicit_product_repeating_prominent_brand_is_separated(self):
        result = extract_product_identity([
            line("SUNLITE", 0.97, [300, 40, 720, 160]),
            line("REFINED OIL", 0.96, [330, 175, 690, 230]),
            line("Product: SUNLITE REFINED OIL", 0.99, [180, 300, 820, 340]),
        ])

        self.assertEqual(result["brand_name"]["value"], "SUNLITE")
        self.assertEqual(result["product_name"]["value"], "REFINED OIL")

    def test_brand_bigger_than_multiline_product_name(self):
        result = extract_product_identity([
            line("MAGGI", 0.96, [300, 40, 720, 160]),
            line("2-Minute Noodles", 0.94, [270, 185, 750, 235]),
            line("Masala", 0.93, [390, 242, 630, 292]),
            line("NET WT 70 g", 0.99, [420, 700, 620, 730]),
        ])

        self.assertEqual(result["brand_name"]["value"], "MAGGI")
        self.assertEqual(result["product_name"]["value"], "2-Minute Noodles Masala")
        self.assertEqual(len(result["product_name"]["source_box"]), 2)

    def test_multiline_product_name_is_merged(self):
        result = extract_product_identity([
            line("CLASSIC", 0.97, [200, 100, 520, 148]),
            line("TEA TIME BISCUIT", 0.95, [160, 154, 560, 202]),
            line("Ingredients: wheat flour", 0.99, [100, 500, 700, 530]),
        ])

        self.assertEqual(result["product_name"]["value"], "CLASSIC TEA TIME BISCUIT")

    def test_inline_brand_and_title_are_separated_without_absorbing_description(self):
        # Deliberately out of OCR order: visual reading order and geometry must
        # determine that the second line completes the title.
        result = extract_product_identity([
            line("Lotion is enriched with cocoa butter", 0.96, [120, 177, 720, 217]),
            line("Himalaya Cocoa Butter Intensive Serum Body", 0.98, [90, 125, 760, 169]),
            line("MRP ₹299", 0.99, [600, 175, 790, 205]),
        ])

        self.assertEqual(result["brand_name"]["value"], "Himalaya")
        self.assertEqual(result["product_name"]["value"], "Cocoa Butter Intensive Serum Body Lotion")
        self.assertEqual(result["brand_name"]["source_box"], [90, 125, 760, 169])
        self.assertEqual(len(result["product_name"]["source_box"]), 2)
        self.assertEqual(result["product_name"]["confidence"], 0.96)

    def test_single_line_brand_title_and_commodity_are_derived(self):
        result = extract_product_identity([
            line("Himalaya Cocoa Butter Intensive Serum Body Lotion", 0.99, [50, 60, 900, 120]),
        ])

        self.assertEqual(result["brand_name"]["value"], "Himalaya")
        self.assertEqual(result["product_name"]["value"], "Cocoa Butter Intensive Serum Body Lotion")
        self.assertEqual(result["product"]["value"], "Body Lotion")

    def test_debug_trace_includes_scores_selection_and_rejections(self):
        with self.assertLogs("product_name_extractor", level="DEBUG"):
            result = extract_product_identity([
                line("Himalaya Cocoa Butter Intensive Serum Body Lotion", 0.99, [50, 60, 900, 120]),
                line("MRP ₹299", 0.99, [50, 180, 250, 210]),
            ])

        trace = result["debug_trace"]
        self.assertEqual(trace["selected_brand"], "Himalaya")
        self.assertEqual(trace["selected_product"], "Body Lotion")
        self.assertTrue(trace["candidates"])
        self.assertEqual(trace["rejected"][0]["rejection_reason"], "metadata_or_invalid_title")

    def test_high_confidence_regulatory_text_is_rejected(self):
        result = extract_product_identity([
            line("NUTRITIONAL INFORMATION", 1.0, [80, 40, 760, 120]),
            line("FreshCo", 0.94, [250, 150, 600, 235]),
            line("Tomato Ketchup", 0.90, [230, 255, 620, 305]),
        ])

        self.assertEqual(result["brand_name"]["value"], "FreshCo")
        self.assertEqual(result["product_name"]["value"], "Tomato Ketchup")

    def test_price_and_quantity_near_name_are_not_merged(self):
        result = extract_product_identity([
            line("FreshCo", 0.96, [250, 80, 600, 165]),
            line("Tomato Ketchup", 0.94, [220, 185, 640, 235]),
            line("MRP ₹120", 0.99, [650, 188, 820, 222]),
            line("Net Wt 500 g", 0.99, [230, 245, 430, 275]),
        ])

        self.assertEqual(result["product_name"]["value"], "Tomato Ketchup")
        self.assertNotIn("120", result["product_name"]["source_text"])
        self.assertNotIn("500", result["product_name"]["source_text"])

    def test_statutory_and_company_lines_are_not_selected_as_the_title(self):
        result = extract_product_identity([
            line("ClearSpring Refreshing Shampoo", 0.97, [120, 80, 700, 135]),
            line("MRP ₹249 Net Quantity 300 ml", 0.99, [120, 170, 620, 205]),
            line("Manufactured by ClearSpring Consumer Products Pvt Ltd", 0.99, [120, 480, 900, 515]),
            line("Plot 7, Industrial Area, Bengaluru 560001", 0.99, [120, 525, 800, 560]),
        ])

        self.assertEqual(result["product_name"]["value"], "ClearSpring Refreshing Shampoo")
        self.assertEqual(result["product"]["value"], "Shampoo")

    def test_unclear_product_name_returns_review_and_null(self):
        result = extract_product_identity([
            line("Tomato Ketchup", 0.94, [100, 100, 400, 145]),
            line("Chilli Sauce", 0.94, [500, 100, 800, 145]),
            line("Ingredients", 0.99, [100, 400, 350, 430]),
        ])

        self.assertEqual(result["status"], "REVIEW")
        self.assertIsNone(result["product_name"])

    def test_brand_is_not_guessed_from_a_short_ambiguous_heading(self):
        result = extract_product_identity([
            line("Chocolate Chip Cookies", 0.97, [120, 80, 650, 135]),
            line("Net Wt 200 g", 0.99, [150, 600, 380, 630]),
        ])

        self.assertIsNone(result["brand_name"])
        self.assertEqual(result["product_name"]["value"], "Chocolate Chip Cookies")

    def test_side_by_side_front_label_keeps_brand_and_title_separate(self):
        result = extract_product_identity([
            line("ALPINE", 0.98, [70, 70, 260, 150]),
            line("Herbal Green Tea", 0.96, [300, 92, 760, 138]),
            line("Marketed by Alpine Foods Pvt Ltd", 0.99, [80, 520, 780, 550]),
            line("Net Quantity 100 g", 0.99, [80, 570, 360, 600]),
        ])

        self.assertEqual(result["brand_name"]["value"], "ALPINE")
        self.assertEqual(result["product_name"]["value"], "Herbal Green Tea")
        self.assertEqual(result["product_name"]["source_box"], [300, 92, 760, 138])


if __name__ == "__main__":
    unittest.main()
