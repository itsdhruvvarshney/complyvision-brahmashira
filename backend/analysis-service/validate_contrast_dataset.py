"""Evaluate LM-R9-002 engineering contrast against human-labeled package data.

Human labels and numeric thresholds in this workflow are prototype validation
instruments. They are not statutory Legal Metrology determinations.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from cv.aruco import detect_aruco_scale
from cv.contrast import measure_local_contrast
from cv.ocr import predict_ocr_items, recover_split_quantity_items
from cv.ocr_filter import filter_ocr_items_near_aruco
from cv.quality import analyze_image_quality
from extract_fields import extract_fields
from rules.engine import validate_contrast_target


ANNOTATIONS_PATH = Path("validation/contrast/annotations.json")
IMAGES_DIRECTORY = Path("validation/contrast/images")
DEBUG_DIRECTORY = Path("validation/contrast/debug")
JSON_OUTPUT = Path("results/contrast_validation.json")
CSV_OUTPUT = Path("results/contrast_validation.csv")
THRESHOLD_OUTPUT = Path("results/contrast_threshold_analysis.csv")
FAILURE_OUTPUT = Path("results/contrast_validation_failures.txt")
VALID_LABELS = {"CLEAR_CONTRAST", "LOW_CONTRAST", "UNCERTAIN"}
VALID_TARGETS = {"MRP", "NET_QUANTITY"}
EXPECTED_SYSTEM_STATUS = {
    "CLEAR_CONTRAST": "PASS",
    "LOW_CONTRAST": "FAIL",
    "UNCERTAIN": "REVIEW",
}
BOOLEAN_FIELDS = (
    "glare_present",
    "gradient_background",
    "textured_background",
    "unusual_text_color",
)
CSV_COLUMNS = [
    "annotation_id", "image", "target_type", "target_text", "human_label",
    "expected_system_status", "system_status", "correct", "contrast_ratio",
    "lab_difference", "confidence", "ocr_confidence", "localization_confidence",
    "background_uniformity", "global_glare_ratio", "local_glare_ratio", "issues",
    "localization_method", "probable_failure_cause", "annotation_notes",
]


class AnnotationValidationError(ValueError):
    pass


def load_annotations(path: str | Path = ANNOTATIONS_PATH) -> list[dict[str, Any]]:
    """Load and strictly validate human annotations without reading images."""
    source = Path(path)
    try:
        payload = json.loads(source.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise AnnotationValidationError(f"Annotation file does not exist: {source}") from exc
    except json.JSONDecodeError as exc:
        raise AnnotationValidationError(f"Annotation file is not valid JSON: {exc}") from exc
    records = payload.get("annotations") if isinstance(payload, dict) else payload
    if not isinstance(records, list):
        raise AnnotationValidationError("Annotation payload must contain an annotations list")

    validated = []
    identifiers = set()
    for index, raw in enumerate(records, start=1):
        if not isinstance(raw, dict):
            raise AnnotationValidationError(f"Annotation {index} must be an object")
        record = dict(raw)
        for field in ("image_filename", "target_type", "expected_target_text", "human_label"):
            if not isinstance(record.get(field), str) or not record[field].strip():
                raise AnnotationValidationError(f"Annotation {index} has invalid {field}")
        record["target_type"] = record["target_type"].upper()
        record["human_label"] = record["human_label"].upper()
        if record["target_type"] not in VALID_TARGETS:
            raise AnnotationValidationError(
                f"Annotation {index} target_type must be one of {sorted(VALID_TARGETS)}"
            )
        if record["human_label"] not in VALID_LABELS:
            raise AnnotationValidationError(
                f"Annotation {index} human_label must be one of {sorted(VALID_LABELS)}"
            )
        declaration = str(record.get("declaration_type") or record["target_type"]).upper()
        if declaration not in VALID_TARGETS or declaration != record["target_type"]:
            raise AnnotationValidationError(
                f"Annotation {index} declaration_type must match target_type"
            )
        record["declaration_type"] = declaration
        for field in BOOLEAN_FIELDS:
            if field in record and not isinstance(record[field], bool):
                raise AnnotationValidationError(f"Annotation {index} {field} must be boolean")
            record.setdefault(field, False)
        categories = record.get("coverage_categories", [])
        if not isinstance(categories, list) or any(not isinstance(item, str) for item in categories):
            raise AnnotationValidationError(
                f"Annotation {index} coverage_categories must be a list of strings"
            )
        polygon = record.get("manual_target_polygon")
        if polygon is not None:
            valid_polygon = (
                isinstance(polygon, list)
                and len(polygon) >= 4
                and all(
                    isinstance(point, list)
                    and len(point) == 2
                    and all(isinstance(value, (int, float)) for value in point)
                    for point in polygon
                )
            )
            if not valid_polygon:
                raise AnnotationValidationError(
                    f"Annotation {index} manual_target_polygon must contain at least four [x, y] points"
                )
        identifier = str(record.get("id") or f"{Path(record['image_filename']).stem}_{record['target_type'].lower()}")
        if identifier in identifiers:
            raise AnnotationValidationError(f"Duplicate annotation id: {identifier}")
        identifiers.add(identifier)
        record["id"] = identifier
        record.setdefault("annotation_notes", "")
        validated.append(record)
    return validated


class OCRContrastEvidenceProvider:
    """Reuse one PaddleOCR instance to extract both declarations per image."""

    def __init__(self, ocr: Any):
        self.ocr = ocr

    def __call__(self, image_path: Path) -> dict[str, Any]:
        quality = analyze_image_quality(str(image_path))
        raw_items = predict_ocr_items(self.ocr, str(image_path))
        recovered, recovery = recover_split_quantity_items(str(image_path), raw_items, self.ocr)
        try:
            aruco = detect_aruco_scale(str(image_path), marker_size_mm=50.0)
            items = list(filter_ocr_items_near_aruco(recovered, aruco.get("corners")))
        except Exception:
            items = recovered
        return {
            "fields": extract_fields(items),
            "image_quality": quality,
            "raw_ocr_item_count": len(raw_items),
            "quantity_crop_recovery": recovery,
        }


def _manual_evidence(annotation: dict[str, Any]) -> dict[str, Any] | None:
    polygon = annotation.get("manual_target_polygon")
    if polygon is None:
        return None
    number = re.search(r"\d+(?:[.,]\d+)?", annotation["expected_target_text"])
    return {
        "value": float(number.group(0).replace(",", ".")) if number else None,
        "confidence": 0.0,
        "source_text": annotation["expected_target_text"],
        "source_box": polygon,
        "manual_target_polygon": polygon,
    }


def _safe_image_path(images_directory: Path, filename: str) -> Path | None:
    root = images_directory.resolve()
    candidate = (root / filename).resolve()
    return candidate if candidate.is_relative_to(root) else None


def _text_matches(expected: str, observed: str | None) -> bool:
    normalize = lambda value: re.sub(r"[^a-z0-9.]", "", (value or "").lower())
    expected_normalized = normalize(expected)
    observed_normalized = normalize(observed)
    return bool(expected_normalized and expected_normalized in observed_normalized)


def probable_failure_cause(record: dict[str, Any]) -> str | None:
    """Assign one explainable cause to an incorrect classification."""
    if record.get("correct"):
        return None
    issues = set(record.get("issues") or [])
    categories = set(record.get("coverage_categories") or [])
    if "MISSING_IMAGE" in issues or "IMAGE_PATH_OUTSIDE_DATASET" in issues:
        return "INSUFFICIENT_EVIDENCE"
    if "EXPECTED_TARGET_TEXT_MISMATCH" in issues:
        return "OCR_TARGET_ERROR"
    if "MISSING_OCR_TARGET" in issues or record.get("localization_method") is None:
        return "LOCALIZATION_ERROR"
    if record.get("glare_present") or any("GLARE" in issue for issue in issues):
        return "GLARE"
    if record.get("gradient_background"):
        return "GRADIENT"
    if record.get("textured_background"):
        return "TEXTURE"
    if "curved_bottle" in categories:
        return "CURVATURE"
    if any("LOW_RESOLUTION" in issue for issue in issues):
        return "LOW_RESOLUTION"
    if "FOREGROUND_BACKGROUND_SEPARATION_AMBIGUOUS" in issues:
        return "FOREGROUND_EXTRACTION"
    if "HETEROGENEOUS_LOCAL_BACKGROUND" in issues:
        return "BACKGROUND_SAMPLING"
    if record.get("contrast_ratio") is not None:
        return "THRESHOLD_BOUNDARY"
    return "INSUFFICIENT_EVIDENCE"


def run_annotations(
    annotations: list[dict[str, Any]],
    images_directory: str | Path,
    evidence_provider: Callable[[Path], dict[str, Any]],
    *,
    contrast_measurer: Callable[..., dict[str, Any]] = measure_local_contrast,
    debug_directory: str | Path | None = DEBUG_DIRECTORY,
) -> list[dict[str, Any]]:
    """Run the existing contrast measurement once for every annotated target."""
    image_root = Path(images_directory)
    cache: dict[Path, dict[str, Any]] = {}
    results = []
    for annotation in annotations:
        expected_status = EXPECTED_SYSTEM_STATUS[annotation["human_label"]]
        image_path = _safe_image_path(image_root, annotation["image_filename"])
        issues: list[str] = []
        measurement: dict[str, Any] = {}
        observed_text = None
        ocr_confidence = None
        image_quality: dict[str, Any] = {}
        if image_path is None:
            issues.append("IMAGE_PATH_OUTSIDE_DATASET")
        elif not image_path.is_file():
            issues.append("MISSING_IMAGE")
        else:
            try:
                if image_path not in cache:
                    cache[image_path] = evidence_provider(image_path)
                extracted = cache[image_path]
                image_quality = extracted.get("image_quality") or {}
                issues.extend(
                    f"IMAGE_WARNING_{warning}"
                    for warning in image_quality.get("warnings") or []
                )
                field_name = "mrp" if annotation["target_type"] == "MRP" else "net_quantity"
                evidence = (extracted.get("fields") or {}).get(field_name)
                manual_evidence = _manual_evidence(annotation)
                if manual_evidence is not None:
                    if isinstance(evidence, dict):
                        manual_evidence["confidence"] = evidence.get("confidence", 0.0)
                        manual_evidence["source_text"] = (
                            evidence.get("source_text") or annotation["expected_target_text"]
                        )
                    evidence = manual_evidence
                if not isinstance(evidence, dict):
                    issues.append("MISSING_OCR_TARGET")
                else:
                    observed_text = evidence.get("source_text")
                    ocr_confidence = evidence.get("confidence")
                    debug_path = None
                    if debug_directory is not None:
                        safe_identifier = re.sub(r"[^A-Za-z0-9_.-]", "_", annotation["id"])
                        debug_path = Path(debug_directory) / f"{safe_identifier}.jpg"
                    measurement = contrast_measurer(
                        str(image_path), evidence, annotation["target_type"],
                        image_quality=image_quality, debug_image_path=debug_path,
                    )
                    issues.extend(measurement.get("issues") or [])
                    if not _text_matches(annotation["expected_target_text"], observed_text):
                        issues.append("EXPECTED_TARGET_TEXT_MISMATCH")
            except Exception as exc:
                issues.append(f"PIPELINE_ERROR:{type(exc).__name__}:{exc}")

        system_status, system_reason = validate_contrast_target(
            measurement, target_name=annotation["target_type"]
        )
        if "EXPECTED_TARGET_TEXT_MISMATCH" in issues:
            system_status = "REVIEW"
            system_reason = "Expected target text does not match the localized OCR declaration"
        result = {
            "annotation_id": annotation["id"],
            "image": annotation["image_filename"],
            "target_type": annotation["target_type"],
            "target_text": observed_text,
            "expected_target_text": annotation["expected_target_text"],
            "human_label": annotation["human_label"],
            "expected_system_status": expected_status,
            "system_status": system_status,
            "system_reason": system_reason,
            "correct": system_status == expected_status,
            "contrast_ratio": measurement.get("contrast_ratio"),
            "lab_difference": measurement.get("lab_color_difference"),
            "confidence": measurement.get("confidence", 0.0),
            "ocr_confidence": ocr_confidence,
            "localization_confidence": measurement.get("localization_confidence"),
            "background_uniformity": (measurement.get("confidence_factors") or {}).get("background_uniformity"),
            "global_glare_ratio": image_quality.get("glare_ratio"),
            "local_glare_ratio": measurement.get("local_glare_ratio"),
            "issues": list(dict.fromkeys(issues)),
            "localization_method": measurement.get("localization_method"),
            "target_box": measurement.get("target_box"),
            "debug_image_path": measurement.get("debug_image_path"),
            "annotation_notes": annotation.get("annotation_notes", ""),
            "coverage_categories": annotation.get("coverage_categories", []),
            **{field: annotation.get(field, False) for field in BOOLEAN_FIELDS},
        }
        result["probable_failure_cause"] = probable_failure_cause(result)
        results.append(result)
    return results


def calculate_evaluation_metrics(records: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(record["human_label"] for record in records)
    coverage_counts = Counter(
        category
        for record in records
        for category in record.get("coverage_categories") or []
    )
    total = len(records)
    correct = sum(bool(record.get("correct")) for record in records)
    system_passes = sum(record.get("system_status") == "PASS" for record in records)
    system_fails = sum(record.get("system_status") == "FAIL" for record in records)
    true_passes = sum(
        record.get("system_status") == "PASS" and record.get("human_label") == "CLEAR_CONTRAST"
        for record in records
    )
    true_fails = sum(
        record.get("system_status") == "FAIL" and record.get("human_label") == "LOW_CONTRAST"
        for record in records
    )
    return {
        "total_samples": total,
        "samples_by_category": {label: counts.get(label, 0) for label in sorted(VALID_LABELS)},
        "samples_by_coverage_category": dict(sorted(coverage_counts.items())),
        "correct_classifications": correct,
        "incorrect_classifications": total - correct,
        "review_count": sum(record.get("system_status") == "REVIEW" for record in records),
        "false_pass": sum(
            record.get("system_status") == "PASS" and record.get("human_label") == "LOW_CONTRAST"
            for record in records
        ),
        "false_fail": sum(
            record.get("system_status") == "FAIL" and record.get("human_label") == "CLEAR_CONTRAST"
            for record in records
        ),
        "human_uncertain_but_system_decisive": sum(
            record.get("human_label") == "UNCERTAIN"
            and record.get("system_status") in {"PASS", "FAIL"}
            for record in records
        ),
        "pass_precision": round(true_passes / system_passes, 4) if system_passes else None,
        "fail_precision": round(true_fails / system_fails, 4) if system_fails else None,
    }


def threshold_distribution_analysis(records: list[dict[str, Any]]) -> dict[str, Any]:
    metrics = ("contrast_ratio", "lab_difference", "confidence")
    distributions: dict[str, Any] = {}
    for label in sorted(VALID_LABELS):
        distributions[label] = {}
        label_records = [record for record in records if record["human_label"] == label]
        for metric in metrics:
            values = [
                float(record[metric]) for record in label_records
                if isinstance(record.get(metric), (int, float))
            ]
            distributions[label][metric] = {
                "count": len(values),
                "min": round(min(values), 4) if values else None,
                "max": round(max(values), 4) if values else None,
                "median": round(statistics.median(values), 4) if values else None,
                "mean": round(statistics.mean(values), 4) if values else None,
            }
    clear = distributions["CLEAR_CONTRAST"]
    low = distributions["LOW_CONTRAST"]
    overlap = {}
    for metric in metrics:
        clear_min, clear_max = clear[metric]["min"], clear[metric]["max"]
        low_min, low_max = low[metric]["min"], low[metric]["max"]
        overlap[metric] = (
            None if None in (clear_min, clear_max, low_min, low_max)
            else max(clear_min, low_min) <= min(clear_max, low_max)
        )
    return {
        "current_engineering_thresholds": {
            "strong_contrast_ratio": 3.0,
            "strong_lab_difference": 35.0,
            "low_contrast_ratio": 1.5,
            "low_lab_difference": 12.0,
            "statutory_threshold": None,
        },
        "distributions": distributions,
        "clear_low_range_overlap": overlap,
    }


def threshold_recommendation(
    metrics: dict[str, Any], analysis: dict[str, Any]
) -> str:
    counts = metrics["samples_by_category"]
    if metrics["total_samples"] < 30 or any(counts[label] == 0 for label in VALID_LABELS):
        return "INSUFFICIENT_VALIDATION_DATA"
    overlaps = [
        analysis["clear_low_range_overlap"][metric]
        for metric in ("contrast_ratio", "lab_difference")
        if analysis["clear_low_range_overlap"][metric] is not None
    ]
    accuracy = metrics["correct_classifications"] / metrics["total_samples"]
    if metrics["false_pass"] == 0 and accuracy >= 0.90 and not any(overlaps):
        return "KEEP_CURRENT_THRESHOLDS"
    return "RECOMMEND_THRESHOLD_CHANGE"


def save_validation_reports(
    records: list[dict[str, Any]], metrics: dict[str, Any], analysis: dict[str, Any],
    recommendation: str, *, json_path: str | Path = JSON_OUTPUT,
    csv_path: str | Path = CSV_OUTPUT, threshold_path: str | Path = THRESHOLD_OUTPUT,
    failure_path: str | Path = FAILURE_OUTPUT,
) -> None:
    for path in (json_path, csv_path, threshold_path, failure_path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "purpose": "Engineering contrast validation only; not a legal determination",
        "dataset_status": metrics["samples_by_category"],
        "evaluation": metrics,
        "threshold_analysis": analysis,
        "threshold_recommendation": recommendation,
        "lm_r9_002_status": (
            "IMPLEMENTED_NOT_VALIDATED" if not records
            else "ENGINEERING_VALIDATED" if recommendation == "KEEP_CURRENT_THRESHOLDS"
            else "VALIDATION_IN_PROGRESS"
        ),
        "lm_r7_001_status": "REVIEW",
        "results": records,
    }
    Path(json_path).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with Path(csv_path).open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=CSV_COLUMNS, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        for record in records:
            row = dict(record)
            row["issues"] = " | ".join(record.get("issues") or [])
            writer.writerow(row)
    with Path(threshold_path).open("w", encoding="utf-8", newline="") as output:
        columns = ["human_label", "metric", "count", "min", "max", "median", "mean"]
        writer = csv.DictWriter(output, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        for label, metric_data in analysis["distributions"].items():
            for metric, values in metric_data.items():
                writer.writerow({"human_label": label, "metric": metric, **values})
    failures = [record for record in records if not record.get("correct")]
    lines = ["Contrast validation failure analysis", "Engineering validation only; not a legal determination", ""]
    for record in failures:
        lines.extend([
            f"Image: {record['image']}", f"Target: {record['target_type']}",
            f"Human: {record['human_label']}", f"System: {record['system_status']}",
            f"Contrast ratio: {record.get('contrast_ratio')}",
            f"Lab difference: {record.get('lab_difference')}",
            f"Confidence: {record.get('confidence')}",
            f"Probable issue: {record.get('probable_failure_cause')}",
            f"Debug overlay: {record.get('debug_image_path')}", "",
        ])
    if not failures:
        lines.append("No incorrect classifications are available for analysis.")
    Path(failure_path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, default=ANNOTATIONS_PATH)
    parser.add_argument("--images-dir", type=Path, default=IMAGES_DIRECTORY)
    parser.add_argument("--debug-dir", type=Path, default=DEBUG_DIRECTORY)
    parser.add_argument("--json-output", type=Path, default=JSON_OUTPUT)
    parser.add_argument("--csv-output", type=Path, default=CSV_OUTPUT)
    parser.add_argument("--threshold-output", type=Path, default=THRESHOLD_OUTPUT)
    parser.add_argument("--failure-output", type=Path, default=FAILURE_OUTPUT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    annotations = load_annotations(args.annotations)
    if annotations:
        from paddleocr import PaddleOCR
        provider: Callable[[Path], dict[str, Any]] = OCRContrastEvidenceProvider(PaddleOCR(lang="en"))
    else:
        provider = lambda _path: {"fields": {}, "image_quality": {}}
    records = run_annotations(
        annotations, args.images_dir, provider, debug_directory=args.debug_dir
    )
    metrics = calculate_evaluation_metrics(records)
    analysis = threshold_distribution_analysis(records)
    recommendation = threshold_recommendation(metrics, analysis)
    save_validation_reports(
        records, metrics, analysis, recommendation, json_path=args.json_output,
        csv_path=args.csv_output, threshold_path=args.threshold_output,
        failure_path=args.failure_output,
    )
    print(json.dumps({
        "dataset_status": metrics["samples_by_category"],
        "evaluation": metrics,
        "threshold_recommendation": recommendation,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
