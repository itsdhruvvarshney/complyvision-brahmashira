import unittest

from batch_measure import apply_ground_truth_validation
from cv.validation import (
    calculate_known_size_sanity,
    calculate_manual_error,
    classify_probable_failure_source,
    coordinate_scale_metadata,
)


class PhysicalValidationTests(unittest.TestCase):
    def test_manual_error_and_percentage(self):
        self.assertEqual(
            calculate_manual_error(18.0, 15.0),
            {"absolute_error_mm": 3.0, "percentage_error": 20.0},
        )

    def test_manual_error_handles_missing_and_zero(self):
        self.assertEqual(
            calculate_manual_error(18.0, None),
            {"absolute_error_mm": None, "percentage_error": None},
        )
        self.assertEqual(
            calculate_manual_error(18.0, 0.0),
            {"absolute_error_mm": 18.0, "percentage_error": None},
        )

    def test_known_size_sanity_calculation(self):
        result = calculate_known_size_sanity(20.0, 128.0, 6.4)
        self.assertTrue(result["available"])
        self.assertEqual(result["estimated_mm"], 20.0)
        self.assertEqual(result["known_size_absolute_error_mm"], 0.0)
        self.assertEqual(result["known_size_percentage_error"], 0.0)
        self.assertEqual(result["status"], "WITHIN_5_PERCENT")

    def test_known_size_requires_aruco_scale(self):
        result = calculate_known_size_sanity(20.0, 128.0, None)
        self.assertFalse(result["available"])
        self.assertEqual(result["status"], "CALIBRATION_UNAVAILABLE")

    def test_coordinate_scaling_conversion(self):
        metadata = coordinate_scale_metadata(
            original_width=2000,
            original_height=1000,
            measurement_width=1000,
            measurement_height=500,
            glyph_height_measurement_px=50,
            pixels_per_mm=10,
        )
        self.assertEqual(metadata["x_scale"], 2.0)
        self.assertEqual(metadata["y_scale"], 2.0)
        self.assertEqual(metadata["glyph_height_original_image_px"], 100.0)
        self.assertEqual(metadata["final_height_mm"], 10.0)

    def test_missing_aruco_keeps_cv_error_unavailable(self):
        result = {
            "image": "samples/5.jpg",
            "aruco": {"detected": False, "pixels_per_mm": None},
            "glyph_measurement": {"status": "REVIEW"},
            "image_quality": {"width": 100, "height": 100},
            "measurement_quality_reasons": [],
        }
        validated = apply_ground_truth_validation(
            result, {"manual_height_mm": 12.0, "known_actual_mm": 20.0, "measured_pixel_length": 100.0}
        )
        self.assertIsNone(validated["absolute_error_mm"])
        self.assertEqual(validated["validation_status"], "CV_MEASUREMENT_UNAVAILABLE")
        self.assertEqual(validated["probable_failure_source"], "CALIBRATION")

    def test_diagnostic_classification(self):
        base = {
            "aruco": {"detected": True, "diagnostic_warnings": []},
            "glyph_measurement": {
                "status": "OK",
                "localization_method": "ocr_token_geometry",
                "coordinate_metadata": {
                    "coordinate_consistent": True, "x_scale": 1.0, "y_scale": 1.0,
                },
            },
            "known_size_sanity": {"status": "WITHIN_5_PERCENT", "known_size_percentage_error": 2.0},
            "percentage_error": 25.0,
            "marker_target_distance_ratio": 0.40,
        }
        self.assertEqual(
            classify_probable_failure_source(base)["probable_failure_source"],
            "PERSPECTIVE",
        )

        coordinate = {**base, "glyph_measurement": {
            **base["glyph_measurement"],
            "coordinate_metadata": {"coordinate_consistent": False, "x_scale": None, "y_scale": None},
        }}
        self.assertEqual(
            classify_probable_failure_source(coordinate)["probable_failure_source"],
            "COORDINATE_SCALING",
        )

        calibration = {**base, "known_size_sanity": {
            "status": "OUTSIDE_5_PERCENT", "known_size_percentage_error": 15.0,
        }}
        self.assertEqual(
            classify_probable_failure_source(calibration)["probable_failure_source"],
            "CALIBRATION",
        )

        localization = {**base, "glyph_measurement": {
            **base["glyph_measurement"], "localization_method": "substring_fallback",
        }}
        self.assertEqual(
            classify_probable_failure_source(localization)["probable_failure_source"],
            "GLYPH_LOCALIZATION",
        )


if __name__ == "__main__":
    unittest.main()
