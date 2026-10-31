"""Deterministic semantic extraction from already-detected OCR evidence."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any

from product_name_extractor import extract_product_identity

MRP_LABEL = re.compile(r"\b(?:M\s*\.?\s*R\s*\.?\s*P\.?|MAX(?:IMUM)?\s+RETAIL\s+PRICE|RETAIL\s+SALE\s+PRICE)\b", re.I)
MFG_LABEL = re.compile(r"\b(?:MFG\.?(?:\s*DATE)?|MFD\.?(?:\s*DATE)?|MANUFACTURE(?:D)?\s*DATE|DATE\s+OF\s+MANUFACTURE|MONTH\s*&\s*YEAR\s+OF\s+MANUFACTURE|PACKED\s+ON|PKD)\b", re.I)
USE_BY_LABEL = re.compile(r"\b(?:USE\s+(?:BY|BEFORE)|BEST\s+BEFORE|EXP(?:IRY)?)\b", re.I)
GENERIC_LABEL = re.compile(r"^\s*(?:COMMON\s+(?:OR\s+)?GENERIC\s+NAME|GENERIC\s+NAME|COMMON\s+NAME|NAME\s+OF\s+COMMODITY)\s*[:;.-]*\s*(.*)$", re.I)
UNIT_PRICE = re.compile(r"\b(?:USP|UNIT\s+SALE\s+PRICE)\b|\bPER\s+(?:G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT)\b", re.I)
PRICE = re.compile(r"(?:₹|\bRS\.?\b|\bINR\b)?\s*(\d{1,6}(?:\.\d{1,2})?)\s*(?:/-)?", re.I)
DATE = re.compile(r"\b(\d{1,2})\s*[-/]\s*(\d{1,2})\s*[-/]\s*(\d{2,4})\b|\b(\d{1,2})\s*[-/]\s*(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\s*[-/]?\s*(\d{4})\b|\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\s+(\d{4})\b", re.I)
MONTHS = {name: number for number, name in enumerate(("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"), 1)}

NOISE = re.compile(r"\b(?:INGREDIENTS?|NUTRITIONAL\s+INFORMATION|SERVING\s+SIZE|PER\s+100\s+G|PER\s+SERVE|KEEP\s+YOUR\s+CITY\s+CLEAN|PLEASE\s+RECYCLE|ILLUSTRATIVE\s+PURPOSE|FSSAI|LIC\.?\s*NO|BATCH\s+NO|TRANS\s+FAT|CHOLESTEROL|SODIUM|CARBOHYDRATE|PROTEIN|TOTAL\s+SUGARS?|ADDED\s+SUGARS?|TOTAL\s+FAT|SATURATED\s+FAT|ENERGY\s*\(\s*KCAL\s*\)|www\.)\b", re.I)
PRODUCT_SLOGAN_START = re.compile(r"^(?:FOR|WITH|THE|NEW|NOW|MADE|RICH|REAL|GOOD|GREAT|TASTY|ORIGINAL|SINCE|NO\.?\s*1|NUMBER\s*1)\b", re.I)


def _items(fields: dict[str, Any]) -> list[dict[str, Any]]:
    items = []
    for index, raw in enumerate(fields.get("ocr_evidence") or []):
        if not isinstance(raw, dict):
            continue
        text = str(raw.get("normalized_text") or raw.get("raw_text") or "").strip()
        if text:
            items.append({**raw, "text": text, "_index": index, "confidence": float(raw.get("confidence", 0) or 0)})
    return items


def _ev(item: dict[str, Any], method: str, confidence: float | None = None) -> dict[str, Any]:
    return {"confidence": round(item["confidence"] if confidence is None else confidence, 3), "source_text": item.get("raw_text", item["text"]), "source_box": item.get("box"), "source_image": item.get("source_image"), "extraction_method": method}


def _date_value(text: str) -> tuple[str, str] | None:
    compact = re.search(r"(?<!\d)([0-3]\d)(0?[1-9]|1[0-2])[-/](\d{2})(?!\d)", text)
    if compact:
        day, month, year = map(int, compact.groups())
        year += 2000 if year < 70 else 1900
        try:
            return compact.group(0), datetime(year, month, day).strftime("%Y-%m-%d")
        except ValueError:
            return None
    short_month = re.search(r"(?:^|[#,.;])\s*(0?[1-9]|1[0-2])[-/](\d{2})(?!\d)", text)
    if short_month:
        year = int(short_month.group(2))
        year += 2000 if year < 70 else 1900
        return short_month.group(0).lstrip("#,.; "), f"{year:04d}-{int(short_month.group(1)):02d}"
    match = DATE.search(text)
    if not match:
        return None
    g = match.groups()
    try:
        if g[0]:
            year = int(g[2]); year += 2000 if year < 70 else 1900 if year < 100 else 0
            return match.group(0), datetime(year, int(g[1]), int(g[0])).strftime("%Y-%m-%d")
        if g[3]:
            return match.group(0), datetime(int(g[5]), MONTHS[g[4].upper()[:3]], int(g[3])).strftime("%Y-%m-%d")
        return match.group(0), f"{int(g[7]):04d}-{MONTHS[g[6].upper()[:3]]:02d}"
    except (TypeError, ValueError, KeyError):
        return None


def _extract_labeled_date(items: list[dict[str, Any]], label_re: re.Pattern[str]) -> dict[str, Any] | None:
    for i, label in enumerate(items):
        if not label_re.search(label["text"]):
            continue
        combined = bool(MFG_LABEL.search(label["text"]) and USE_BY_LABEL.search(label["text"]))
        candidate_indices = list(range(len(items))) if combined else [
            j for offset in range(0, 8)
            for j in ([i] if offset == 0 else (i - offset, i + offset))
            if 0 <= j < len(items)
        ]
        candidates = []
        for j in candidate_indices:
            candidate = items[j]
            if label_re is MFG_LABEL and USE_BY_LABEL.search(candidate["text"]):
                continue
            if label_re is USE_BY_LABEL and MFG_LABEL.search(candidate["text"]):
                continue
            parsed = _date_value(candidate["text"])
            if not parsed:
                continue
            raw, normalized = parsed
            score = candidate["confidence"] * 10 - abs(j - i) * (0.1 if combined else 1.0)
            if combined and label_re is MFG_LABEL:
                score += 15 if re.search(r"\d{2,5}\.\d{2}\s*[,.;#]", candidate["text"]) else 0
                score += 4 if len(normalized) == 7 else 0
            if combined and label_re is USE_BY_LABEL:
                score += 15 if len(normalized) == 10 else 0
                score += 4 if re.search(r"[A-Za-z]+\d+", candidate["text"]) else 0
            candidates.append((score, candidate, raw, normalized))
        if candidates:
            _, candidate, raw, normalized = max(candidates, key=lambda entry: entry[0])
            return {"raw": raw, "normalized": normalized, "type": "expiry_date" if label_re is USE_BY_LABEL else "manufacture_date", "label_text": label["raw_text"], "label_box": label.get("box"), **_ev(candidate, "explicit_date_label_semantic_window")}
    return None


def _extract_mrp(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    for i, label in enumerate(items):
        if not MRP_LABEL.search(label["text"]):
            continue
        inline = MRP_LABEL.sub("", label["text"], count=1)
        match = PRICE.search(inline)
        if match and not UNIT_PRICE.search(inline) and "%" not in inline:
            amount = float(match.group(1))
            if amount > 0:
                return {"currency": "INR", "value": amount, "inclusive_of_all_taxes": bool(re.search(r"(?:INCLUSIVE|INCL\.?\s*OF)\s+ALL\s+TAXES", label["text"], re.I)), **_ev(label, "explicit_mrp_label")}

        candidates = []
        for distance in range(1, 8):
            for j in (i - distance, i + distance):
                if not 0 <= j < len(items):
                    continue
                candidate = items[j]
                text = candidate["text"].strip()
                if UNIT_PRICE.search(text) or "%" in text or re.search(r"\b(?:PER|USP|UNIT\s+SALE)\b", text, re.I):
                    continue
                match = PRICE.fullmatch(text)
                if not match:
                    continue
                amount = float(match.group(1))
                if amount <= 0:
                    continue
                score = 100 - distance * 12 + candidate["confidence"] * 10
                if j == i - 1: score += 18
                if j == i + 1: score += 14
                if re.search(r"₹|\bRS\.?\b|\bINR\b|/-", text, re.I): score += 25
                if re.fullmatch(r"\d{1,6}(?:\.\d{1,2})?", text): score += 8
                if re.fullmatch(r"\d{1,6}\.\d{2}", text): score += 6
                candidates.append((score, candidate, amount))
        if candidates:
            _, candidate, amount = max(candidates, key=lambda x: x[0])
            return {"currency": "INR", "value": amount, "inclusive_of_all_taxes": False, "label_text": label["raw_text"], "label_box": label.get("box"), **_ev(candidate, "explicit_mrp_label_adjacent_amount")}
    return None


def _extract_unit_sale_price(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    for i, item in enumerate(items):
        if not UNIT_PRICE.search(item["text"]):
            continue
        for j in (i, i - 1, i + 1, i - 2, i + 2):
            if not 0 <= j < len(items):
                continue
            candidate = items[j]
            match = PRICE.search(candidate["text"])
            unit = re.search(r"\bPER\s+(G|KG|ML|L|CM|M|NUMBER|NO\.?|PIECE|UNIT)\b", candidate["text"], re.I)
            if match and unit:
                return {"currency": "INR", "value": float(match.group(1)), "unit": unit.group(1).upper(), **_ev(candidate, "explicit_unit_sale_price")}
    return None


def _clean_candidate(text: str) -> bool:
    text = text.strip(" .:-")
    if re.search(r"\b(?:MANUFACTURED|MANUFACTURER|PACKED|PACKER|IMPORTED|IMPORTER|MARKETED|MARKETER|CONSUMER|CUSTOMER|CARE|ADDRESS)\b", text, re.I) or NOISE.search(text) or PRODUCT_SLOGAN_START.search(text) or re.search(r"[₹@\d]|https?://|www\.", text, re.I):
        return False
    if not re.fullmatch(r"[A-Za-z][A-Za-z &'’\-]{2,59}", text):
        return False
    word_count = len(text.split())
    # Product names are often a single branded token such as "Parle-G" or
    # "Surf-excel". Keep those when they have a distinctive hyphen, while
    # rejecting short marketing fragments such as "for Genius".
    if word_count == 1:
        return bool("-" in text or len(text) >= 4)
    return 2 <= word_count <= 5


def _extract_product(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates = []
    for item in items:
        text = item["text"].strip(" .:-")
        if item["confidence"] < 0.88 or not _clean_candidate(text):
            continue
        if text.isupper() or text.islower():
            continue
        score = item["confidence"] * 10
        score += 12 * sum(word[:1].isupper() for word in text.split()) / max(1, len(text.split()))
        if "-" in text:
            score += 10
        if re.search(r"\b(?:biscuit|shampoo|soap|toothpaste|tea|flour|rice|oil|juice|drink|cream|lotion|milk|salt|honey)\b", text, re.I):
            score += 12
        # Standalone OCR headings are much more likely to be the actual product
        # name than small marketing slogans, so keep a mild confidence margin.
        candidates.append((score, item))
    if not candidates:
        return None
    _, item = max(candidates, key=lambda x: x[0])
    return {"value": item["text"], **_ev(item, "standalone_product_heading")}


def _extract_generic_name(items: list[dict[str, Any]], product: dict[str, Any] | None) -> dict[str, Any] | None:
    for i, item in enumerate(items):
        match = GENERIC_LABEL.match(item["text"])
        if not match:
            continue
        inline = match.group(1).strip(" :;.-")
        if inline:
            return {"value": inline, **_ev(item, "explicit_generic_name_label")}
        for j in (i + 1, i - 1):
            if 0 <= j < len(items) and items[j]["text"]:
                return {"value": items[j]["text"], **_ev(items[j], "explicit_generic_name_label")}
    product_words = set()
    if product:
        product_words = {re.sub(r"[^a-z]", "", word.lower()).rstrip("s") for word in product["value"].split()}
    candidates = []
    for item in items:
        text = item["text"].strip(" .:-")
        if item["confidence"] < 0.90 or not re.fullmatch(r"[A-Z][A-Z -]{3,24}", text) or NOISE.search(text):
            continue
        stem = re.sub(r"[^a-z]", "", text.lower()).rstrip("s")
        if stem in product_words:
            candidates.append(item)
    if candidates:
        item = max(candidates, key=lambda x: x["confidence"])
        return {"value": item["text"].title(), **_ev(item, "commodity_heading_semantic_match")}
    return None


def enhance_extracted_fields(fields: dict[str, Any] | None) -> dict[str, Any]:
    result = dict(fields or {})
    items = _items(result)
    if not items:
        return result
    identity = extract_product_identity(items)
    product_name = identity["product_name"]
    product = identity["product"]
    generic = _extract_generic_name(items, product_name)
    mrp = _extract_mrp(items)
    if mrp:
        full_text = "\n".join(item["text"] for item in items)
        if re.search(r"(?:INCLUSIVE\s+OF|INCL\.?\s+OF)\s+ALL\s+TAXES", full_text, re.I):
            mrp["inclusive_of_all_taxes"] = True
    if identity["brand_name"] is not None:
        result["brand_name"] = identity["brand_name"]
    result["product_name_status"] = identity["status"]
    if product_name is not None:
        result["product_name"] = product_name
    if product is not None:
        result["product"] = product
    candidates = {"common_generic_name": generic, "mrp": mrp, "manufacture_date": _extract_labeled_date(items, MFG_LABEL), "use_by_date": _extract_labeled_date(items, USE_BY_LABEL), "unit_sale_price": _extract_unit_sale_price(items)}
    for key, value in candidates.items():
        if value is None:
            continue
        current = result.get(key)
        if current:
            # Preserve primary declarations when they already carry reliable evidence.
            if not isinstance(current, dict) or current.get("confidence", 0) >= 0.82:
                continue
            if value.get("confidence", 0) <= current.get("confidence", 0):
                continue
        result[key] = value
    return result


def add_enhanced_report_fields(report: dict[str, Any], fields: dict[str, Any]) -> dict[str, Any]:
    extracted = report.setdefault("extracted_fields", {})
    for name in ("common_generic_name", "unit_sale_price", "use_by_date"):
        value = fields.get(name)
        if not isinstance(value, dict):
            continue
        extracted[name] = {
            "field_name": name,
            "present": True,
            "normalized_value": {k: v for k, v in value.items() if k not in {"confidence", "source_text", "source_box", "source_image", "label_text", "label_box"}},
            "raw_text": value.get("source_text"),
            "ocr_confidence": value.get("confidence"),
            "extraction_confidence": value.get("confidence"),
            "source_polygon": value.get("source_box"),
            "extraction_method": value.get("extraction_method"),
            "issues": [],
        }
    return report
