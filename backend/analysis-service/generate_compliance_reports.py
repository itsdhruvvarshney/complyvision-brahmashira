"""Generate canonical JSON and Markdown compliance reports for sample images."""

from __future__ import annotations

import json
from pathlib import Path

from batch_measure import DEFAULT_IMAGES, process_batch
from reporting.report import build_package_report, save_package_report


REPORT_DIRECTORY = Path("results/reports")


def generate_sample_reports(
    image_paths=DEFAULT_IMAGES, *, output_directory=REPORT_DIRECTORY,
) -> list[dict]:
    from paddleocr import PaddleOCR

    ocr = PaddleOCR(lang="en")
    batch_results = process_batch(
        image_paths, ocr, debug_directory=Path("debug") / "reports"
    )
    output_root = Path(output_directory)
    summaries = []
    for result in batch_results:
        report = build_package_report(result)
        stem = Path(result["image"]).stem
        save_package_report(
            report, output_root / f"{stem}.json", output_root / f"{stem}.md"
        )
        net_quantity = report["extracted_fields"]["net_quantity"]["normalized_value"]
        mrp = report["extracted_fields"]["mrp"]["normalized_value"]
        summary = report["summary"]
        summaries.append({
            "image": report["image"]["filename"],
            "net_quantity": net_quantity,
            "mrp": mrp,
            "pass_count": summary["pass_count"],
            "fail_count": summary["fail_count"],
            "review_count": summary["review_count"],
            "not_applicable_count": summary["not_applicable_count"],
            "overall_result": summary["overall_status"],
            "review_reasons": [
                {"rule_id": rule["rule_id"], "reason": rule["reason"]}
                for rule in report["rule_results"] if rule["status"] == "REVIEW"
            ],
        })
    output_root.mkdir(parents=True, exist_ok=True)
    (output_root / "summary.json").write_text(
        json.dumps(summaries, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return summaries


def print_summary(summaries: list[dict]) -> None:
    print("IMAGE | NET QUANTITY | MRP | PASS | FAIL | REVIEW | N/A | OVERALL")
    for item in summaries:
        quantity = item["net_quantity"] or {}
        quantity_text = " ".join(
            str(quantity.get(key)) for key in ("value", "unit") if quantity.get(key) is not None
        ) or "--"
        mrp = item["mrp"] or {}
        mrp_text = str(mrp.get("value")) if mrp.get("value") is not None else "--"
        print(
            f"{item['image']} | {quantity_text} | {mrp_text} | "
            f"{item['pass_count']} | {item['fail_count']} | {item['review_count']} | "
            f"{item['not_applicable_count']} | {item['overall_result']}"
        )


def main() -> int:
    summaries = generate_sample_reports()
    print_summary(summaries)
    print(f"\nReports: {REPORT_DIRECTORY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
