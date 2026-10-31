"""Semantic-first OCR field extraction for packaged-product declarations."""

from __future__ import annotations

import calendar
import math
import re
from datetime import datetime
from typing import Any, Iterable

from product_name_extractor import extract_product_identity


QUANTITY_UNITS = {
    "PCS": "N", "PC": "N", "PIECE": "N", "PIECES": "N",
    "UNIT": "N", "UNITS": "N", "NOS": "N", "NO": "N", "N": "N", "U": "N",
    "GM": "G", "GMS": "G", "G": "G", "KGS": "KG", "KG": "KG",
    "ML": "ML", "L": "L", "CM": "CM", "M": "M",
}
QUANTITY_LABEL_RE = re.compile(
    r"\b(?:NET\s*(?:QUANTITY|QTY|WT|WEIGHT|VOL|VOLUME)|QUANTITY|QTY)\b", re.I
)
MRP_LABEL_RE = re.compile(
    r"\b(?:M\s*\.?\s*R\s*\.?\s*P\.?|MAX(?:IMUM)?\s+RETAIL\s+PRICE|RETAIL\s+SALE\s+PRICE)\b", re.I
)
DATE_LABEL_RE = re.compile(r"\b(?:MFD|MFG|MTD|MANUFACTURE|MANUFACTURING|PACKED\s+ON|PKD)\b", re.I)
EXPIRY_LABEL_RE = re.compile(r"\b(?:EXP|EXPIRY|USE\s+(?:BY|BEFORE)|BEST\s+BEFORE)\b", re.I)
PRODUCT_LABEL_RE = re.compile(
    r"^(?:PRODUCT(?:\s+NAME)?|NAME\s+OF\s+COMMODITY|COMMON\s+NAME|GENERIC\s+NAME|CONTENTS)\s*[:;.-]*\s*(.*)$", re.I
)
LICENSE_RE = re.compile(
    r"\b(?:F\s*S\s*S\s*A\s*I|F\s*S\s*S\s*A|S\s*S\s*A\s*I)\s*(?:NO)?\b|\b(?:LIC|LICENSE)\s*(?:NO)?\b", re.I
)
ROLE_HEADER_RE = re.compile(
    r"\b(?:MANUFACTURED|MANUFACTURER|MFD|MFG|PACKED|PACKER|MARKETED|DISTRIBUTED|IMPORTED|IMPORTER)"
    r"(?:\s*/?\s*LICENSED)?(?:\s*(?:&|AND|/)\s*(?:MANUFACTURED|PACKED|MARKETED|DISTRIBUTED|IMPORTED))?\.?\s*(?:BY)?\b", re.I
)
COMPANY_INDICATORS = (
    "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INDUSTRIES", "FOODS",
    "ENTERPRISES", "COMPANY", "CORPORATION", "PRODUCTS", "TRADERS",
)
ADDRESS_WORD_RE = re.compile(
    r"\b(?:ROAD|ROOD|RD|STREET|SECTOR|NAGAR|LAYOUT|VILLAGE|GRAM(?:A)?|HOBLI|TALUK|DISTRICT|"
    r"INDUSTRIAL|AREA|PLOT|PHASE|BLOCK|FLOOR|BUILDING|CENTRA|STATE|INDIA)\b", re.I
)
SECTION_STOP_RE = re.compile(
    r"\b(?:MONTH\s*&\s*YEAR|NET\s*(?:QTY|QUANTITY|WT|WEIGHT|VOL|VOLUME)|M\s*\.?R\s*\.?P|BATCH|MFD|MFG|MTD|"
    r"MANUFACTURE\s+DATE|DATE\s+OF\s+MANUFACTURE|CUSTOMER\s+(?:CARE|COMPLAINTS?)|"
    r"CONSUMER\s+CARE|HELPLINE|CONTACT\s+US|"
    r"BEST\s+BEFORE|EXPIRY|SIZE|STYLE|COLOU?R|PRODUCT)\b", re.I
)
DECLARATION_SECTION_RE = re.compile(
    r"^\s*[^A-Za-z0-9]*(?:MONTH\s*&\s*YEAR|MANUFACTURE\s+DATE|DATE\s+OF\s+MANUFACTURE|MFD(?!\.?\s*BY)\b|MFG(?!\.?\s*BY)\b|"
    r"NET\s*(?:QUANTITY|QTY|WT|WEIGHT|VOL|VOLUME)\b|M\s*\.?R\s*\.?P\b|BATCH\b|STYLE\b|SIZE\b|COLOU?R\b|"
    r"PRODUCT\b|CUSTOMER\s+CARE\b|BEST\s+BEFORE\b|EXPIRY\b)", re.I
)
CARE_HEADER_RE = re.compile(
    r"\b(?:CUSTOMER\s+CARE|CONSUMER\s+CARE|CUSTOMER\s+COMPLAINTS?|FOR\s+CUSTOMER\s+COMPLAINTS?|"
    r"HELPLINE|CONTACT\s+US)\b", re.I
)


def normalize_text(text: Any) -> str:
    """Conservatively normalize OCR text while callers retain the raw value."""
    value = "" if text is None else str(text)
    value = re.sub(r"^[\s:;|]+", "", value.strip())
    value = re.sub(r"\s+", " ", value)
    units = "PCS|PC|PIECES?|UNITS?|NOS|N|U|KGS?|GMS?|G|ML|L|CM|M"
    value = re.sub(rf"(?<=\d)(?=(?:{units})\b)", " ", value, flags=re.I)
    return value


def clean_text(text: Any) -> str:
    return normalize_text(text)


def _bounds(box: Any) -> tuple[float, float, float, float]:
    if not box:
        return (0.0, 0.0, 0.0, 0.0)
    if isinstance(box[0], (list, tuple)):
        xs = [float(point[0]) for point in box]
        ys = [float(point[1]) for point in box]
        return min(xs), min(ys), max(xs), max(ys)
    if len(box) >= 4:
        x1, y1, x2, y2 = map(float, box[:4])
        return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)
    return (0.0, 0.0, 0.0, 0.0)


def box_center(box: Any) -> tuple[float, float]:
    x1, y1, x2, y2 = _bounds(box)
    return ((x1 + x2) / 2, (y1 + y2) / 2)


def box_width(box: Any) -> float:
    x1, _, x2, _ = _bounds(box)
    return x2 - x1


def box_height(box: Any) -> float:
    _, y1, _, y2 = _bounds(box)
    return y2 - y1


def _overlap(a1: float, a2: float, b1: float, b2: float) -> float:
    intersection = max(0.0, min(a2, b2) - max(a1, b1))
    denominator = max(1.0, min(a2 - a1, b2 - b1))
    return intersection / denominator


def horizontal_overlap(box_a: Any, box_b: Any) -> float:
    ax1, _, ax2, _ = _bounds(box_a)
    bx1, _, bx2, _ = _bounds(box_b)
    return _overlap(ax1, ax2, bx1, bx2)


def vertical_overlap(box_a: Any, box_b: Any) -> float:
    _, ay1, _, ay2 = _bounds(box_a)
    _, by1, _, by2 = _bounds(box_b)
    return _overlap(ay1, ay2, by1, by2)


def horizontal_distance(box_a: Any, box_b: Any) -> float:
    ax1, _, ax2, _ = _bounds(box_a)
    bx1, _, bx2, _ = _bounds(box_b)
    return max(0.0, max(ax1, bx1) - min(ax2, bx2))


def vertical_distance(box_a: Any, box_b: Any) -> float:
    _, ay1, _, ay2 = _bounds(box_a)
    _, by1, _, by2 = _bounds(box_b)
    return max(0.0, max(ay1, by1) - min(ay2, by2))


def same_row_score(box_a: Any, box_b: Any) -> float:
    return vertical_overlap(box_a, box_b)


def same_column_score(box_a: Any, box_b: Any) -> float:
    return horizontal_overlap(box_a, box_b)


def relative_direction(box_a: Any, box_b: Any) -> str:
    ax, ay = box_center(box_a)
    bx, by = box_center(box_b)
    if same_row_score(box_a, box_b) >= 0.45:
        return "right" if bx >= ax else "left"
    if same_column_score(box_a, box_b) >= 0.25:
        return "below" if by >= ay else "above"
    return "below-right" if by >= ay and bx >= ax else (
        "below-left" if by >= ay else ("above-right" if bx >= ax else "above-left")
    )


def _prepare_items(ocr_items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    prepared = []
    for index, item in enumerate(ocr_items or []):
        raw = "" if item.get("text") is None else str(item.get("text"))
        prepared.append({
            **item, "text": normalize_text(raw), "raw_text": raw,
            "normalized_text": normalize_text(raw),
            "confidence": float(item.get("confidence", 0.0) or 0.0),
            "box": item.get("box") or [0, index * 20, 100, (index + 1) * 20],
            "_index": index,
        })
    return prepared


def nearby_items(items: list[dict[str, Any]], target_index: int, max_distance: float = 180):
    target = items[target_index]
    candidates = []
    for item in items:
        if item is target:
            continue
        dx = horizontal_distance(target["box"], item["box"])
        dy = vertical_distance(target["box"], item["box"])
        if min(dx, dy) <= max_distance and math.hypot(dx, dy) <= max_distance * 1.75:
            alignment = max(same_row_score(target["box"], item["box"]), same_column_score(target["box"], item["box"]))
            candidates.append((dx + dy - alignment * 50, item))
    return [item for _, item in sorted(candidates, key=lambda pair: pair[0])]


def _spatial_score(label: dict[str, Any], candidate: dict[str, Any]) -> float:
    direction = relative_direction(label["box"], candidate["box"])
    row = same_row_score(label["box"], candidate["box"])
    column = same_column_score(label["box"], candidate["box"])
    dx = horizontal_distance(label["box"], candidate["box"])
    dy = vertical_distance(label["box"], candidate["box"])
    score = max(row * 24, column * 18)
    if direction in {"right", "below", "above", "left"}:
        score += 8
    score += max(0.0, 18.0 - (dx + dy) / 12.0)
    score += max(0.0, 10.0 - abs(candidate["_index"] - label["_index"]) * 1.6)
    return score


def _candidate_indices(items: list[dict[str, Any]], label_index: int, radius: int = 6):
    return (i for i in range(max(0, label_index - radius), min(len(items), label_index + radius + 1)) if i != label_index)


def _evidence(item: dict[str, Any], confidence: float | None = None) -> dict[str, Any]:
    evidence = {
        "confidence": round(item["confidence"] if confidence is None else confidence, 3),
        "source_text": item["raw_text"], "source_box": item["box"],
        "source_image": item.get("source_image"),
    }
    if isinstance(item.get("tokens"), list):
        evidence["tokens"] = item["tokens"]
    return evidence


def _parse_quantity(text: str) -> tuple[float, str] | None:
    if re.search(r"(?:₹|\bRS\.?\b|/-)", text, re.I) or re.search(r"\d{1,2}[-/]\d{1,2}[-/]\d{2,4}", text):
        return None
    pattern = re.compile(
        r"(?<![\d-])(\d+(?:\.\d+)?)\s*(PCS|PC|PIECES?|UNITS?|NOS|NO|N|U|KGS?|KG|GMS?|GM|G|ML|L|CM|M)\b", re.I
    )
    match = pattern.search(text)
    if not match:
        return None
    return float(match.group(1)), QUANTITY_UNITS[match.group(2).upper()]


_SPLIT_QUANTITY_RE = re.compile(
    r"^\s*(\d+(?:\.\d+)?)\s*(PCS|PC|PIECES?|UNITS?|NOS|NO|N|U|KGS?|KG|GMS?|GM|G|ML|L|CM|M)\s*[.;,:-]?\s*$",
    re.I,
)
_NON_QUANTITY_CONTEXT_RE = re.compile(
    r"(?:₹|\bRS\.?\b|\bINR\b|/-|\bMRP\b|\bPRICE\b|\bBATCH\b|\bLOT\b|"
    r"\bFSSAI\b|\bLIC(?:ENCE|ENSE)?\b|\bPIN\b|\bPHONE\b|\bMOB(?:ILE)?\b|"
    r"\bMFD\b|\bMFG\b|\bEXP(?:IRY)?\b|\bBEST\s+BEFORE\b|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})",
    re.I,
)


def is_standalone_quantity_candidate(text: str) -> bool:
    """Return whether a separate OCR line is unambiguously a quantity value."""
    normalized = normalize_text(text)
    return not _NON_QUANTITY_CONTEXT_RE.search(normalized) and bool(
        _SPLIT_QUANTITY_RE.fullmatch(normalized)
    )


def _quantity_layout_score(label: dict[str, Any], candidate: dict[str, Any]) -> tuple[float, str]:
    direction = relative_direction(label["box"], candidate["box"])
    row = same_row_score(label["box"], candidate["box"])
    column = same_column_score(label["box"], candidate["box"])
    dx = horizontal_distance(label["box"], candidate["box"])
    dy = vertical_distance(label["box"], candidate["box"])
    height = max(1.0, box_height(label["box"]), box_height(candidate["box"]))
    if row >= 0.45:
        layout = "same_row"
        priority = 48 if direction == "right" else 36
    elif column >= 0.25 and direction == "below":
        layout, priority = "below_label", 44
    elif column >= 0.25 and direction == "above":
        layout, priority = "above_label", 40
    else:
        layout, priority = "nearby", 12
    distance_penalty = (dx + dy) / height * 7.0
    alignment_bonus = max(row, column) * 24.0
    return priority + alignment_bonus - distance_penalty, layout


def _extract_quantity(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    scored = []
    for label_index, label in enumerate(items):
        if not QUANTITY_LABEL_RE.search(label["text"]):
            continue
        same_item_value = _parse_quantity(label["text"])
        if same_item_value:
            value, unit = same_item_value
            return {
                "value": value, "unit": unit, "source_layout": "same_item",
                "label_text": label["raw_text"], "label_box": label["box"],
                **_evidence(label),
            }
        for candidate in items:
            if candidate is label:
                continue
            if not is_standalone_quantity_candidate(candidate["text"]):
                continue
            if (
                horizontal_distance(label["box"], candidate["box"])
                + vertical_distance(label["box"], candidate["box"])
                > max(260.0, box_height(label["box"]) * 7.0)
            ):
                continue
            value = _parse_quantity(candidate["text"])
            if value:
                layout_score, layout = _quantity_layout_score(label, candidate)
                score = 80 + layout_score + candidate["confidence"] * 8
                scored.append((score, candidate, value, label, layout))
    if not scored:
        return None
    score, candidate, (value, unit), label, layout = max(scored, key=lambda entry: entry[0])
    confidence = min(candidate["confidence"], score / 140)
    return {
        "value": value, "unit": unit, "source_layout": layout,
        "label_text": label["raw_text"], "label_box": label["box"],
        **_evidence(candidate, confidence),
    }


def _parse_mrp(text: str) -> float | None:
    value_text = MRP_LABEL_RE.sub("", text, count=1).strip(" :;.-")
    if (
        _parse_quantity(value_text) is not None
        or LICENSE_RE.search(value_text)
        or re.search(r"\b(?:BATCH|FSSAI|USP|UNIT\s+SALE|PER\s+(?:G|KG|ML|L|UNIT))\b|/\s*(?:G|KG|ML|L)\b", value_text, re.I)
    ):
        return None
    if _parse_date(value_text) is not None:
        return None
    compact = re.sub(r"\s", "", value_text)
    digits = re.sub(r"\D", "", compact)
    if len(digits) >= 6 or re.fullmatch(r"20\d{2}", compact):
        return None
    match = re.search(r"(?:₹|\bRS\.?|\bINR)?\s*(\d{1,5}(?:\.\d{1,2})?)\s*(?:/-)?", value_text, re.I)
    if not match:
        return None
    through_price = re.sub(r"\s", "", value_text[:match.end()])
    if re.search(r"[A-Za-z]\d|\d[A-Za-z]", through_price) and not re.search(r"(?:RS|INR)", through_price, re.I):
        return None
    amount = float(match.group(1))
    return amount if 0 < amount < 100000 else None


def _has_ocr_currency_prefix(text: str) -> bool:
    """Accept a single corrupted non-ASCII currency glyph before an amount."""
    return bool(re.fullmatch(
        r"\s*[^A-Za-z0-9\s./-]{1,3}\s*\d{1,5}(?:\.\d{1,2})?\s*(?:/-)?\s*",
        text,
    ))


def _includes_all_taxes(text: str) -> bool:
    compact = re.sub(r"[\W_]+", "", text).lower()
    return "inclusiveofalltaxes" in compact or "inclofalltaxes" in compact


def _extract_mrp(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    full_text = "\n".join(item["text"] for item in items)
    scored = []
    for label_index, label in enumerate(items):
        if not MRP_LABEL_RE.search(label["text"]):
            continue
        same_item_amount = _parse_mrp(label["text"])
        if same_item_amount is not None:
            return {
                "currency": "INR", "value": same_item_amount,
                "inclusive_of_all_taxes": _includes_all_taxes(full_text),
                **_evidence(label),
            }
        candidate_indices = list(_candidate_indices(items, label_index, radius=8))
        candidate_indices.extend(
            index for index, item in enumerate(items)
            if item.get("recovered_from_declaration_crop") and index not in candidate_indices
        )
        for candidate_index in candidate_indices:
            candidate = items[candidate_index]
            candidate_text = candidate["text"].strip()
            if (
                not re.search(r"₹|\bRS\.?\b|\bINR\b|/-", candidate_text, re.I)
                and not re.fullmatch(r"\d{1,5}(?:\.\d{1,2})?", candidate_text)
                and not _has_ocr_currency_prefix(candidate_text)
            ):
                continue
            amount = _parse_mrp(candidate["text"])
            if amount is None:
                continue
            score = 85 + _spatial_score(label, candidate) + candidate["confidence"] * 8
            if re.search(r"₹|\bRS\.?|\bINR|/-", candidate["text"], re.I):
                score += 18
            if amount < 10 and not re.search(r"₹|RS|INR|/-", candidate["text"], re.I):
                score -= 24
            scored.append((score, candidate, amount))
    if not scored:
        return None
    score, candidate, amount = max(scored, key=lambda entry: entry[0])
    return {
        "currency": "INR", "value": amount,
        "inclusive_of_all_taxes": _includes_all_taxes(full_text),
        **_evidence(candidate, min(candidate["confidence"], score / 145)),
    }


MONTHS = {name.upper(): number for number, name in enumerate(calendar.month_name) if name}
MONTHS.update({name.upper(): number for number, name in enumerate(calendar.month_abbr) if name})


def _parse_date(text: str) -> tuple[str, str, str] | None:
    numeric = re.search(r"\b(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})\b", text)
    if numeric:
        day, month, year = map(int, numeric.groups())
        year += 2000 if year < 70 else (1900 if year < 100 else 0)
        try:
            normalized = datetime(year, month, day).strftime("%Y-%m-%d")
        except ValueError:
            return None
        return numeric.group(0), normalized, "manufacture_date"
    compact_numeric = re.search(r"(?<!\d)([0-3]\d)(0?[1-9]|1[0-2])[-/](\d{2})(?!\d)", text)
    if compact_numeric:
        day, month, year = map(int, compact_numeric.groups())
        year += 2000 if year < 70 else 1900
        try:
            normalized = datetime(year, month, day).strftime("%Y-%m-%d")
        except ValueError:
            return None
        return compact_numeric.group(0), normalized, "manufacture_date"
    month_year = re.search(r"\b(" + "|".join(MONTHS) + r")\s+(\d{4})\b", text, re.I)
    if month_year:
        month = MONTHS[month_year.group(1).upper()]
        return month_year.group(0), f"{int(month_year.group(2)):04d}-{month:02d}", "manufacture_month_year"
    numeric_month = re.search(r"\b(0?[1-9]|1[0-2])[-/](\d{4})\b", text)
    if numeric_month:
        return numeric_month.group(0), f"{numeric_month.group(2)}-{int(numeric_month.group(1)):02d}", "manufacture_month_year"
    short_numeric_month = re.search(r"\b(0?[1-9]|1[0-2])[-/](\d{2})\b", text)
    if short_numeric_month:
        year = int(short_numeric_month.group(2))
        year += 2000 if year < 70 else 1900
        return short_numeric_month.group(0), f"{year:04d}-{int(short_numeric_month.group(1)):02d}", "manufacture_month_year"
    return None


def _extract_manufacture_date(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    scored = []
    for label_index, label in enumerate(items):
        combined_declaration = bool(
            DATE_LABEL_RE.search(label["text"])
            and EXPIRY_LABEL_RE.search(label["text"])
            and re.search(r"\b(?:BATCH|B\s*\.\s*NO)\b", label["text"], re.I)
        )
        if not DATE_LABEL_RE.search(label["text"]) or (EXPIRY_LABEL_RE.search(label["text"]) and not combined_declaration):
            continue
        candidate_indices = [label_index, *_candidate_indices(items, label_index, radius=5)]
        if combined_declaration:
            candidate_indices.extend(index for index in range(len(items)) if index not in candidate_indices)
        candidate_indices.extend(
            index for index, item in enumerate(items)
            if item.get("recovered_from_declaration_crop") and index not in candidate_indices
        )
        for candidate_index in candidate_indices:
            candidate = items[candidate_index]
            parsed = _parse_date(candidate["text"])
            if parsed:
                score = 80 + _spatial_score(label, candidate) + candidate["confidence"] * 8
                if candidate_index == label_index:
                    score += 25
                scored.append((score, candidate, parsed))
    if not scored:
        return None
    score, candidate, (raw, normalized, date_type) = max(scored, key=lambda entry: entry[0])
    return {"raw": raw, "normalized": normalized, "type": date_type,
            **_evidence(candidate, min(candidate["confidence"], score / 140))}


def _roles_for_header(text: str) -> set[str]:
    made_in_by = bool(re.search(r"\bMADE\s+IN\s+[A-Z ]+\s+BY\s*:?\s*$", text, re.I))
    if DECLARATION_SECTION_RE.search(text) or (not ROLE_HEADER_RE.search(text) and not made_in_by):
        return set()
    upper = text.upper()
    roles = set()
    if made_in_by: roles.add("manufacturer")
    if re.search(r"MANUFACT|\bMF[DG]\b", upper): roles.add("manufacturer")
    if re.search(r"PACKED|PACKER", upper): roles.add("packer")
    if re.search(r"MARKETED|DISTRIBUTED", upper): roles.add("marketer")
    if re.search(r"IMPORTED|IMPORTER", upper): roles.add("importer")
    return roles


def _is_noise(item: dict[str, Any]) -> bool:
    text = item["text"]
    letters = re.sub(r"[^A-Za-z]", "", text)
    if not text:
        return True
    if len(letters) <= 4 and letters.isalpha():
        has_signal = any(signal in text.upper() for signal in COMPANY_INDICATORS) or ADDRESS_WORD_RE.search(text)
        return not has_signal and (item["confidence"] < 0.8 or len(letters) <= 2 or text.upper() == "MORC")
    return False


def _looks_like_company(text: str) -> bool:
    return any(re.search(rf"\b{re.escape(indicator)}\b", text, re.I) for indicator in COMPANY_INDICATORS)


def _looks_like_address(text: str) -> bool:
    return bool(ADDRESS_WORD_RE.search(text) or re.search(r"\b\d{6}\b", text) or re.search(r"\d+\s*[/,-]", text))


def _split_name_address(lines: list[dict[str, Any]]) -> tuple[str, str, list[dict[str, Any]]] | None:
    useful = [line for line in lines if not _is_noise(line) and not LICENSE_RE.search(line["text"])]
    if not useful:
        return None
    name_index = next((i for i, line in enumerate(useful) if _looks_like_company(line["text"])), 0)
    name = useful[name_index]["text"].strip(" ,")
    address_parts: list[str] = []
    consumed = [useful[name_index]]
    # A company name can wrap before the line containing its legal suffix.
    if name_index > 0:
        prefix = useful[name_index - 1]["text"]
        if (
            re.search(r"[A-Za-z]{3}", prefix)
            and
            not _looks_like_address(prefix)
            and not ROLE_HEADER_RE.search(prefix)
            and not DECLARATION_SECTION_RE.search(prefix)
        ):
            name = f"{prefix.strip(' ,')} {name}"
            consumed.insert(0, useful[name_index - 1])
    next_index = name_index + 1
    if next_index < len(useful) and not re.search(r"\b(?:LTD|LIMITED|LLP)\b", name, re.I):
        continuation = useful[next_index]["text"]
        suffix = re.match(r"^((?:PVT\.?\s*)?(?:LTD\.?|LIMITED|LLP))\s*[,;:-]*\s*(.*)$", continuation, re.I)
        if suffix:
            name = f"{name} {suffix.group(1)}".strip()
            if suffix.group(2): address_parts.append(suffix.group(2))
            consumed.append(useful[next_index])
            next_index += 1
    split = re.search(r"\s*[,;]\s*(?=\d|(?:NO\.?|PLOT|SECTOR)\b)", name, re.I)
    if split:
        address_parts.insert(0, name[split.end():].strip())
        name = name[:split.start()].strip(" ,")
    for line in useful[next_index:]:
        if ROLE_HEADER_RE.search(line["text"]) or SECTION_STOP_RE.search(line["text"]) or LICENSE_RE.search(line["text"]):
            break
        if re.search(r"\b(?:TM\s+OWNERS?|REGN\.?\s+NO|M\s*\.\s*L\s*\.\s*NO|MINIMUM\s+THICKNESS)\b", line["text"], re.I):
            break
        if _looks_like_address(line["text"]) or address_parts:
            address_parts.append(line["text"])
            consumed.append(line)
    return name, " ".join(address_parts).strip(), consumed


def _extract_organizations(items: list[dict[str, Any]]) -> dict[str, dict[str, Any] | None]:
    result = {"manufacturer": None, "marketer": None, "packer": None, "importer": None}
    for header_index, item in enumerate(items):
        roles = _roles_for_header(item["text"])
        if not roles:
            continue
        combined_roles = set(roles)
        cursor = header_index + 1
        inline_text = ROLE_HEADER_RE.sub("", item["text"], count=1).strip(" :;/&-")
        while cursor < len(items) and cursor <= header_index + 2 and _roles_for_header(items[cursor]["text"]):
            combined_roles.update(_roles_for_header(items[cursor]["text"]))
            cursor += 1
        section_lines = []
        if inline_text:
            section_lines.append({**item, "text": inline_text, "normalized_text": inline_text})
        for candidate in items[cursor:cursor + 12]:
            if ROLE_HEADER_RE.search(candidate["text"]) or SECTION_STOP_RE.search(candidate["text"]):
                break
            if LICENSE_RE.search(candidate["text"]):
                break
            section_lines.append(candidate)
        parsed = _split_name_address(section_lines)
        if not parsed:
            continue
        name, address, source_items = parsed
        if not name or (not _looks_like_company(name) and len(name) < 4):
            continue
        organization = {
            "name": name, "address": address,
            "confidence": round(min((line["confidence"] for line in source_items), default=0.0), 3),
            "source_lines": [line["raw_text"] for line in source_items],
        }
        for role in combined_roles:
            if result[role] is None: result[role] = organization.copy()
    return result


def _extract_consumer_care(items: list[dict[str, Any]]) -> dict[str, Any]:
    email_re = re.compile(r"[A-Za-z0-9._%+-]+\s*@\s*[A-Za-z0-9.-]+\s*\.\s*[A-Za-z]{2,}")
    phone_re = re.compile(
        r"(?<!\d)(?:"
        r"(?:\+?\s*91[\s().-]*)?1800(?:[\s.-]*\d){7,9}|"
        r"(?:\+?\s*91[\s().-]*)?[6-9]\d(?:[\s.-]*\d){8}|"
        r"(?:\+?\s*91[\s().-]*)?\(?0\d{1,4}\)?[\s.-]*\d(?:[\s.-]*\d){5,7}"
        r")(?![\s.-]*\d)"
    )
    care_indices = [i for i, item in enumerate(items) if CARE_HEADER_RE.search(item["text"])]
    search_items = [entry for index in care_indices for entry in items[index:index + 14]] if care_indices else items
    phone = email = None
    phone_item = email_item = None
    for item in search_items:
        if email is None:
            match = email_re.search(item["text"])
            if match:
                email = re.sub(r"\s", "", match.group(0)); email_item = item
        if phone is None and not LICENSE_RE.search(item["text"]):
            match = phone_re.search(item["text"])
            if match:
                phone = match.group(0).strip(); phone_item = item
    result = {"phone": phone, "email": email}
    if phone_item or email_item:
        result.update({
            "confidence": round(min((entry["confidence"] for entry in (phone_item, email_item) if entry), default=0.0), 3),
            "source_lines": [entry["raw_text"] for entry in (phone_item, email_item) if entry],
        })
    return result


def _extract_labeled_text(items: list[dict[str, Any]], label_re: re.Pattern[str]) -> tuple[str | None, dict[str, Any] | None]:
    for index, item in enumerate(items):
        match = label_re.match(item["text"])
        if not match: continue
        inline = match.group(1).strip(" :;.-")
        if inline: return inline, item
        for candidate_index in (index + 1, index - 1):
            if 0 <= candidate_index < len(items):
                candidate = items[candidate_index]
                if candidate["text"] and not SECTION_STOP_RE.search(candidate["text"]):
                    return candidate["text"], candidate
    return None, None


def _is_declaration_item(text: str) -> bool:
    return bool(
        QUANTITY_LABEL_RE.search(text)
        or MRP_LABEL_RE.search(text)
        or DATE_LABEL_RE.search(text)
        or EXPIRY_LABEL_RE.search(text)
        or PRODUCT_LABEL_RE.match(text)
        or ROLE_HEADER_RE.search(text)
        or CARE_HEADER_RE.search(text)
        or LICENSE_RE.search(text)
        or re.search(
            r"^\s*(?:BATCH|ADDRESS|E-?MAIL|MADE\s+IN|COUNTRY\s+OF\s+ORIGIN)\b",
            text,
            re.I,
        )
    )


def _is_product_candidate_line(item: dict[str, Any]) -> bool:
    text = item["text"].strip()
    if item["confidence"] < 0.85 or _is_declaration_item(text):
        return False
    if _looks_like_company(text) or _looks_like_address(text):
        return False
    if re.search(r"(?:₹|\d|@|https?://|www\.)", text, re.I):
        return False
    if not re.fullmatch(r"[A-Za-z][A-Za-z &'’.\-]{2,49}", text):
        return False
    return len(text.split()) <= 5


def _infer_unlabeled_product(items: list[dict[str, Any]]) -> str | None:
    """Conservatively infer a two-line product heading before declarations."""
    declaration_index = next(
        (index for index, item in enumerate(items) if _is_declaration_item(item["text"])),
        None,
    )
    if declaration_index != 2:
        return None

    first, second = items[:2]
    if not (_is_product_candidate_line(first) and _is_product_candidate_line(second)):
        return None
    maximum_gap = max(box_height(first["box"]), box_height(second["box"]), 1.0) * 1.5
    if vertical_distance(first["box"], second["box"]) > maximum_gap:
        return None
    if same_column_score(first["box"], second["box"]) < 0.25:
        return None
    return f"{first['text']} {second['text']}"


def extract_company_section(ocr_items, section_headers, start_index=None):
    """Backward-compatible organization helper."""
    items = _prepare_items(ocr_items)
    headers = tuple(header.upper() for header in section_headers)
    if start_index is None:
        start_index = next((i for i, item in enumerate(items) if any(header in item["text"].upper() for header in headers)), None)
    if start_index is None or not 0 <= start_index < len(items): return None
    parsed = _split_name_address(items[start_index + 1:start_index + 12])
    if not parsed: return None
    name, address, source_items = parsed
    return {"name": name, "address": address,
            "confidence": round(min((item["confidence"] for item in source_items), default=0.0), 3),
            "source_lines": [item["raw_text"] for item in source_items]}


def extract_fields(ocr_items):
    items = _prepare_items(ocr_items)
    organizations = _extract_organizations(items)
    identity = extract_product_identity(items)
    product_name = identity["product_name"]
    product = product_name.get("value") if isinstance(product_name, dict) else None
    full_text = "\n".join(item["text"] for item in items)
    country_match = re.search(r"(?:MADE\s+IN|COUNTRY\s+OF\s+ORIGIN\s*[:\-]?)\s+([A-Z][A-Z ]{1,30})", full_text, re.I)
    country = re.sub(r"\s+BY\s*$", "", country_match.group(1), flags=re.I).strip().title() if country_match else None
    result = {
        # Keep the legacy raw extractor shape; the enhancement pass replaces
        # this with the evidence-bearing commodity field from the same identity.
        "product": product, "product_name": product_name, "brand_name": identity["brand_name"],
        "product_name_status": identity["status"], "colour": None,
        "net_quantity": _extract_quantity(items), "size": None,
        "mrp": _extract_mrp(items), "manufacture_date": _extract_manufacture_date(items),
        **organizations, "consumer_care": _extract_consumer_care(items), "country_of_origin": country,
        "ocr_evidence": [
            {"raw_text": item["raw_text"], "normalized_text": item["normalized_text"],
             "confidence": item["confidence"], "box": item["box"], "source_image": item.get("source_image"),
             **({"recovered_from_declaration_crop": True} if item.get("recovered_from_declaration_crop") else {})}
            for item in items
        ],
    }
    result["colour"], _ = _extract_labeled_text(items, re.compile(r"^COLOU?R\s*[:;.-]*\s*(.*)$", re.I))
    result["size"], _ = _extract_labeled_text(items, re.compile(r"^SIZE\s*[:;.-]*\s*(.*)$", re.I))
    return result


if __name__ == "__main__":
    print("Field extractor ready.")
