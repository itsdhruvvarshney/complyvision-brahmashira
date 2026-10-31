"""Map enhanced OCR declarations into the canonical report shape."""

from __future__ import annotations

from typing import Any

_HIDDEN = {"confidence", "source_text", "source_box", "source_image", "label_text", "label_box"}


def merge_enhanced_fields(report: dict[str, Any], fields: dict[str, Any]) -> dict[str, Any]:
    extracted = report.setdefault("extracted_fields", {})
    for name in (
        "product",
        "common_generic_name",
        "mrp",
        "manufacture_date",
        "unit_sale_price",
        "use_by_date",
    ):
        value = fields.get(name)
        if not isinstance(value, dict):
            continue
        extracted[name] = {
            "field_name": name,
            "present": True,
            "normalized_value": {key: item for key, item in value.items() if key not in _HIDDEN},
            "raw_text": value.get("source_text"),
            "ocr_confidence": value.get("confidence"),
            "extraction_confidence": value.get("confidence"),
            "source_polygon": value.get("source_box"),
            "extraction_method": value.get("extraction_method"),
            "issues": [],
        }
    return report
