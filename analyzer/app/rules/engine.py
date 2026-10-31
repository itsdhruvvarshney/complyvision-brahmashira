"""Compliance engine: the prototype rule CSV + deterministic validators.

Each rule resolves to PASS, FAIL, REVIEW or NOT_APPLICABLE with a human-readable reason, what was
expected, what was found, and the declaration fields that serve as evidence. The AI reader (Gemini)
can never turn a FAIL into a PASS - it can only flag a disagreement for human REVIEW.
"""

from __future__ import annotations

import csv
import os
from dataclasses import dataclass, field
from datetime import date

PASS, FAIL, REVIEW, NA = "PASS", "FAIL", "REVIEW", "NOT_APPLICABLE"

_CSV = os.path.join(os.path.dirname(__file__), "lm_rules.csv")


@dataclass
class Rule:
    id: str
    sort_order: int
    title: str
    reference: str
    category: str
    severity: str
    validator: str
    description: str


def load_rules(path: str = _CSV) -> list[Rule]:
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    rules = [Rule(r["id"], int(r["sort_order"]), r["title"], r["reference"], r["category"], r["severity"],
                  r["validator"], r["description"]) for r in rows]
    missing = [r.validator for r in rules if r.validator not in VALIDATORS]
    if missing:
        raise ValueError(f"Rule CSV references unknown validators: {missing}")
    return sorted(rules, key=lambda r: r.sort_order)


@dataclass
class Outcome:
    status: str
    reason: str
    expected: str | None = None
    found: str | None = None
    fields: list[str] = field(default_factory=list)


@dataclass
class RuleContext:
    decl: dict
    quality: dict
    calibration: dict
    measurements: dict
    expected: dict
    ai: dict
    ocr_mean_confidence: float
    ocr_line_count: int
    require_calibration: bool = False
    today: date = field(default_factory=date.today)

    @property
    def weak_capture(self) -> bool:
        return self.quality.get("grade") == "Poor" or self.ocr_mean_confidence < 0.62

    def ai_saw(self, key: str) -> bool:
        seen = (self.ai or {}).get("declarations_seen") or {}
        return bool(seen.get(key))


LOW_CONF = 0.6


def _missing(ctx: RuleContext, key: str, what: str, fields: list[str] | None = None, expected: str | None = None) -> Outcome:
    if ctx.ai_saw(key):
        return Outcome(REVIEW, f"{what} was not read by OCR, but the AI reader reports it on the label - confirm on the physical pack.",
                       expected, "AI reader only", fields or [])
    if ctx.weak_capture:
        return Outcome(REVIEW, f"{what} was not found; the capture quality may have hidden it - re-scan or verify manually.",
                       expected, "Not found", fields or [])
    return Outcome(FAIL, f"{what} is not declared on the label.", expected, "Not found", fields or [])


def _fmt_money(v: float | None) -> str:
    return "—" if v is None else f"₹{v:,.2f}"


def _fmt_qty(q: dict | None) -> str:
    if not q:
        return "—"
    v = q["value"]
    return f"{v:g} {q['unit']}"


# ---------------------------------------------------------------------------
# Validators
# ---------------------------------------------------------------------------

def v_manufacturer(ctx: RuleContext) -> Outcome:
    m = ctx.decl.get("manufacturer")
    if not m:
        return _missing(ctx, "manufacturer", "Manufacturer / packer name and address", expected="Name and complete address")
    found = m.get("address") or m.get("name")
    if m.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Manufacturer details were read with low confidence.", "Name and complete address", found, ["manufacturer"])
    if not m.get("address_complete"):
        return Outcome(REVIEW, "A manufacturer / packer name was found but the address looks incomplete.",
                       "Name and complete address", found, ["manufacturer"])
    return Outcome(PASS, f"{m.get('role', 'Manufacturer').title()} by {m.get('name')} with address declared.",
                   "Name and complete address", found, ["manufacturer"])


def v_country_of_origin(ctx: RuleContext) -> Outcome:
    o = ctx.decl.get("origin") or {}
    imported = o.get("imported") or bool((ctx.ai or {}).get("is_imported"))
    if not imported:
        return Outcome(NA, "Domestic product - no import markers on the label.", None, o.get("country") or "Domestic", ["origin"])
    if o.get("country"):
        return Outcome(PASS, f"Country of origin declared: {o['country']}.", "Country of origin", o["country"], ["origin"])
    return _missing(ctx, "country_of_origin", "Country of origin for an imported package", ["origin"], "Country of origin")


def v_generic_name(ctx: RuleContext) -> Outcome:
    g = ctx.decl.get("generic_name")
    if g:
        note = "" if g.get("matches_case_product") or not ctx.expected.get("product_words") else " (differs from the case product)"
        return Outcome(PASS, f"Generic name '{g['value']}' declared{note}.", "Common / generic name", g["value"], ["generic_name"])
    ai_name = (ctx.ai or {}).get("generic_name")
    if ai_name:
        return Outcome(REVIEW, f"OCR could not confirm the generic name; the AI reader suggests '{ai_name}'.",
                       "Common / generic name", ai_name, [])
    return _missing(ctx, "generic_name", "Common or generic name of the commodity", expected="Common / generic name")


def v_net_quantity(ctx: RuleContext) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return _missing(ctx, "net_quantity", "Net quantity", ["net_quantity"], "Net quantity in g / kg / ml / L / N")
    found = _fmt_qty(q)
    if not q.get("standard_symbol", True):
        return Outcome(REVIEW, f"Net quantity uses the non-standard symbol '{q['unit']}' (standard: g, kg, ml, L).",
                       "Standard unit symbol", found, ["net_quantity"])
    if q.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Net quantity was read with low confidence.", "Net quantity in standard units", found, ["net_quantity"])
    label = "" if q.get("labelled") else " (no 'Net Qty' label next to it)"
    return Outcome(PASS, f"Net quantity {found} declared in standard units{label}.", "Net quantity in standard units", found, ["net_quantity"])


def v_mfg_date(ctx: RuleContext) -> Outcome:
    d = ctx.decl.get("mfg_date")
    if not d:
        return _missing(ctx, "mfg_date", "Month and year of manufacture / packing", ["mfg_date"], "Month & year")
    if d.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Manufacture date was read with low confidence.", "Month & year", d["iso"], ["mfg_date"])
    return Outcome(PASS, f"Manufacture / packing date {d['iso']} declared.", "Month & year", d["iso"], ["mfg_date"])


def v_mrp_declared(ctx: RuleContext) -> Outcome:
    m = ctx.decl.get("mrp")
    if not m:
        return _missing(ctx, "mrp", "Retail sale price (MRP)", ["mrp"], "MRP on the package")
    if m.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "MRP was read with low confidence.", "MRP on the package", _fmt_money(m["value"]), ["mrp"])
    return Outcome(PASS, f"MRP {_fmt_money(m['value'])} declared.", "MRP on the package", _fmt_money(m["value"]), ["mrp"])


def v_mrp_format(ctx: RuleContext) -> Outcome:
    m = ctx.decl.get("mrp")
    if not m:
        return Outcome(NA, "No MRP found to assess (see LM-06).", "Rs. / ₹ and 'inclusive of all taxes'", None, [])
    found = m.get("text")
    if not m.get("inclusive_of_taxes"):
        if ctx.ai_saw("tax_inclusive"):
            return Outcome(REVIEW, "OCR did not read 'inclusive of all taxes'; the AI reader reports it - confirm.",
                           "'Inclusive of all taxes'", found, ["mrp"])
        if ctx.weak_capture:
            return Outcome(REVIEW, "'Inclusive of all taxes' was not found; capture quality may have hidden it.",
                           "'Inclusive of all taxes'", found, ["mrp"])
        return Outcome(FAIL, "MRP is not stated as inclusive of all taxes.", "'Inclusive of all taxes'", found, ["mrp"])
    if not m.get("currency_symbol"):
        return Outcome(REVIEW, "Tax-inclusive MRP found, but the rupee symbol / 'Rs.' could not be read.",
                       "Rs. / ₹ prefix", found, ["mrp"])
    return Outcome(PASS, "MRP stated in rupees and inclusive of all taxes.", "Rs. / ₹ and 'inclusive of all taxes'", found, ["mrp"])


def v_unit_sale_price(ctx: RuleContext) -> Outcome:
    u = ctx.decl.get("unit_sale_price")
    if u:
        return Outcome(PASS, f"Unit sale price ₹{u['value']:g} per {u['per']} declared.", "Price per unit of measure",
                       f"₹{u['value']:g}/{u['per']}", ["unit_sale_price"])
    return _missing(ctx, "unit_sale_price", "Unit sale price", ["unit_sale_price"], "Price per kg / L / unit")


def v_customer_care(ctx: RuleContext) -> Outcome:
    c = ctx.decl.get("customer_care")
    if not c or not (c.get("phones") or c.get("emails")):
        if c and c.get("label"):
            return Outcome(REVIEW, "A consumer care heading was found but no phone number or e-mail could be read.",
                           "Phone and e-mail", c.get("text"), ["customer_care"])
        return _missing(ctx, "customer_care", "Consumer care telephone / e-mail", ["customer_care"], "Phone and e-mail")
    parts = []
    if c.get("phones"):
        parts.append(c["phones"][0])
    if c.get("emails"):
        parts.append(c["emails"][0])
    note = "" if c.get("phones") and c.get("emails") else " (only one contact channel found)"
    return Outcome(PASS, f"Consumer care contact declared{note}.", "Phone and e-mail", ", ".join(parts), ["customer_care"])


def v_best_before(ctx: RuleContext) -> Outcome:
    is_food = (ctx.decl.get("is_food") or {}).get("value") or bool((ctx.ai or {}).get("is_food"))
    if not is_food:
        return Outcome(NA, "Not a food or perishable commodity.", None, None, [])
    b = ctx.decl.get("best_before")
    if not b:
        return _missing(ctx, "best_before", "Best before / use-by date", ["best_before"], "Best before or use-by")
    found = b.get("iso") or f"{b.get('amount')} {b.get('unit')} from manufacture"
    return Outcome(PASS, f"Best before / use-by declared ({found}).", "Best before or use-by", found, ["best_before"])


def v_numeral_height(ctx: RuleContext) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q or q.get("base_unit") not in ("g", "ml"):
        return Outcome(NA, "No weight / volume net quantity to measure.", None, None, [])
    if not ctx.calibration.get("detected"):
        status = REVIEW if ctx.require_calibration else NA
        return Outcome(status, "No ArUco calibration marker in the frame, so physical size cannot be measured. "
                               "Place the 20 mm marker beside the pack to enable this check.",
                       None, None, ["net_quantity"])
    meas = ctx.measurements.get("numeral_height")
    required = ctx.measurements.get("numeral_required_mm")
    if not meas:
        return Outcome(REVIEW, "Numeral glyphs could not be isolated for measurement.",
                       f"≥ {required:g} mm" if required else None, None, ["net_quantity", "calibration"])
    got = meas["height_mm"]
    expected = f"≥ {required:g} mm for {_fmt_qty(q)}"
    found = f"{got:.1f} mm"
    if got >= required:
        return Outcome(PASS, f"Numerals are {got:.1f} mm tall (minimum {required:g} mm).", expected, found, ["net_quantity", "calibration"])
    if got >= required * 0.9:
        return Outcome(REVIEW, f"Numerals measure {got:.1f} mm, within measurement tolerance of the {required:g} mm minimum.",
                       expected, found, ["net_quantity", "calibration"])
    return Outcome(FAIL, f"Numerals are only {got:.1f} mm tall; the minimum is {required:g} mm.", expected, found,
                   ["net_quantity", "calibration"])


def v_contrast(ctx: RuleContext) -> Outcome:
    c = ctx.measurements.get("contrast") or {}
    worst = c.get("worst")
    if worst is None:
        return Outcome(REVIEW, "Declaration regions were not available for a contrast measurement.", "Contrast ≥ 3:1", None, [])
    found = f"{worst['ratio']:.1f}:1 ({worst['field']})"
    fields = [worst["field"]]
    if worst["ratio"] >= 3.0:
        return Outcome(PASS, f"Declarations contrast clearly with the background (lowest {worst['ratio']:.1f}:1).",
                       "Contrast ≥ 3:1", found, fields)
    if worst["ratio"] >= 1.8:
        return Outcome(REVIEW, f"Contrast is borderline ({worst['ratio']:.1f}:1) for the {worst['field'].replace('_', ' ')}.",
                       "Contrast ≥ 3:1", found, fields)
    return Outcome(FAIL, f"The {worst['field'].replace('_', ' ')} is printed with insufficient contrast ({worst['ratio']:.1f}:1).",
                   "Contrast ≥ 3:1", found, fields)


def v_mrp_matches_case(ctx: RuleContext) -> Outcome:
    expected = ctx.expected.get("mrp")
    if expected is None:
        return Outcome(NA, "No MRP recorded for this case.", None, None, [])
    m = ctx.decl.get("mrp")
    if not m:
        return Outcome(REVIEW, "Pack MRP could not be read, so it cannot be compared with the case record.",
                       _fmt_money(expected), None, [])
    got = m["value"]
    if abs(got - expected) < 0.005:
        return Outcome(PASS, f"Pack MRP {_fmt_money(got)} matches the case record.", _fmt_money(expected), _fmt_money(got), ["mrp"])
    if got > expected:
        return Outcome(FAIL, f"Pack MRP {_fmt_money(got)} is higher than the recorded {_fmt_money(expected)}.",
                       _fmt_money(expected), _fmt_money(got), ["mrp"])
    return Outcome(REVIEW, f"Pack MRP {_fmt_money(got)} differs from the recorded {_fmt_money(expected)}.",
                   _fmt_money(expected), _fmt_money(got), ["mrp"])


def v_date_consistency(ctx: RuleContext) -> Outcome:
    mfg = ctx.decl.get("mfg_date")
    bb = ctx.decl.get("best_before")
    if not mfg:
        return Outcome(NA, "Not enough dates declared to compare.", None, None, [])
    today_key = (ctx.today.year, ctx.today.month, ctx.today.day)
    mfg_key = (mfg["year"], mfg["month"], mfg.get("day") or 1)
    if mfg_key[:2] > today_key[:2] or (mfg.get("day") and mfg_key > today_key):
        return Outcome(FAIL, f"Manufacture date {mfg['iso']} is in the future.", "Not after today", mfg["iso"], ["mfg_date"])
    if bb and bb.get("year"):
        bb_key = (bb["year"], bb["month"], bb.get("day") or 31)
        if bb_key[:2] < mfg_key[:2]:
            return Outcome(FAIL, f"Best before {bb['iso']} is earlier than manufacture {mfg['iso']}.",
                           "Best before after manufacture", f"{mfg['iso']} → {bb['iso']}", ["mfg_date", "best_before"])
        if bb_key < today_key:
            return Outcome(FAIL, f"Product expired on {bb['iso']} and is still on sale.", "Not expired", bb["iso"],
                           ["mfg_date", "best_before"])
        return Outcome(PASS, f"Manufactured {mfg['iso']}, best before {bb['iso']} - consistent and not expired.",
                       "Consistent dates", f"{mfg['iso']} → {bb['iso']}", ["mfg_date", "best_before"])
    return Outcome(PASS, f"Manufacture date {mfg['iso']} is not in the future.", "Not after today", mfg["iso"], ["mfg_date"])


def v_image_quality(ctx: RuleContext) -> Outcome:
    grade = ctx.quality.get("grade")
    score = ctx.quality.get("score")
    found = f"{grade} ({score}/100), OCR confidence {ctx.ocr_mean_confidence:.0%}"
    if ctx.ocr_line_count == 0:
        return Outcome(FAIL, "No readable text was found in the image.", "Readable label", found, [])
    if grade == "Poor" or ctx.ocr_mean_confidence < 0.62:
        return Outcome(REVIEW, "Image quality is marginal - automated results should be confirmed manually.", "Good capture", found, [])
    if grade == "Fair" and ctx.ocr_mean_confidence < 0.8:
        return Outcome(REVIEW, "Fair image quality with modest OCR confidence - spot-check the results.", "Good capture", found, [])
    return Outcome(PASS, "Image quality sufficient for automated verification.", "Good capture", found, [])


def v_quantity_matches_case(ctx: RuleContext) -> Outcome:
    exp = ctx.expected.get("quantity")
    if not exp:
        return Outcome(NA, "No quantity recorded for this product.", None, None, [])
    q = ctx.decl.get("net_quantity")
    if not q:
        return Outcome(REVIEW, "Pack quantity could not be read, so it cannot be compared with the product record.",
                       _fmt_qty(exp), None, [])
    if q["base_unit"] != exp["base_unit"]:
        return Outcome(REVIEW, f"Pack declares {_fmt_qty(q)} but the product record is {_fmt_qty(exp)} (different measure).",
                       _fmt_qty(exp), _fmt_qty(q), ["net_quantity"])
    diff = (q["base_value"] - exp["base_value"]) / max(exp["base_value"], 1e-6)
    if abs(diff) <= 0.005:
        return Outcome(PASS, f"Pack quantity {_fmt_qty(q)} matches the product record.", _fmt_qty(exp), _fmt_qty(q), ["net_quantity"])
    if diff < 0:
        return Outcome(FAIL, f"Pack declares {_fmt_qty(q)}, less than the recorded {_fmt_qty(exp)}.", _fmt_qty(exp), _fmt_qty(q),
                       ["net_quantity"])
    return Outcome(REVIEW, f"Pack declares {_fmt_qty(q)}, more than the recorded {_fmt_qty(exp)}.", _fmt_qty(exp), _fmt_qty(q),
                   ["net_quantity"])


VALIDATORS = {
    "manufacturer": v_manufacturer,
    "country_of_origin": v_country_of_origin,
    "generic_name": v_generic_name,
    "net_quantity": v_net_quantity,
    "mfg_date": v_mfg_date,
    "mrp_declared": v_mrp_declared,
    "mrp_format": v_mrp_format,
    "unit_sale_price": v_unit_sale_price,
    "customer_care": v_customer_care,
    "best_before": v_best_before,
    "numeral_height": v_numeral_height,
    "contrast": v_contrast,
    "mrp_matches_case": v_mrp_matches_case,
    "date_consistency": v_date_consistency,
    "image_quality": v_image_quality,
    "quantity_matches_case": v_quantity_matches_case,
}


def evaluate(rules: list[Rule], ctx: RuleContext) -> list[tuple[Rule, Outcome]]:
    results = []
    for rule in rules:
        try:
            outcome = VALIDATORS[rule.validator](ctx)
        except Exception as exc:  # a broken validator must never silently pass a package
            outcome = Outcome(REVIEW, f"Automated check could not complete ({exc.__class__.__name__}); verify manually.")
        results.append((rule, outcome))
    return results


def verdict(statuses: list[str]) -> str:
    if FAIL in statuses:
        return FAIL
    if REVIEW in statuses:
        return REVIEW
    return PASS
