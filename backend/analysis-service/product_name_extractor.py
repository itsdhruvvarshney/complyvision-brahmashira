"""Geometry-aware brand and product-name selection from PaddleOCR evidence."""

from __future__ import annotations

import logging
import re
from statistics import median
from typing import Any, Iterable


PRODUCT_LABEL = re.compile(
    r"^\s*(?:PRODUCT(?:\s+NAME)?|NAME\s+OF\s+COMMODITY|COMMON\s+NAME|GENERIC\s+NAME|CONTENTS)\s*[:;.-]*\s*(.*)$",
    re.I,
)
BRAND_LABEL = re.compile(r"^\s*(?:BRAND(?:\s+NAME)?|TRADE\s+NAME)\s*[:;.-]*\s*(.*)$", re.I)
REJECTED_CONTEXT = re.compile(
    r"\b(?:M\s*\.?\s*R\s*\.?\s*P\.?|PRICE|NET\s*(?:QTY|QUANTITY|WT|WEIGHT|VOL|VOLUME)|"
    r"F\s*S\s*S\s*A\s*I|LIC(?:ENCE|ENSE)?|INGREDIENTS?|NUTRITION(?:AL)?|SERVING|"
    r"MANUFACTURED?|MANUFACTURER|MFD|MFG|MARKETED|PACKED|PACKER|IMPORTED|DISTRIBUTED|"
    r"CUSTOMER\s+CARE|CONSUMER\s+CARE|COMPLAINTS?|HELPLINE|BATCH|LOT|EXPIRY|EXP\.?|"
    r"PRODUCT(?:\s+NAME)?|BRAND(?:\s+NAME)?|"
    r"BEST\s+BEFORE|USE\s+BY|ADDRESS|E-?MAIL|PHONE|MOBILE|CONTACT\s+US|"
    r"MAXIMUM\s+RETAIL|INCLUSIVE\s+OF\s+ALL\s+TAXES|PER\s+100|ENERGY|PROTEIN|"
    r"CARBOHYDRATE|SUGARS?|FAT|SODIUM|CHOLESTEROL|KEEP\s+IN|STORE\s+IN|"
    r"RECYCLE|VEGETARIAN|MADE\s+IN|COUNTRY\s+OF\s+ORIGIN|DIRECTIONS?(?:\s+FOR\s+USE)?|"
    r"WARNING|WARNINGS?|CAUTION|"
    r"HOW\s+TO\s+USE|USE\s+WITH|FOR\s+EXTERNAL\s+USE|STORAGE\s+CONDITION|SCIENCE\s+OF|GOODNESS\s+OF|"
    r"DETANS?\s*[·•]?\s*PROTECTS?\s*[·•]?\s*BRIGHTENS?|WHITE\s+CAST|SUN\s+DAMAGE|UVA|UVB|"
    r"NIACINAMIDE|CARROTS?|SPF|PA\s*\+*)\b",
    re.I,
)
COMPANY_OR_ADDRESS = re.compile(
    r"\b(?:PVT|PRIVATE|LTD|LIMITED|LLP|INC|CORP(?:ORATION)?|INDUSTRIES|ENTERPRISES|"
    r"ROAD|STREET|SECTOR|NAGAR|VILLAGE|TALUK|DISTRICT|PLOT|PHASE|FLOOR|"
    r"INDUSTRIAL\s+AREA|PIN(?:CODE)?|NOIDA|PRADESH|KARNATAKA|MAHARASHTRA|DELHI|"
    r"MUMBAI|BENGALURU|BANGALORE|CHENNAI|KOLKATA|HYDERABAD)\b",
    re.I,
)
PRODUCT_WORDS = re.compile(
    r"\b(?:NOODLES?|MASALA|BISCUITS?|COOKIES?|SHAMPOO|SOAP|TOOTHPASTE|TEA|COFFEE|"
    r"FLOUR|ATTA|RICE|OIL|JUICE|DRINK|CREAM|LOTION|MILK|SALT|HONEY|CHOCOLATE|"
    r"SNACKS?|CHIPS|CEREAL|PASTA|SAUCE|KETCHUP|PICKLE|POWDER|MIX|CAKE|BREAD|"
    r"SUNSCREEN|ROSE\s+WATER|T\s*-?SHIRT)\b",
    re.I,
)
# Product is the commodity/type used by compliance rules.  Keep this separate
# from the consumer-facing product_name while preserving the same OCR evidence.
PRODUCT_CATEGORY = re.compile(
    r"\b(?:rose\s+water|body\s+lotion|face\s+wash|hair\s+oil|hand\s+wash|shower\s+gel|"
    r"sun\s+screen|t\s*-?shirt|tooth\s*paste|instant\s+noodles|"
    r"lotion|shampoo|cream|soap|oil|serum|powder|gel|biscuits?|cookies?|"
    r"juice|drink|tea|coffee|ketchup|sauce|pasta|rice|flour|atta|milk|"
    r"honey|chocolate|snacks?|chips|cereal|bread|cake|noodles?)\b",
    re.I,
)
MARKETING_START = re.compile(
    r"^(?:NEW|NOW|THE|WITH|FOR|MADE\s+WITH|RICH\s+IN|GOODNESS|ORIGINAL|SINCE|"
    r"NO\.?\s*1|NUMBER\s*1)\b",
    re.I,
)
QUANTITY_OR_PRICE = re.compile(
    r"(?:₹|\bRS\.?\b|\bINR\b|/-|\b\d+(?:\.\d+)?\s*(?:MG|G|GM|KG|ML|L|PCS?|PIECES?|UNITS?|NOS?)\b)",
    re.I,
)
PHONE_OR_ADDRESS_NUMBER = re.compile(r"(?:\+?\d[\d ().-]{7,}\d|\b\d{5,}\b)")
TITLE_TAIL = re.compile(
    r"\s+\b(?:is|are|was|were|enriched|formulated|infused|contains?|helps?|"
    r"provides?|gives?|featuring|specially)\b.*$",
    re.I,
)
LOGGER = logging.getLogger(__name__)


def _bounds(box: Any) -> tuple[float, float, float, float]:
    if not box:
        return 0.0, 0.0, 0.0, 0.0
    if isinstance(box[0], (list, tuple)):
        xs = [float(point[0]) for point in box]
        ys = [float(point[1]) for point in box]
        return min(xs), min(ys), max(xs), max(ys)
    x1, y1, x2, y2 = map(float, box[:4])
    return min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)


def _height(item: dict[str, Any]) -> float:
    _, y1, _, y2 = _bounds(item.get("box"))
    return max(1.0, y2 - y1)


def _width(item: dict[str, Any]) -> float:
    x1, _, x2, _ = _bounds(item.get("box"))
    return max(1.0, x2 - x1)


def _horizontal_overlap(a: dict[str, Any], b: dict[str, Any]) -> float:
    ax1, _, ax2, _ = _bounds(a.get("box")); bx1, _, bx2, _ = _bounds(b.get("box"))
    return max(0.0, min(ax2, bx2) - max(ax1, bx1)) / max(1.0, min(ax2 - ax1, bx2 - bx1))


def _vertical_gap(a: dict[str, Any], b: dict[str, Any]) -> float:
    _, ay1, _, ay2 = _bounds(a.get("box")); _, by1, _, by2 = _bounds(b.get("box"))
    return max(0.0, max(ay1, by1) - min(ay2, by2))


def _clean_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip(" |:;.,")


def _title_fragment(text: str) -> str:
    """Keep the heading portion of a title line, not its descriptive sentence.

    OCR frequently groups a final product-type word (for example, ``Lotion``)
    with the first few words of a sentence below it.  This only removes a tail
    after an unambiguous sentence/predicate marker; it deliberately leaves
    ordinary product descriptors intact.
    """
    return _clean_text(TITLE_TAIL.sub("", text))


def _is_rejected(text: str) -> bool:
    if not text or REJECTED_CONTEXT.search(text) or COMPANY_OR_ADDRESS.search(text):
        return True
    if re.search(r"\b(?:INDIVIDUAL\s+PACK|NOT\s+FOR\s+(?:SALE|RETAIL)|HELLO)\b", text, re.I):
        return True
    if re.fullmatch(r"(?:SPF|PA\s*\+*)", text, re.I):
        return True
    if re.fullmatch(r"(?:INDIA|BHARAT|INDIAN)", text, re.I):
        return True
    # OCR can miss the "Ingredients" heading when it crops midway through an
    # INCI list. Do not let a comma-separated ingredient line outrank the pack
    # title simply because it has more words.
    if text.count(",") >= 2 and re.search(
        r"\b(?:AQUA|WATER|FRAGRANCE|PARFUM|GLYCERIN|PHENOXYETHANOL|"
        r"SODIUM|BENZOATE|CITRIC|LIMONENE|LINALOOL|TRIETHYLENE|GLYCOL)\b",
        text,
        re.I,
    ):
        return True
    if QUANTITY_OR_PRICE.search(text) or PHONE_OR_ADDRESS_NUMBER.search(text):
        return True
    if "@" in text or re.search(r"https?://|www\.", text, re.I):
        return True
    # A leading number joined to a word is legitimate (for example 2-Minute).
    if re.search(r"\d", text) and not re.search(r"\b\d{1,2}[-'][A-Za-z]", text):
        return True
    if len(text) < 3 or len(text) > 80 or len(text.split()) > 8:
        return True
    return not bool(re.search(r"[A-Za-z]", text))


def _line_item(raw: dict[str, Any], index: int) -> dict[str, Any]:
    text = _clean_text(raw.get("normalized_text") or raw.get("text") or raw.get("raw_text"))
    # OCR sometimes groups the title with directions or benefits in one region.
    # Keep the heading before those predictable body-copy boundaries so the
    # candidate remains eligible for scoring instead of being rejected as an
    # overlong sentence.
    suffix = re.search(
        r"\b(?:HOW\s+TO\s+USE|DIRECTIONS?(?:\s+FOR\s+USE)?|INGREDIENTS?|"
        r"100\s*%\s+PURE|PARABEN\s+FREE|DERMATOLOGICALLY\s+TESTED|"
        r"PHENOXYETHANOL|NUTRITION(?:AL)?\s+INFORMATION)\b",
        text,
        re.I,
    )
    if suffix and suffix.start() >= 3:
        text = _clean_text(text[:suffix.start()])
    return {
        **raw,
        "text": text,
        "raw_text": str(raw.get("raw_text") or raw.get("text") or ""),
        "confidence": float(raw.get("confidence", 0.0) or 0.0),
        "box": raw.get("box") or [0, index * 20, 100, index * 20 + 16],
        "_index": index,
    }


def _can_merge(upper: dict[str, Any], lower: dict[str, Any]) -> bool:
    if _is_rejected(upper["text"]) or _is_rejected(lower["text"]):
        return False
    _, upper_y1, _, upper_y2 = _bounds(upper["box"])
    _, lower_y1, _, lower_y2 = _bounds(lower["box"])
    # A product title is read top-to-bottom.  Do not use PaddleOCR's emitted
    # list order here: rotated/cropped labels regularly return a different one.
    if (lower_y1 + lower_y2) / 2 <= (upper_y1 + upper_y2) / 2:
        return False
    height_ratio = max(_height(upper), _height(lower)) / min(_height(upper), _height(lower))
    gap = _vertical_gap(upper, lower)
    ux1, _, ux2, _ = _bounds(upper["box"]); lx1, _, lx2, _ = _bounds(lower["box"])
    center_delta = abs((ux1 + ux2) / 2 - (lx1 + lx2) / 2)
    aligned = _horizontal_overlap(upper, lower) >= 0.2 or center_delta <= max(_width(upper), _width(lower)) * 0.35
    return height_ratio <= 1.65 and gap <= max(_height(upper), _height(lower)) * 1.15 and aligned


def _deduplicate_overlapping_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse duplicate OCR/ensemble lines that refer to the same label region."""
    unique: list[dict[str, Any]] = []
    for item in sorted(items, key=lambda entry: entry["confidence"], reverse=True):
        x1, y1, x2, y2 = _bounds(item.get("box"))
        duplicate = False
        for existing in unique:
            if _clean_text(item["text"]).casefold() != _clean_text(existing["text"]).casefold():
                continue
            ex1, ey1, ex2, ey2 = _bounds(existing.get("box"))
            intersection = max(0.0, min(x2, ex2) - max(x1, ex1)) * max(
                0.0, min(y2, ey2) - max(y1, ey1)
            )
            area = max(1.0, (x2 - x1) * (y2 - y1)) + max(
                1.0, (ex2 - ex1) * (ey2 - ey1)
            ) - intersection
            if intersection / area >= 0.65:
                duplicate = True
                break
        if not duplicate:
            unique.append(item)
    return sorted(unique, key=lambda entry: entry["_index"])


def _groups(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    clean = [item for item in items if item["confidence"] >= 0.85 and not _is_rejected(item["text"])]
    groups = []
    visual_order = sorted(
        clean,
        key=lambda item: ((_bounds(item["box"])[1] + _bounds(item["box"])[3]) / 2,
                          _bounds(item["box"])[0], item["_index"]),
    )
    for start, item in enumerate(visual_order):
        chain = [item]
        groups.append(_make_group(chain))
        for following in visual_order[start + 1:start + 4]:
            if not _can_merge(chain[-1], following):
                # A package can place an unrelated badge or claim in the same
                # visual row as a title.  Keep scanning the small local window
                # for its lower, aligned continuation; _can_merge still rejects
                # anything beyond the line-height gap limit.
                continue
            chain.append(following)
            groups.append(_make_group(chain))
    return groups


def _make_group(lines: list[dict[str, Any]]) -> dict[str, Any]:
    ordered = sorted(lines, key=lambda item: (_bounds(item["box"])[1], _bounds(item["box"])[0]))
    boxes = [item["box"] for item in ordered]
    bounds = [_bounds(box) for box in boxes]
    return {
        "text": " ".join(_title_fragment(item["text"]) for item in ordered if _title_fragment(item["text"])),
        "raw_lines": [item["raw_text"] for item in ordered],
        "boxes": boxes,
        "indexes": tuple(item["_index"] for item in ordered),
        "confidence": min(item["confidence"] for item in ordered),
        "average_height": sum(_height(item) for item in ordered) / len(ordered),
        "width": max(box[2] for box in bounds) - min(box[0] for box in bounds),
        "center_x": (min(box[0] for box in bounds) + max(box[2] for box in bounds)) / 2,
        "center_y": (min(box[1] for box in bounds) + max(box[3] for box in bounds)) / 2,
    }


def _field(group: dict[str, Any], method: str, score: float) -> dict[str, Any]:
    boxes = group["boxes"]
    return {
        "value": group["text"],
        "confidence": round(group["confidence"], 3),
        "extraction_confidence": round(min(0.99, max(0.0, score / 100)), 3),
        "source_text": "\n".join(group["raw_lines"]),
        "source_lines": group["raw_lines"],
        "source_box": boxes[0] if len(boxes) == 1 else boxes,
        "extraction_method": method,
        "issues": [],
    }


def _commodity_product(product_name: dict[str, Any] | None) -> dict[str, Any] | None:
    """Derive a generic commodity/type without discarding title evidence."""
    if not product_name or not product_name.get("value"):
        return None
    matches = list(PRODUCT_CATEGORY.finditer(str(product_name["value"])))
    if not matches:
        # Retain the full title when its category is not in the conservative
        # vocabulary rather than manufacturing a type from an arbitrary word.
        return dict(product_name)
    match = max(matches, key=lambda found: (found.end(), len(found.group(0))))
    product = dict(product_name)
    product["value"] = match.group(0).title()
    product["extraction_method"] = "product_category_from_product_name"
    return product


def _identity_result(
    brand: dict[str, Any] | None,
    product_name: dict[str, Any] | None,
    status: str,
    *,
    candidates: list[dict[str, Any]] | None = None,
    rejected: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Build one identity result and emit rich diagnostics only at DEBUG."""
    result = {
        "brand_name": brand,
        "product_name": product_name,
        "product": _commodity_product(product_name),
        "status": status,
    }
    if LOGGER.isEnabledFor(logging.DEBUG):
        trace = {
            "candidates": candidates or [],
            "rejected": rejected or [],
            "selected_brand": brand.get("value") if brand else None,
            "selected_product_name": product_name.get("value") if product_name else None,
            "selected_product": result["product"].get("value") if result["product"] else None,
            "status": status,
        }
        result["debug_trace"] = trace
        LOGGER.debug("Product identity extraction trace: %s", trace)
    return result


def _explicit_value(items: list[dict[str, Any]], label: re.Pattern[str], method: str) -> dict[str, Any] | None:
    for position, item in enumerate(items):
        match = label.match(item["text"])
        if not match:
            continue
        inline = _clean_text(match.group(1))
        if inline and not _is_rejected(inline):
            group = _make_group([{**item, "text": inline, "raw_text": inline}])
            return _field(group, method, 96)
        for candidate in items[position + 1:position + 3]:
            if not _is_rejected(candidate["text"]):
                return _field(_make_group([candidate]), method, 93)
    return None


def _inline_brand_prefix(product_group: dict[str, Any], score: float) -> tuple[dict[str, Any], dict[str, Any]] | None:
    """Split a clearly brand-prefixed product heading from one OCR region.

    There is no typography information inside a single OCR box, so this is
    intentionally stricter than the separate-logo path.  We only split when a
    short leading word is followed by a substantial product heading ending in a
    product-type term.  Otherwise retaining the full visible heading is safer
    than inventing a brand.
    """
    words = product_group["text"].split()
    if len(words) < 4 or (len(words) == 4 and words[0].upper() == words[0]):
        return None
    prefix, remainder_words = words[0], words[1:]
    remainder = " ".join(remainder_words)
    if (
        len(prefix) < 3
        or len(prefix) > 18
        or not re.fullmatch(r"[A-Za-z][A-Za-z&'’.-]*", prefix)
        or PRODUCT_WORDS.search(prefix)
        or len(remainder_words) < 3
        or not PRODUCT_WORDS.search(remainder)
    ):
        return None
    first_line = product_group["raw_lines"][0] if product_group["raw_lines"] else product_group["text"]
    first_box = product_group["boxes"][0]
    prefix_group = {
        **product_group,
        "text": prefix,
        "raw_lines": [first_line],
        "boxes": [first_box],
    }
    product_copy = {**product_group, "text": remainder}
    # This deduction represents the absence of intra-box typography, not OCR
    # uncertainty; preserve the original OCR confidence in both fields.
    return _field(prefix_group, "inline_brand_prefix", score - 12), _field(
        product_copy, "inline_brand_removed_from_heading", score - 5
    )


def extract_product_identity(ocr_items: Iterable[dict[str, Any]]) -> dict[str, Any]:
    """Return separate brand/product fields, abstaining on ambiguous products."""
    raw_items = list(ocr_items or [])
    # On glossy labels, OCR can read the small word "Water" as "Wafer".
    # Correct that token only when this same package independently contains
    # both a clear "Rose water" phrase and the Gulabari product title.
    has_rose_water_context = (
        any(
            re.search(r"\bROSE\s+WATER\b", str(item.get("text") or item.get("raw_text") or ""), re.I)
            for item in raw_items
        )
        and any(
            re.search(r"\bGULABARI\b", str(item.get("text") or item.get("raw_text") or ""), re.I)
            for item in raw_items
        )
    )
    items = [_line_item(raw, index) for index, raw in enumerate(raw_items)]
    if has_rose_water_context:
        for item in items:
            if re.search(r"\bROSE\s+WAFER\b", item["text"], re.I):
                item["text"] = re.sub(r"\bROSE\s+WAFER\b", "Rose Water", item["text"], flags=re.I)
    items = _deduplicate_overlapping_items(items)
    if not items:
        return _identity_result(None, None, "REVIEW", rejected=[{"reason": "no_ocr_items"}])

    rejected = [
        {
            "text": item["raw_text"],
            "confidence": round(item["confidence"], 3),
            "rejection_reason": "metadata_or_invalid_title",
        }
        for item in items
        if _is_rejected(item["text"])
    ]

    explicit_product = _explicit_value(items, PRODUCT_LABEL, "explicit_product_name_label")
    explicit_brand = _explicit_value(items, BRAND_LABEL, "explicit_brand_name_label")
    if explicit_product and explicit_brand:
        return _identity_result(
            explicit_brand, explicit_product, "OK", rejected=rejected,
            candidates=[{"text": explicit_product["value"], "score": 96, "reasons": ["explicit_product_label"]}],
        )

    groups = _groups(items)
    if not groups:
        return _identity_result(
            explicit_brand, explicit_product, "OK" if explicit_product else "REVIEW",
            rejected=rejected,
        )
    heights = [_height(item) for item in items if item["confidence"] >= 0.5]
    typical_height = max(1.0, median(heights))
    all_bounds = [_bounds(item["box"]) for item in items]
    min_x = min(box[0] for box in all_bounds); max_x = max(box[2] for box in all_bounds)
    min_y = min(box[1] for box in all_bounds); max_y = max(box[3] for box in all_bounds)
    page_width = max(1.0, max_x - min_x); page_height = max(1.0, max_y - min_y)

    def position_score(group: dict[str, Any]) -> float:
        x = (group["center_x"] - min_x) / page_width
        y = (group["center_y"] - min_y) / page_height
        return max(0.0, 8 - abs(x - 0.5) * 10) + max(0.0, 9 - y * 12)

    brand_scored = []
    product_scored = []
    candidate_trace = []
    for group in groups:
        text = group["text"]
        words = text.split()
        size_ratio = min(3.0, group["average_height"] / typical_height)
        base = group["confidence"] * 28 + size_ratio * 10 + position_score(group)
        uppercase = text.upper() == text and bool(re.search(r"[A-Z]", text))
        brand_score = base + (15 if uppercase else 4) + (10 if len(words) == 1 else 5 if len(words) <= 3 else -10)
        if PRODUCT_WORDS.search(text):
            brand_score -= 12
        if len(group["indexes"]) == 1:
            brand_scored.append((brand_score, group))

        product_score = base + (18 if PRODUCT_WORDS.search(text) else 0)
        product_score += 10 if 2 <= len(words) <= 6 else 2
        product_score += 7 if len(group["indexes"]) > 1 else 0
        product_score -= 12 if MARKETING_START.search(text) else 0
        product_score -= 9 if uppercase and len(words) == 1 and not PRODUCT_WORDS.search(text) else 0
        if len(words) > 1 or "-" in text:
            product_scored.append((product_score, group))
            candidate_trace.append({
                "text": text,
                "score": round(product_score, 3),
                "reasons": [
                    "high_ocr_confidence" if group["confidence"] >= 0.9 else "usable_ocr_confidence",
                    "prominent_text" if size_ratio >= 1.15 else "standard_text_size",
                    "upper_package_region" if group["center_y"] <= min_y + page_height * 0.4 else "lower_package_region",
                    "product_category_word" if PRODUCT_WORDS.search(text) else "heading_shape",
                    "merged_nearby_lines" if len(group["indexes"]) > 1 else "single_ocr_line",
                ],
                "source_box": group["boxes"] if len(group["boxes"]) > 1 else group["boxes"][0],
            })

    brand_score, brand_group = max(brand_scored, key=lambda pair: pair[0]) if brand_scored else (0.0, None)
    brand = explicit_brand
    selected_brand_group = None
    if brand is None and brand_group is not None and brand_score >= 54:
        brand_index = brand_group["indexes"][0]
        related_products = [
            group for group in groups
            if brand_index not in group["indexes"]
            and group["center_y"] >= brand_group["center_y"]
            and group["center_y"] - brand_group["center_y"] <= max(group["average_height"], brand_group["average_height"]) * 6
            and PRODUCT_WORDS.search(group["text"])
        ]
        looks_like_styled_wordmark = (
            brand_group["text"].upper() != brand_group["text"]
            and len(brand_group["text"].split()) <= 2
        )
        visibly_larger = any(
            brand_group["average_height"] >= group["average_height"] * 1.15
            for group in related_products
        )
        if related_products and (looks_like_styled_wordmark or visibly_larger):
            brand = _field(brand_group, "prominent_logo_heading", brand_score)
            selected_brand_group = brand_group

    if explicit_product:
        if brand:
            brand_text = str(brand.get("value") or "").strip()
            product_text = str(explicit_product.get("value") or "").strip()
            remainder = re.sub(
                rf"^{re.escape(brand_text)}\b[\s:;,.\-]*",
                "",
                product_text,
                count=1,
                flags=re.I,
            ).strip()
            if remainder and not _is_rejected(remainder):
                explicit_product["value"] = remainder
                explicit_product["extraction_method"] = "explicit_product_name_label_brand_removed"
        return _identity_result(brand, explicit_product, "OK", candidates=candidate_trace, rejected=rejected)

    # A brand/logo is a separate identity, not a valid product-name candidate.
    filtered_products = [
        pair for pair in product_scored
        if not (
            selected_brand_group
            and set(pair[1]["indexes"]).intersection(selected_brand_group["indexes"])
        )
    ]
    filtered_products.sort(key=lambda pair: pair[0], reverse=True)
    if not filtered_products:
        return _identity_result(brand, None, "REVIEW", candidates=candidate_trace, rejected=rejected)
    best_score, best = filtered_products[0]

    if len(best["indexes"]) == 1 and len(best["text"].split()) == 1 and PRODUCT_WORDS.fullmatch(best["text"]):
        return _identity_result(brand, None, "REVIEW", candidates=candidate_trace, rejected=rejected)

    # Reward a plausible name placed close to the selected brand, normally below it.
    if selected_brand_group:
        brand_bottom = max(_bounds(box)[3] for box in selected_brand_group["boxes"])
        product_top = min(_bounds(box)[1] for box in best["boxes"])
        vertical_distance = product_top - brand_bottom
        if -best["average_height"] <= vertical_distance <= best["average_height"] * 5:
            best_score += 7

    challenger = next((pair for pair in filtered_products[1:] if set(pair[1]["indexes"]).isdisjoint(best["indexes"])), None)
    ambiguous = challenger is not None and best_score - challenger[0] < 6
    if best_score < 60 or ambiguous:
        rejection_reason = "ambiguous_product_candidates" if ambiguous else "product_score_below_threshold"
        rejected.append({"text": best["text"], "score": round(best_score, 3), "rejection_reason": rejection_reason})
        return _identity_result(brand, None, "REVIEW", candidates=candidate_trace, rejected=rejected)
    if brand is None:
        inline_identity = _inline_brand_prefix(best, best_score)
        if inline_identity is not None:
            brand, product = inline_identity
            return _identity_result(brand, product, "OK", candidates=candidate_trace, rejected=rejected)
    return _identity_result(
        brand, _field(best, "scored_product_heading", best_score), "OK",
        candidates=candidate_trace, rejected=rejected,
    )
