"""Benchmark canonical extraction and rule decisions against manual labels."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parent
DEFAULT_GROUND_TRUTH = REPO_ROOT / "validation" / "ground_truth.csv"
DEFAULT_OUTPUT = REPO_ROOT / "results" / "validation"
RULE_IDS = (
    "LM-R6-001", "LM-R6-005", "LM-R6-006", "LM-R6-007", "LM-R6-008",
    "LM-R6-010", "LM-R13-001", "LM-R26-001", "LM-R7-001", "LM-R9-002",
)
STATUSES = {"PASS", "FAIL", "REVIEW", "NOT_APPLICABLE"}


def _known(value: Any) -> bool:
    return str(value or "").strip().upper() not in {"", "UNKNOWN"}


def _text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip().casefold()


def _decimal(value: Any) -> Decimal | None:
    cleaned = re.sub(r"(?i)(?:₹|rs\.?|inr|,|\s)", "", str(value or ""))
    try:
        return Decimal(cleaned)
    except InvalidOperation:
        return None


def _field_value(report: dict[str, Any], field: str, key: str | None = None) -> Any:
    value = ((report.get("extracted_fields") or {}).get(field) or {}).get("normalized_value")
    return value.get(key) if key and isinstance(value, dict) else value


def _compare(expected: Any, actual: Any, *, numeric: bool = False) -> tuple[bool, bool]:
    exact = str(actual or "").strip() == str(expected or "").strip()
    if numeric:
        left, right = _decimal(expected), _decimal(actual)
        normalized = left is not None and right is not None and left == right
    else:
        normalized = _text(expected) == _text(actual)
    return exact, normalized


def derive_quality_category(report: dict[str, Any]) -> str:
    quality = report.get("quality") or {}
    issues = {str(item).upper() for item in (quality.get("issues") or []) + (quality.get("warnings") or [])}
    if any("GLARE" in item for item in issues): return "glare"
    if any("BLUR" in item for item in issues): return "blur"
    if any("RESOLUTION" in item for item in issues): return "low_resolution"
    if not ((report.get("evidence") or {}).get("calibration") or {}).get("detected"): return "missing_calibration_marker"
    return "clear"


def evaluate_row(record: dict[str, str], report: dict[str, Any]) -> dict[str, Any]:
    actual_fields = {
        "mrp": _field_value(report, "mrp", "value"),
        "net_quantity_value": _field_value(report, "net_quantity", "value"),
        "net_quantity_unit": _field_value(report, "net_quantity", "unit"),
        "manufacturer": _field_value(report, "manufacturer", "name"),
    }
    extraction: dict[str, Any] = {}
    for field, actual in actual_fields.items():
        expected = record.get(f"expected_{field}", "")
        if not _known(expected):
            extraction[field] = {"evaluated": False, "expected": expected, "actual": actual}
            continue
        exact, normalized = _compare(expected, actual, numeric=field in {"mrp", "net_quantity_value"})
        extraction[field] = {
            "evaluated": True, "expected": expected, "actual": actual,
            "exact_match": exact, "normalized_match": normalized,
            "outcome": "correct" if normalized else "missing" if actual in {None, ""} else "incorrect",
        }
    predicted_rules = {item.get("rule_id"): item.get("status") for item in report.get("rule_results") or []}
    rules = {}
    for rule_id in RULE_IDS:
        expected = str(record.get(f"expected_{rule_id}", "")).strip().upper()
        predicted = predicted_rules.get(rule_id)
        evaluated = expected in STATUSES
        rules[rule_id] = {
            "evaluated": evaluated, "expected": expected or None, "predicted": predicted,
            "correct": bool(evaluated and expected == predicted),
            "false_pass": bool(evaluated and predicted == "PASS" and expected in {"FAIL", "REVIEW"}),
        }
    return {
        "image": record.get("image", ""),
        "quality_category": record.get("quality_category", "").strip() or derive_quality_category(report),
        "overall_status": (report.get("summary") or {}).get("overall_status"),
        "extraction": extraction,
        "rules": rules,
        "notes": record.get("notes", ""),
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    extraction_metrics = {}
    for field in ("mrp", "net_quantity_value", "net_quantity_unit", "manufacturer"):
        items = [row["extraction"][field] for row in rows if row["extraction"][field]["evaluated"]]
        count = len(items)
        outcomes = Counter(item["outcome"] for item in items)
        extraction_metrics[field] = {
            "evaluated": count,
            "exact_matches": sum(item["exact_match"] for item in items),
            "normalized_matches": sum(item["normalized_match"] for item in items),
            "exact_accuracy": sum(item["exact_match"] for item in items) / count if count else None,
            "normalized_accuracy": sum(item["normalized_match"] for item in items) / count if count else None,
            "missing": outcomes["missing"], "incorrect": outcomes["incorrect"],
        }
    rule_items = [item for row in rows for item in row["rules"].values() if item["evaluated"]]
    confusion = Counter(f'{item["expected"]}->{item["predicted"] or "MISSING"}' for item in rule_items)
    for transition in (
        "PASS->FAIL", "PASS->REVIEW", "FAIL->PASS", "FAIL->REVIEW",
        "REVIEW->PASS", "REVIEW->FAIL",
    ):
        confusion.setdefault(transition, 0)
    per_rule = {}
    for rule_id in RULE_IDS:
        items = [row["rules"][rule_id] for row in rows if row["rules"][rule_id]["evaluated"]]
        per_rule[rule_id] = {
            "evaluated": len(items), "correct": sum(item["correct"] for item in items),
            "incorrect": sum(not item["correct"] for item in items),
            "accuracy": sum(item["correct"] for item in items) / len(items) if items else None,
            "false_passes": sum(item["false_pass"] for item in items),
        }
    predicted = Counter(item["predicted"] for item in rule_items)
    correct = Counter(item["predicted"] for item in rule_items if item["correct"])
    quality: dict[str, dict[str, int]] = defaultdict(lambda: {"images": 0, "evaluated_rules": 0, "correct_rules": 0, "false_passes": 0})
    for row in rows:
        group = quality[row["quality_category"]]
        group["images"] += 1
        items = [item for item in row["rules"].values() if item["evaluated"]]
        group["evaluated_rules"] += len(items)
        group["correct_rules"] += sum(item["correct"] for item in items)
        group["false_passes"] += sum(item["false_pass"] for item in items)
    for group in quality.values():
        group["accuracy"] = (
            group["correct_rules"] / group["evaluated_rules"]
            if group["evaluated_rules"] else None
        )
    return {
        "images_processed": len(rows), "extraction_metrics": extraction_metrics,
        "rule_metrics": {
            "evaluated": len(rule_items), "correct": sum(item["correct"] for item in rule_items),
            "accuracy": sum(item["correct"] for item in rule_items) / len(rule_items) if rule_items else None,
            "pass_precision": correct["PASS"] / predicted["PASS"] if predicted["PASS"] else None,
            "fail_precision": correct["FAIL"] / predicted["FAIL"] if predicted["FAIL"] else None,
            "review_rate": predicted["REVIEW"] / len(rule_items) if rule_items else None,
            "false_passes": sum(item["false_pass"] for item in rule_items),
            "confusion": dict(sorted(confusion.items())), "per_rule": per_rule,
        },
        "quality_groups": dict(sorted(quality.items())),
    }


def load_ground_truth(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        for rule_id in RULE_IDS:
            value = str(row.get(f"expected_{rule_id}", "")).strip().upper()
            if value and value not in STATUSES | {"UNKNOWN"}:
                raise ValueError(f"Unsupported status {value!r} for {rule_id}")
    return [row for row in rows if str(row.get("image", "")).strip()]


def run_benchmark(records: Iterable[dict[str, str]], analyzer: Any) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rows = []
    for record in records:
        image = (REPO_ROOT / record["image"]).resolve()
        try:
            image.relative_to(REPO_ROOT)
        except ValueError as exc:
            raise ValueError(f"Image path escapes repository: {record['image']}") from exc
        if not image.is_file():
            raise FileNotFoundError(f"Ground-truth image not found: {record['image']}")
        rows.append(evaluate_row(record, analyzer.analyze_package(image, display_filename=image.name)))
    return rows, aggregate(rows)


def write_outputs(rows: list[dict[str, Any]], metrics: dict[str, Any], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    payload = {"generated_at": datetime.now(timezone.utc).isoformat(), **metrics, "rows": rows}
    (output / "benchmark.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with (output / "benchmark.csv").open("w", newline="", encoding="utf-8") as handle:
        columns = ["image", "quality_category", "overall_status", "evaluated_rules", "correct_rules", "false_passes"]
        writer = csv.DictWriter(handle, fieldnames=columns); writer.writeheader()
        for row in rows:
            items = [item for item in row["rules"].values() if item["evaluated"]]
            writer.writerow({"image": row["image"], "quality_category": row["quality_category"], "overall_status": row["overall_status"], "evaluated_rules": len(items), "correct_rules": sum(item["correct"] for item in items), "false_passes": sum(item["false_pass"] for item in items)})
    rule = metrics["rule_metrics"]
    lines = ["# Validation Benchmark", "", f"Images processed: {metrics['images_processed']}", ""]
    if not rule["evaluated"]:
        lines += ["No manually verified ground-truth decisions are available; accuracy is not reported.", ""]
    else:
        lines += [f"Rule accuracy: {rule['accuracy']:.1%} ({rule['correct']}/{rule['evaluated']})", f"False passes: {rule['false_passes']}", f"Review rate: {rule['review_rate']:.1%}", ""]
    lines += ["## Extraction metrics", ""]
    for field, item in metrics["extraction_metrics"].items():
        value = "not evaluated" if item["normalized_accuracy"] is None else f'{item["normalized_accuracy"]:.1%} ({item["normalized_matches"]}/{item["evaluated"]})'
        lines.append(f"- {field}: {value}")
    lines += ["", "## Quality groups", ""]
    lines += [f'- {name}: {item["images"]} image(s), {item["false_passes"]} false pass(es)' for name, item in metrics["quality_groups"].items()] or ["No quality groups available."]
    (output / "benchmark.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ground-truth", type=Path, default=DEFAULT_GROUND_TRUTH)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    records = load_ground_truth(args.ground_truth)
    if records:
        from services.analyzer import PackageAnalyzer
        rows, metrics = run_benchmark(records, PackageAnalyzer())
    else:
        rows, metrics = [], aggregate([])
    write_outputs(rows, metrics, args.output)
    print(f"Wrote validation benchmark to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
