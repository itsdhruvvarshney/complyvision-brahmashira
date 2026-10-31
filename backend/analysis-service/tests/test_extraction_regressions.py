import json
import unittest
from pathlib import Path

from cv.ocr_filter import filter_ocr_items_near_aruco
from extract_fields import extract_fields, normalize_text


FIXTURES = Path(__file__).parent / "fixtures"


def load_ocr(name):
    with (FIXTURES / name).open(encoding="utf-8") as fixture:
        lines = json.load(fixture)["lines"]
    return [
        {"text": line, "confidence": 0.98,
         "box": [[0, i * 20], [180, i * 20], [180, (i + 1) * 20], [0, (i + 1) * 20]],
         "source_image": name.replace("_ocr.json", ".jpg")}
        for i, line in enumerate(lines)
    ]


class ExtractionRegressionTests(unittest.TestCase):
    def test_normalization_preserves_raw_evidence(self):
        result = extract_fields(load_ocr("bioworld_ocr.json"))
        quantity_line = next(line for line in result["ocr_evidence"] if line["raw_text"] == ":1N")
        self.assertEqual(quantity_line["normalized_text"], "1 N")
        self.assertEqual(normalize_text("  ;  50PCS  "), "50 PCS")

    def test_bioworld_regression(self):
        result = extract_fields(load_ocr("bioworld_ocr.json"))
        self.assertEqual(result["product"], "TSHIRT")
        self.assertEqual(result["net_quantity"]["value"], 1.0)
        self.assertEqual(result["net_quantity"]["unit"], "N")
        self.assertEqual(result["mrp"]["value"], 999.0)
        self.assertTrue(result["mrp"]["inclusive_of_all_taxes"])
        self.assertEqual(result["manufacture_date"]["normalized"], "2022-02")
        expected_company = "BIOWORLD MERCHANDISING INDIA PVT. LTD"
        self.assertEqual(result["manufacturer"]["name"], expected_company)
        self.assertEqual(result["marketer"]["name"], expected_company)
        self.assertNotIn("Month & Year", result["manufacturer"]["name"])
        self.assertNotIn("February 2022", result["manufacturer"]["name"])
        expected_address = "307-309 PARK CENTRA, SECTOR 30 GURGAON, HARYANA, INDIA 122001."
        self.assertEqual(result["manufacturer"]["address"], expected_address)
        self.assertEqual(result["marketer"]["address"], expected_address)
        self.assertEqual(result["consumer_care"]["phone"], "0124-4362552")
        self.assertEqual(result["consumer_care"]["email"], "contact@bioworldind.com")
        self.assertEqual(result["country_of_origin"], "India")

    def test_go_desi_regression(self):
        result = extract_fields(load_ocr("go_desi_ocr.json"))
        self.assertIsNone(result["product"])
        self.assertEqual(result["net_quantity"]["value"], 50.0)
        self.assertEqual(result["net_quantity"]["unit"], "N")
        self.assertEqual(result["mrp"]["value"], 250.0)
        self.assertEqual(result["manufacture_date"]["normalized"], "2020-07-30")
        self.assertEqual(result["manufacturer"]["name"], "GO DESI MANDI PVT LTD.")
        self.assertEqual(result["marketer"]["name"], "GO DESI MANDI PVT LTD.")
        for role in ("manufacturer", "marketer"):
            address = result[role]["address"].upper()
            self.assertNotIn("FSSAI", address)
            self.assertNotIn("SSAI", address)
            self.assertNotIn("112193", address)
            self.assertNotIn("MORC", address)

    def test_shampoo_real_ocr_regression(self):
        result = extract_fields(load_ocr("shampoo_ocr.json"))

        self.assertEqual(result["product"], "NOURISH SHAMPOO")
        self.assertEqual(result["net_quantity"]["value"], 250.0)
        self.assertEqual(result["net_quantity"]["unit"], "ML")
        self.assertEqual(result["net_quantity"]["source_text"], "Net Vol: 250 ml")
        self.assertEqual(result["mrp"]["value"], 349.0)
        self.assertTrue(result["mrp"]["inclusive_of_all_taxes"])
        self.assertEqual(result["mrp"]["source_text"], "MRP: ₹349.00 Incl. of all taxes")
        self.assertEqual(result["manufacture_date"]["normalized"], "2026-09")
        self.assertEqual(result["manufacturer"]["name"], "ABC Personal Care Pvt. Ltd.")
        self.assertEqual(result["consumer_care"]["phone"], "1800-123-4567")
        self.assertEqual(result["consumer_care"]["email"], "care@abcpersonalcare.in")
        self.assertEqual(result["country_of_origin"], "India")

    def test_consumer_care_accepts_spaced_phone_without_changing_email(self):
        items = [
            {"text": "Consumer Care", "confidence": 0.99, "box": [0, 0, 180, 20]},
            {"text": "+91 98765 43210", "confidence": 0.97, "box": [0, 25, 180, 45]},
            {"text": "Email: care@freshkartfoods.in", "confidence": 1.0, "box": [0, 50, 280, 70]},
        ]

        result = extract_fields(items)["consumer_care"]

        self.assertEqual(result["phone"], "+91 98765 43210")
        self.assertEqual(result["email"], "care@freshkartfoods.in")

    def test_consumer_care_keeps_previously_supported_phone_formats(self):
        for phone in ("1800-123-4567", "+91 1800-123-4567", "0124-4362552", "9876543210"):
            with self.subTest(phone=phone):
                items = [
                    {"text": "Customer Care", "confidence": 0.99, "box": [0, 0, 180, 20]},
                    {"text": phone, "confidence": 0.98, "box": [0, 25, 180, 45]},
                ]
                self.assertEqual(extract_fields(items)["consumer_care"]["phone"], phone)

    def test_same_line_quantity_formats(self):
        cases = {
            "Net Vol: 250 ml": (250.0, "ML"),
            "Net Vol: 250ml": (250.0, "ML"),
            "NET VOLUME 500 ML": (500.0, "ML"),
            "Net Wt: 100 g": (100.0, "G"),
            "Net Wt: 100g": (100.0, "G"),
            "NET WEIGHT: 1 kg": (1.0, "KG"),
            "Net Qty: 750 ml": (750.0, "ML"),
            "NET QUANTITY 2 L": (2.0, "L"),
            "Net Quantity: 50 PCS": (50.0, "N"),
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                item = {"text": text, "confidence": 0.97, "box": [1, 2, 101, 22], "source_image": "sample.jpg"}
                quantity = extract_fields([item])["net_quantity"]
                self.assertEqual((quantity["value"], quantity["unit"]), expected)
                self.assertEqual(quantity["confidence"], 0.97)
                self.assertEqual(quantity["source_text"], text)
                self.assertEqual(quantity["source_box"], item["box"])
                self.assertEqual(quantity["source_image"], "sample.jpg")

    def test_same_line_mrp_formats(self):
        cases = {
            "MRP: ₹349.00": 349.0,
            "MRP ₹349": 349.0,
            "M.R.P.: 999.00": 999.0,
            "MRP: Rs. 250": 250.0,
            "MRP 250/-": 250.0,
            "Maximum Retail Price: ₹499": 499.0,
            "MRP: ₹349.00 Incl. of all taxes": 349.0,
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                item = {"text": text, "confidence": 0.96, "box": [2, 3, 102, 23]}
                mrp = extract_fields([item])["mrp"]
                self.assertEqual(mrp["value"], expected)
                self.assertEqual(mrp["confidence"], 0.96)
                self.assertEqual(mrp["source_text"], text)
                self.assertEqual(mrp["source_box"], item["box"])

    def test_unlabeled_product_inference_requires_confidence_and_adjacency(self):
        low_confidence = [
            {"text": "NOURISH", "confidence": 0.84, "box": [0, 0, 100, 18]},
            {"text": "SHAMPOO", "confidence": 0.99, "box": [0, 20, 100, 38]},
            {"text": "Net Vol: 250 ml", "confidence": 0.99, "box": [0, 40, 140, 58]},
        ]
        non_adjacent = [
            {"text": "NOURISH", "confidence": 0.99, "box": [0, 0, 100, 18]},
            {"text": "SHAMPOO", "confidence": 0.99, "box": [0, 100, 100, 118]},
            {"text": "Net Vol: 250 ml", "confidence": 0.99, "box": [0, 120, 140, 138]},
        ]

        self.assertIsNone(extract_fields(low_confidence)["product"])
        self.assertIsNone(extract_fields(non_adjacent)["product"])

    def test_semantic_formats_beat_nearby_numbers(self):
        lines = ["MRP", "560098", "February 2022", "50 PCS", "₹250/-", "NET QTY"]
        items = [{"text": text, "confidence": 0.95, "box": [0, i * 20, 100, i * 20 + 18]} for i, text in enumerate(lines)]
        result = extract_fields(items)
        self.assertEqual(result["mrp"]["value"], 250.0)
        self.assertEqual(result["net_quantity"]["value"], 50.0)

    def test_mrp_does_not_select_a_number_embedded_in_address_text(self):
        items = [
            {"text": "MRP", "confidence": 0.99, "box": [0, 0, 80, 20]},
            {"text": "Consumer Services Cell, PO Box no. 9411,", "confidence": 0.95, "box": [0, 25, 300, 50]},
        ]
        self.assertIsNone(extract_fields(items)["mrp"])

    def test_embossed_see_below_values_are_recovered_without_unit_price_confusion(self):
        lines = [
            ("Mfd., Batch No., MRP", [100, 500, 360, 530], False),
            ("See Below", [370, 500, 470, 530], False),
            ("INGREDIENTS: Paraffinum Liquidum", [100, 580, 420, 610], False),
            ("Store in cool place", [100, 700, 320, 730], False),
            ("10/25", [190, 1100, 280, 1130], True),
            ("CG069,L", [180, 1140, 300, 1170], True),
            ("₹43.00", [180, 1180, 300, 1210], True),
            ("₹0.48/ml", [180, 1220, 320, 1250], True),
        ]
        items = [
            {
                "text": text,
                "confidence": 0.91,
                "box": box,
                **({"recovered_from_declaration_crop": True} if recovered else {}),
            }
            for text, box, recovered in lines
        ]

        result = extract_fields(items)

        self.assertEqual(result["manufacture_date"]["normalized"], "2025-10")
        self.assertEqual(result["mrp"]["value"], 43.0)
        self.assertEqual(result["mrp"]["source_text"], "₹43.00")

    def test_inline_role_and_wrapped_company_name(self):
        lines = [
            "Manufactured By: ACME GLOBAL",
            "FOODS PRIVATE LIMITED, Plot 4",
            "Industrial Area, Delhi-110001",
            "MRP: Rs. 99.00",
        ]
        items = [{"text": text, "confidence": 0.95, "box": [0, i * 20, 180, i * 20 + 18]} for i, text in enumerate(lines)]
        result = extract_fields(items)
        self.assertEqual(result["manufacturer"]["name"], "ACME GLOBAL FOODS PRIVATE LIMITED")
        self.assertEqual(result["manufacturer"]["address"], "Plot 4 Industrial Area, Delhi-110001")
        self.assertEqual(result["mrp"]["value"], 99.0)

    def test_mfd_by_is_manufacturer_header_not_date_label(self):
        lines = [
            "Mfd.by:",
            "DABUR INDIA LTD.,",
            "Lane No. 03, Phase II,",
            "SIDCO Industrial Complex,",
            "Bari Brahmana, Samba,",
            "J & K, India, 181133",
            "MRP Rs. (incl., of all taxes),",
            "Batch No., Mfd., Use before",
            "そ92",
            "(₹ 0.37/ml)",
        ]
        items = [
            {"text": text, "confidence": 0.96, "box": [0, i * 30, 300, i * 30 + 24]}
            for i, text in enumerate(lines)
        ]

        result = extract_fields(items)

        self.assertEqual(result["manufacturer"]["name"], "DABUR INDIA LTD.")
        self.assertIn("SIDCO Industrial Complex", result["manufacturer"]["address"])
        self.assertEqual(result["mrp"]["value"], 92.0)
        self.assertEqual(result["mrp"]["source_text"], "そ92")

    def test_tax_inclusive_ocr_spacing_variants(self):
        for phrase in (
            "inclusive of all taxes",
            "inclusive ofall taxes",
            "inclusiveof all taxes",
            "inclusiveofalltaxes",
            "(INCLUSIVE OF ALL TAXES)",
            "Incl. of all taxes",
            "Incl of all taxes",
        ):
            with self.subTest(phrase=phrase):
                lines = ["MRP", "999.00", phrase]
                items = [
                    {"text": text, "confidence": 0.95, "box": [0, i * 20, 100, i * 20 + 18]}
                    for i, text in enumerate(lines)
                ]
                result = extract_fields(items)
                self.assertEqual(result["mrp"]["value"], 999.0)
                self.assertTrue(result["mrp"]["inclusive_of_all_taxes"])
                raw_texts = [evidence["raw_text"] for evidence in result["ocr_evidence"]]
                self.assertIn(phrase, raw_texts)

    def test_aruco_overlap_filter_preserves_raw_items(self):
        fake_marker_text = {
            "text": "7",
            "confidence": 0.459,
            "box": [821, 527, 1271, 946],
        }
        nearby_real_text = {
            "text": "NET QUANTITY",
            "confidence": 0.98,
            "box": [1200, 980, 1400, 1020],
        }
        raw_ocr_items = [fake_marker_text, nearby_real_text]
        marker_corners = [
            [808, 598],
            [1139, 600],
            [1132, 917],
            [817, 923],
        ]

        extraction_ocr_items = filter_ocr_items_near_aruco(
            raw_ocr_items,
            marker_corners,
            overlap_threshold=0.30,
        )

        self.assertEqual(extraction_ocr_items, [nearby_real_text])
        self.assertEqual(raw_ocr_items, [fake_marker_text, nearby_real_text])


if __name__ == "__main__":
    unittest.main()
