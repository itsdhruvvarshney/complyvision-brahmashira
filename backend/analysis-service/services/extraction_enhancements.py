"""Deterministic post-processing for high-confidence OCR declarations.

The OCR engine already sees the label text. This module turns explicit label
patterns into structured declarations without using nearby numbers blindly.
It is deliberately conservative: ambiguous text stays unset/reviewable.
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any


MRP_LABEL_RE = re.compile(
    r"\b(?:M\s*\.?\s*R\s*\.?\s*P\.?|MAX(?:IMUM)?\s+RETAIL\s+PRICE|RETAIL\s+SALE\s+PRICE)\b",
    re.I,
)
MFG_LABEL_RE = re.compile(
    r"\b(?:MFG\.?\s*DATE|MFD\.?\s*DATE|MANUFACTURE(?:D)?\s*DATE|DATE\s+OF\s+MANUFACTURE|"
    r"MONTH\s*&\s*YEAR\s+OF\s+MANUFACTURE|PACKED\s+ON|PKD)\b",
    re.I,
)
USE_BY_LABEL_RE = re.compile(r"\b(?:USE\s+BY|BEST\s+BEFORE|EXP(?:IRY)?)\b", re.I)
GENERIC_LABEL_RE = re.compile(
    r"^\s*(?:COMMON\s+NAME|GENERIC\s+NAME|COMMON\s+OR\s+GENERIC\s+NAME|NAME\s+OF\s+COMMODITY)\s*[:;.-]*\s*(.*)$",
    re.I,
)
UNIT_SALE_LABEL_RE = re.compile(
    r"\b(?:USP|UNIT\s+SALE\s+PRICE)\b|\b(?:PER\s+(?:G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT))\b",
    re.I,
)
PRICE_RE = re.compile(r"(?:₹|\bRS\.?\b|\bINR\b)?\s*(\d{1,6}(?:\.\d{1,2})?)\s*(?:/-)?", re.I)
DATE_RE = re.compile(
    r"\b(\d{1,2})\s*[-/]\s*(\d{1,2})\s*[-/]\s*(\d{2,4})\b|"
    r"\b(\d{1,2})\s*[-/]\s*(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\s*[-/]?\s*(\d{4})\b|"
    r"\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\s+(\d{4})\b",
    re.I,
)
MONTHS = {
    "JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "MAY": 5, "JUN": 6,
    "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12,
}

FOOTER_NOISE = re.compile(
    r"\b(?:KEEP\s+YOUR\s+CITY\s+CLEAN|PLEASE\s+RECYCLE|IMAGES?\s+ARE|"
    r"ILLUSTRATIVE\s+PURPOSE|NUTRITIONAL\s+INFORMATION|INGREDIENTS?|PER\s+100\s+G|"
    r"SERVING\s+SIZE|FSSAI|LIC\.?\s*NO|BATCH\s+NO|TRANS\s+FAT|CHOLESTEROL|SODIUM|"
    r"CARBOHYDRATE|PROTEIN|TOTAL\s+SUGARS?|ADDED\s+SUGARS?|TOTAL\s+FAT|SATURATED\s+FAT|"
    r"ENERGY\s*\(\s*KCAL\s*\)|www\.)\b",
    re.I,
)


def _items(fields: dict[str, Any]) -> list[dict[str, Any]]:
    raw = fields.get("ocr_evidence") or []
    result = []
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            continue
        text = str(item.get("normalized_text") or item.get("raw_text") or "").strip()
        if not text:
            continue
        result.append({
            **item,
            "text": text,
            "raw_text": str(item.get("raw_text") or text),
            "confidence": float(item.get("confidence", 0.0) or 0.0),
            "_index": index,
        })
    return result


def _evidence(item: dict[str, Any], confidence: float | None = None) -> dict[str, Any]:
    return {
        "confidence": round(item["confidence"] if confidence is None else confidence, 3),
        "source_text": item["raw_text"],
        "source_box": item.get("box"),
        "source_image": item.get("source_image"),
    }


def _parse_price(text: str) -> float | None:
    value = text.strip()
    if UNIT_SALE_LABEL_RE.search(value) or re.search(r"\bPER\s+[A-Z]+\b", value, re.I):
        return None
    match = PRICE_RE.search(value)
    if not match:
        return None
    amount = float(match.group(1))
    if amount <= 0 or amount >= 100000:
        return None
    return amount


def _nearby_price(items: list[dict[str, Any]], label_index: int) -> tuple[dict[str, Any], float] | None:
    label = items[label_index]
    candidates: list[tuple[float, dict[str, Any], float]] = []
    for offset in range(1, 7):
        for index in (label_index - offset, label_index + offset):
            if not 0 <= index < len(items):
                continue
            item = items[index]
            if UNIT_SALE_LABEL_RE.search(item["text"]):
                continue
            amount = _parse_price(item["text"])
            if amount is None:
                continue
            score = 100.0 - offset * 12.0 + item["confidence"] * 10.0
            if index == label_index - 1:
                score += 18.0
            if index == label_index + 1:
                score += 14.0
            if re.search(r"₹|\bRS\.?\b|\bINR\b|/-", item["text"], re.I):
                score += 15.0
            candidates.append((score, item, amount))
    if not candidates:
        return None
    score, item, amount = max(candidates, key=lambda entry: entry[0])
    return item, amount


def _extract_mrp(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    labels = [i for i, item in enumerate(items) if MRP_LABEL_RE.search(item["text"])]
    for label_index in labels:
        label = items[label_index]
        # Prefer an amount written directly on the MRP line.
        inline_text = MRP_LABEL_RE.sub("", label["text"], count=1)
        amount = _parse_price(inline_text)
        if amount is not None:
            return {
                "currency": "INR",
                "value": amount,
                "inclusive_of_all_taxes": bool(re.search(r"(?:INCLUSIVE|INCL\.?\s*OF)\s+OF?\s*ALL\s+TAXES", label["text"], re.I)),
                **_evidence(label),
            }
        nearby = _nearby_price(items, label_index)
        if nearby:
            item, amount = nearby
            return {
                "currency": "INR",
                "value": amount,
                "inclusive_of_all_taxes": False,
                **_evidence(item),
                "label_text": label["raw_text"],
                "label_box": label.get("box"),
                "extraction_method": "explicit_mrp_label_adjacent_amount",
            }
    return None


def _parse_date(text: str) -> tuple[str, str] | None:
    match = DATE_RE.search(text)
    if not match:
        return None
    groups = match.groups()
    try:
        if groups[0] is not None:
            day, month, year = int(groups[0]), int(groups[1]), int(groups[2])
            year += 2000 if year < 70 else (1900 if year < 100 else 0)
            normalized = datetime(year, month, day).strftime("%Y-%m-%d")
            return match.group(0), normalized
        if groups[3] is not None:
            day, month_name, year = int(groups[3]), groups[4], int(groups[5])
            normalized = datetime(year, MONTHS[month_name.upper()[:3]], day).strftime("%Y-%m-%d")
            return match.group(0), normalized
        month_name, year = groups[6], int(groups[7])
        normalized = f"{year:04d}-{MONTHS[month_name.upper()[:3]]:02d}"
        return match.group(0), normalized
    except (TypeError, ValueError, KeyError):
        return None


def _extract_date(items: list[dict[str, Any]], label_re: re.Pattern[str]) -> dict[str, Any] | None:
    labels = [i for i, item in enumerate(items) if label_re.search(item["text"])]
    for label_index in labels:
        label = items[label_index]
        # Search the full OCR stream in a small semantic window. This handles
        # OCR engines that split a label and its value into separate regions.
        for offset in range(0, 10):
            for index in ([label_index] if offset == 0 else [label_index - offset, label_index + offset]):
                if not 0 <= index < len(items):
                    continue
                candidate = items[index]
                if label_re is USE_BY_LABEL_RE and MFG_LABEL_RE.search(candidate["text"]):
                    continue
                if label_re is not USE_BY_LABEL_RE and USE_BY_LABEL_RE.search(candidate["text"]):
                    continue
                parsed = _parse_date(candidate["text"])
                if parsed:
                    raw, normalized = parsed
                    return {
                        "raw": raw,
                        "normalized": normalized,
                        "type": "expiry_date" if label_re is USE_BY_LABEL_RE else "manufacture_date",
                        **_evidence(candidate, min(candidate["confidence"], 0.99)),
                        "label_text": label["raw_text"],
                        "label_box": label.get("box"),
                        "extraction_method": "explicit_date_label_semantic_window",
                    }
    return None


def _extract_unit_sale_price(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    for index, item in enumerate(items):
        if not UNIT_SALE_LABEL_RE.search(item["text"]):
            continue
        label = item["text"]
        match = PRICE_RE.search(label)
        if match and re.search(r"PER\s+[A-Z]+", label, re.I):
            unit_match = re.search(r"\bPER\s+(G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT)\b", label, re.I)
            return {
                "currency": "INR",
                "value": float(match.group(1)),
                "unit": unit_match.group(1).upper() if unit_match else None,
                **_evidence(item),
                "extraction_method": "explicit_unit_sale_price_label",
            }
        for offset in range(1, 4):
            for candidate_index in (index - offset, index + offset):
                if not 0 <= candidate_index < len(items):
                    continue
                candidate = items[candidate_index]
                if not re.search(r"PER\s+[A-Z]+", candidate["text"], re.I):
                    continue
                match = PRICE_RE.search(candidate["text"])
                if not match:
                    continue
                unit_match = re.search(r"\bPER\s+(G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT)\b", candidate["text"], re.I)
                return {
                    "currency": "INR",
                    "value": float(match.group(1)),
                    "unit": unit_match.group(1).upper() if unit_match else None,
                    **_evidence(candidate),
                    "label_text": label,
                    "label_box": item.get("box"),
                    "extraction_method": "explicit_unit_sale_price_label",
                }
    return None


def _extract_generic_name(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    # First choice: an explicit common/generic-name label.
    for index, item in enumerate(items):
        match = GENERIC_LABEL_RE.match(item["text"])
        if not match:
            continue
        inline = match.group(1).strip(" :;.-")
        if inline:
            return {"value": inline, **_evidence(item), "extraction_method": "explicit_generic_name_label"}
        for offset in range(1, 4):
            for candidate_index in (index + offset, index - offset):
                if not 0 <= candidate_index < len(items):
                    continue
                candidate = items[candidate_index]
                if candidate["text"] and not FOOTER_NOISE.search(candidate["text"]):
                    return {"value": candidate["text"], **_evidence(candidate), "extraction_method": "explicit_generic_name_label"}

    # Conservative fallback for packaging where the commodity name is printed
    # as a standalone heading. Require a clean, high-confidence, multi-word
    # title-case line; this avoids selecting INGREDIENTS or footer slogans.
    candidates = []
    for item in items:
        text = item["text"].strip(" .:-")
        if item["confidence"] < 0.90 or FOOTER_NOISE.search(text):
            continue
        if not re.fullmatch(r"[A-Za-z][A-Za-z &'’\-]{2,59}", text):
            continue
        words = text.split()
        if not 2 <= len(words) <= 5:
            continue
        if text.isupper() or text.islower():
            continue
        if any(word.lower() in {"ingredients", "information", "purpose", "serve", "size"} for word in words):
            continue
        title_score = sum(word[:1].isupper() for word in words) / len(words)
        score = item["confidence"] * 10 + title_score * 12
        if re.search(r"\b(?:biscuit|shampoo|soap|toothpaste|tea|flour|rice|oil|juice|drink|cream|lotion)\b", text, re.I):
            score += 10
        candidates.append((score, item))
    if candidates:
        _, item = max(candidates, key=lambda pair: pair[0])
        return {"value": item["text"], **_evidence(item), "extraction_method": "standalone_commodity_heading"}
    return None


def _extract_product(items: list[dict[str, Any]], generic: dict[str, Any] | None) -> dict[str, Any] | None:
    # If a generic name was found, select the nearest clean title-case heading
    # as the commercial product name. Otherwise use the same conservative scan.
    generic_item = None
    if generic and generic.get("source_box") is not None:
        for item in items:
            if item.get("box") == generic.get("source_box"):
                generic_item = item
                break
    candidates = []
    for item in items:
        text = item["text"].strip(" .:-")
        if item["confidence"] < 0.90 or FOOTER_NOISE.search(text):
            continue
        if not re.fullmatch(r"[A-Za-z][A-Za-z &'’\-]{2,59}", text):
            continue
        words = text.split()
        if not 2 <= len(words) <= 5 or text.isupper() or text.islower():
            continue
        if any(word.lower() in {"ingredients", "information", "purpose", "serving", "illustrative", "feedback", "complaints"} for word in words):
            continue
        if generic_item is not None and item is generic_item:
            continue
        title_score = sum(word[:1].isupper() for word in words) / len(words)
        score = item["confidence"] * 10 + title_score * 12
        if re.search(r"\b(?:biscuit|shampoo|soap|toothpaste|tea|flour|rice|oil|juice|drink|cream|lotion)\b", text, re.I):
            score += 12
        if generic_item is not None:
            # OCR ordering is not guaranteed, so use index distance only as a
            # tie-breaker rather than a hard positional requirement.
            score += max(0.0, 8.0 - abs(item["_index"] - generic_item["_index"]) * 0.4)
        candidates.append((score, item))
    if not candidates:
        return None
    _, item = max(candidates, key=lambda pair: pair[0])
    return {"value": item["text"], **_evidence(item), "extraction_method": "standalone_product_heading"}


def _set_if_better(fields: dict[str, Any], key: str, value: Any) -> None:
    if value is not None:
        fields[key] = value


def enhance_extracted_fields(fields: dict[str, Any] | None) -> dict[str, Any]:
    """Correct structured declarations using explicit OCR evidence."""
    result = dict(fields or {})
    items = _items(result)
    if not items:
        return result

    generic = _extract_generic_name(items)
    product = _extract_product(items, generic)
    mrp = _extract_mrp(items)
    manufacture = _extract_date(items, MFG_LABEL_RE)
    use_by = _extract_date(items, USE_BY_LABEL_RE)
    unit_sale = _extract_unit_sale_price(items)

    _set_if_better(result, "product", product)
    _set_if_better(result, "common_generic_name", generic)
    _set_if_better(result, "mrp", mrp)
    _set_if_better(result, "manufacture_date", manufacture)
    _set_if_better(result, "use_by_date", use_by)
    _set_if_better(result, "unit_sale_price", unit_sale)
    return result


def add_enhanced_report_fields(report: dict[str, Any], fields: dict[str, Any]) -> dict[str, Any]:
    """Expose enhanced declarations in the canonical dashboard report."""
    extracted = report.setdefault("extracted_fields", {})
    for name in ("common_generic_name", "unit_sale_price", "use_by_date"):
        value = fields.get(name)
        if not isinstance(value, dict):
            continue
        extracted[name] = {
            "field_name": name,
            "present": True,
            "normalized_value": {
                key: value[key]
                for key in value
                if key not in {"confidence", "source_text", "source_box", "source_image", "label_text", "label_box"}
            },
            "raw_text": value.get("source_text"),
            "ocr_confidence": value.get("confidence"),
            "extraction_confidence": value.get("confidence"),
            "source_polygon": value.get("source_box"),
            "extraction_method": value.get("extraction_method"),
            "issues": [],
        }
    return report
