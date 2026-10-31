import unittest

import numpy as np

from cv.ocr import predict_ocr_items
from extract_fields import extract_fields
from services.declaration_extractor import enhance_extracted_fields
from services.mrp_extractor import correct_mrp


class RotatedSunscreenRegressionTests(unittest.TestCase):
    def test_paddle_orientation_metadata_is_retained_for_pipeline_geometry(self):
        oriented = np.zeros((1600, 1200, 3), dtype=np.uint8)

        class FakeOCR:
            def predict(self, _image, **_kwargs):
                return [{
                    "doc_preprocessor_res": {"angle": 90, "output_img": oriented},
                    "rec_texts": ["JOY"],
                    "rec_scores": [0.99],
                    "rec_boxes": [[10, 20, 80, 50]],
                }]

        metadata = {}
        items = predict_ocr_items(FakeOCR(), "sideways.jpg", prediction_metadata=metadata)

        self.assertEqual(items[0]["text"], "JOY")
        self.assertEqual(metadata["orientation_angle"], 90)
        self.assertIs(metadata["oriented_image"], oriented)

    def test_orientation_corrected_joy_label_fields(self):
        rows = [
            ("￥380.00,#05/26", 0.91, [83, 72, 369, 97]),
            ("BABWF4008,304/29", 0.93, [83, 86, 399, 114]),
            ("INDIVIDUAL PACK NOT FOR SS", 0.90, [57, 117, 466, 147]),
            ("Hello", 1.0, [241, 173, 321, 205]),
            ("JOY", 1.0, [118, 189, 216, 229]),
            ("SUN 30", 0.98, [233, 186, 443, 250]),
            ("SPF", 0.99, [437, 198, 469, 219]),
            ("PA++", 0.91, [438, 214, 475, 232]),
            ("TAN PROTECT SUNSCREEN", 0.98, [106, 244, 370, 275]),
            ("SCIENCE OF", 0.99, [184, 316, 282, 339]),
            ("GOODNESS OF", 1.0, [379, 317, 504, 337]),
            ("936", 0.97, [199, 707, 236, 948]),
            ("SUNSCREEN. MADE IN INDIA BY:", 1.0, [253, 685, 484, 714]),
            ("RSH WELLNESS PRIVATE LIMITED", 0.99, [253, 705, 496, 733]),
            ("PLOT NO.-2, HPSIDC, INDUSTRIAL", 0.99, [253, 723, 495, 753]),
            ("AREA, BADDI-173 205", 0.99, [253, 742, 411, 770]),
            ("DISTRICT-SOLAN (H.P.)", 0.97, [253, 761, 422, 788]),
            ("M.L. NO. HIM/COS/25/384", 0.99, [252, 779, 435, 807]),
            ("MARKETED BY LIC. USER", 0.99, [252, 798, 430, 825]),
            ("RSH GLOBAL PRIVATE LIMITED", 0.99, [251, 815, 473, 845]),
            ("TM OWNERS JOY CREATORS LLP.", 1.0, [251, 834, 491, 865]),
            ("CUSTOMER CARE-QUERY/FEEDBACK:", 1.0, [250, 892, 506, 928]),
            ("RSH GLOBAL PRIVATE LIMITED, 119,", 1.0, [249, 913, 504, 950]),
            ("PARK STREET, KOLKATA 700 016", 1.0, [250, 934, 481, 969]),
            ("TOLL FREE:1800-1205-40000", 0.99, [254, 965, 491, 1003]),
            ("CUSTOMERCARE@RSHGLOBAL.COM", 1.0, [252, 990, 490, 1026]),
            ("NET VOL.", 1.0, [242, 1035, 322, 1067]),
            ("100ml", 1.0, [358, 1041, 421, 1076]),
            ("*MRP(INCL. OF ALL TAXES)", 0.99, [240, 1078, 459, 1121]),
            ("#MFD.,B.NO.,@USE BEFORE &", 0.98, [239, 1102, 469, 1140]),
        ]
        items = [{"text": text, "confidence": confidence, "box": box} for text, confidence, box in rows]

        fields = correct_mrp(enhance_extracted_fields(extract_fields(items)))

        self.assertEqual(fields["brand_name"]["value"], "JOY")
        self.assertEqual(fields["product_name"]["value"], "TAN PROTECT SUNSCREEN")
        self.assertEqual(fields["net_quantity"]["value"], 100.0)
        self.assertEqual(fields["net_quantity"]["unit"], "ML")
        self.assertEqual(fields["mrp"]["value"], 380.0)
        self.assertEqual(fields["manufacture_date"]["normalized"], "2026-05")
        self.assertEqual(fields["use_by_date"]["normalized"], "2029-04-30")
        self.assertEqual(fields["manufacturer"]["name"], "RSH WELLNESS PRIVATE LIMITED")
        self.assertNotIn("936", fields["manufacturer"]["name"])
        self.assertEqual(fields["marketer"]["name"], "RSH GLOBAL PRIVATE LIMITED")
        self.assertEqual(fields["marketer"]["address"], "")
        self.assertEqual(fields["consumer_care"]["phone"], "1800-1205-40000")
        self.assertEqual(fields["consumer_care"]["email"], "CUSTOMERCARE@RSHGLOBAL.COM")
        self.assertEqual(fields["country_of_origin"], "India")


if __name__ == "__main__":
    unittest.main()
