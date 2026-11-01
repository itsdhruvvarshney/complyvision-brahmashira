"""Compliance engine: category-aware Legal Metrology rule catalog + deterministic validators.

Flow for one package:
  1. the product is categorised (classifier.py) and its package type read from the label;
  2. the catalog is narrowed to the rules that govern that category and package type
     (catalog.select_rules); the rest are reported as "not relevant to this category";
  3. applicability gates are worked out - Rule 3 scope (over 25 kg / 'not for retail sale') and the
     Rule 26(a) small-package exemption switch the Chapter II declaration rules to NOT_APPLICABLE;
  4. every selected rule resolves to PASS, FAIL, REVIEW or NOT_APPLICABLE with a reason, what was
     expected, what was found, and the label lines that serve as evidence.

The AI reader (Gemini) can never pass a rule - it can only turn a FAIL into REVIEW when it reports
seeing a declaration the OCR missed. Only an inspector can resolve a REVIEW.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date

from . import catalog as cat_mod
from .catalog import Category, Rule
from .classifier import CONFIDENT

PASS, FAIL, REVIEW, NA = "PASS", "FAIL", "REVIEW", "NOT_APPLICABLE"
LOW_CONF = 0.6


def load_rules(path: str = cat_mod.RULES_CSV) -> list[Rule]:
    return cat_mod.load_rules(path, VALIDATORS)


@dataclass
class Outcome:
    status: str
    reason: str
    expected: str | None = None
    found: str | None = None
    fields: list[str] = field(default_factory=list)
    lines: list[int] = field(default_factory=list)


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
    category: Category | None = None
    classification: dict = field(default_factory=dict)
    package_type: str = "retail"
    texts: list[str] = field(default_factory=list)
    context: dict = field(default_factory=dict)

    def __post_init__(self):
        if self.category is None:
            self.category = cat_mod.load_taxonomy().general
        self._joined = None

    @property
    def weak_capture(self) -> bool:
        return self.quality.get("grade") == "Poor" or self.ocr_mean_confidence < 0.62

    def ai_saw(self, key: str) -> bool:
        seen = (self.ai or {}).get("declarations_seen") or {}
        return bool(seen.get(key))

    def has(self, tag: str) -> bool:
        return self.category.has(tag)

    @property
    def joined(self) -> str:
        if self._joined is None:
            self._joined = " ".join(self.texts)
        return self._joined

    def search(self, pattern: str) -> tuple[re.Match | None, list[int]]:
        """Searches the label text across line breaks; returns the match and the lines it spans."""
        rx = re.compile(pattern, re.I)
        m = rx.search(self.joined)
        if not m:
            return None, []
        idx, pos = [], 0
        for i, t in enumerate(self.texts):
            end = pos + len(t)
            if end >= m.start() and pos <= m.end():
                idx.append(i)
            pos = end + 1
        return m, idx


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
    return f"{q['value']:g} {q['unit']}"


def _qty_band(q: dict | None) -> float | None:
    return q.get("base_value") if q and q.get("base_unit") in ("g", "ml") else None


def _lines(ctx: RuleContext, key: str) -> list[int]:
    return list((ctx.decl.get(key) or {}).get("lines") or [])


# ---------------------------------------------------------------------------
# Applicability gates (Rule 3 scope, Rule 26 exemptions)
# ---------------------------------------------------------------------------

def scope_gate(ctx: RuleContext) -> dict | None:
    """Reason why the Chapter II declaration rules do not apply to this package, or None."""
    q = ctx.decl.get("net_quantity")
    if ctx.package_type == "institutional":
        return {"rule": "LM-17", "reason": "Package is marked for industrial / institutional consumers ('not for retail sale') - "
                                           "Chapter II retail declarations do not apply (Rule 3(c)). See LM-17."}
    base = _qty_band(q)
    if base is not None:
        limit = 50000.0 if ctx.has("bulk_50kg") and q["base_unit"] == "g" else 25000.0
        if base > limit:
            return {"rule": "LM-17", "reason": f"Net quantity {_fmt_qty(q)} exceeds {limit / 1000:g} kg / L - outside Chapter II "
                                               f"(Rule 3(a), 3(b)). See LM-17."}
        if base <= 10.0 and not ctx.has("no_small_pack_exemption"):
            return {"rule": "LM-18", "reason": f"Net quantity {_fmt_qty(q)} is 10 g / 10 ml or less - exempt under Rule 26(a). See LM-18."}
    return None


# ---------------------------------------------------------------------------
# Categorisation & applicability
# ---------------------------------------------------------------------------

def v_categorisation(ctx: RuleContext, rule: Rule) -> Outcome:
    c = ctx.classification or {}
    cat = ctx.category
    conf = float(c.get("confidence", 0.0))
    source = c.get("source", "auto")
    laws = "; ".join(cat.laws[:3])
    found = f"{cat.name} ({conf:.0%}, {'set by inspector' if source == 'inspector' else 'auto-detected'})"
    if source == "inspector":
        return Outcome(PASS, f"Category set by the inspector: {cat.name}. Governing law: {laws}.", "Product category", found)
    if source == "fallback":
        return Outcome(REVIEW, "The product could not be matched to a specific category, so only the general Legal Metrology rules "
                               "were applied - choose the category and re-run to apply its standards.", "Product category", found)
    if conf < CONFIDENT:
        alts = ", ".join(a["name"] for a in c.get("alternatives", [])[:2])
        return Outcome(REVIEW, f"Categorised as {cat.name} with low confidence" + (f" (also possible: {alts})" if alts else "") +
                       " - confirm the category so the right standards are applied.", "Product category", found)
    ev = ", ".join(f"{e['source'].lower()}: {e['detail']}" for e in c.get("evidence", [])[:3])
    return Outcome(PASS, f"Categorised as {cat.name} ({ev}). Governing law: {laws}.", "Product category", found)


def v_chapter2_scope(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if ctx.package_type == "institutional":
        mark = (ctx.decl.get("institutional_marking") or {}).get("value", "not for retail sale")
        return Outcome(REVIEW, f"The pack is marked '{mark}' (industrial / institutional consumers, Rule 2(bb), 2(bc)), so the retail "
                               "declarations were not applied. Confirm it is not being offered for retail sale; if it is, re-run as a retail package.",
                       "Retail package or genuine institutional supply", mark, lines=_lines(ctx, "institutional_marking"))
    if ctx.package_type == "wholesale":
        return Outcome(NA, "Wholesale package - Rule 24 (Chapter III) declarations applied instead of Chapter II.", None,
                       (ctx.decl.get("wholesale_marking") or {}).get("value"), lines=_lines(ctx, "wholesale_marking"))
    gate = scope_gate(ctx)
    if gate and gate["rule"] == "LM-17":
        return Outcome(NA, gate["reason"].replace(" See LM-17.", ""), "≤ 25 kg / 25 L (≤ 50 kg for cement, fertiliser, farm produce)",
                       _fmt_qty(q), ["net_quantity"])
    found = _fmt_qty(q) if q else "quantity not read"
    return Outcome(PASS, f"Retail package ({found}) - Chapter II declarations apply.", "Retail package within Rule 3", found,
                   ["net_quantity"] if q else [])


def v_small_pack_exemption(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    base = _qty_band(q)
    if base is None:
        return Outcome(NA, "No weight / volume quantity to test against the 10 g / 10 ml threshold.")
    if base <= 10.0:
        if ctx.has("no_small_pack_exemption"):
            return Outcome(NA, f"{_fmt_qty(q)} is a small package, but the Rule 26(a) exemption does not apply to "
                               f"{ctx.category.name.lower()} - every declaration is still required.", "No exemption", _fmt_qty(q), ["net_quantity"])
        return Outcome(NA, f"{_fmt_qty(q)} - exempt from the LM (PC) Rules under Rule 26(a); the declaration rules are not applicable.",
                       "≤ 10 g / 10 ml", _fmt_qty(q), ["net_quantity"])
    return Outcome(NA, f"{_fmt_qty(q)} is above 10 g / 10 ml - the small-package exemption does not apply.", None, _fmt_qty(q))


def v_export_package(ctx: RuleContext, rule: Rule) -> Outcome:
    e = ctx.decl.get("export_marking")
    if not e:
        return Outcome(NA, "No 'for export only' marking on the label.")
    return Outcome(FAIL, f"The package is marked '{e['value']}' but is offered for sale in India - it must be re-packed or re-labelled "
                         "under Chapter II before sale (Rule 25).", "Re-labelled for the Indian market", e["value"], lines=e["lines"])


# ---------------------------------------------------------------------------
# Mandatory declarations
# ---------------------------------------------------------------------------

def v_manufacturer(ctx: RuleContext, rule: Rule) -> Outcome:
    m = ctx.decl.get("manufacturer")
    qr = ctx.decl.get("qr_hint")
    expected = "Name and complete address with PIN code"
    if not m:
        imp = ctx.decl.get("importer")
        if imp:
            m = {"name": imp["name"], "address": imp["address"], "role": "imported", "confidence": imp["confidence"],
                 "address_complete": imp["pin_code"], "pin_code": imp["pin_code"], "lines": imp["lines"]}
        else:
            return _missing(ctx, "manufacturer", "Manufacturer / packer name and address", expected=expected)
    found = m.get("address") or m.get("name")
    fields = ["manufacturer"] if ctx.decl.get("manufacturer") else []
    if m.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Manufacturer details were read with low confidence.", expected, found, fields, m.get("lines", []))
    if not m.get("address_complete") or not m.get("pin_code"):
        if ctx.has("qr_allowed") and qr:
            return Outcome(PASS, f"Name of the {m.get('role', 'manufacturer')} is printed and the pack directs consumers to the QR code for the "
                                 "address, as the electronic-products proviso to Rule 6(1)(a) allows.", "Name on pack, address via QR", found,
                           fields, qr["lines"])
        what = "has no PIN code" if m.get("address_complete") else "looks incomplete"
        return Outcome(REVIEW, f"A {m.get('role', 'manufacturer')} name was found but the address {what} - Rule 10 Explanation 1 needs the "
                               "street / premises, city, State and PIN code.", expected, found, fields)
    return Outcome(PASS, f"{m.get('role', 'Manufacturer').title()} by {m.get('name')} with a complete address (PIN {m.get('pin_code')}).",
                   expected, found, fields)


def _imported(ctx: RuleContext) -> bool:
    o = ctx.decl.get("origin") or {}
    return bool(o.get("imported") or (ctx.ai or {}).get("is_imported") or ctx.decl.get("importer"))


def v_importer(ctx: RuleContext, rule: Rule) -> Outcome:
    if not _imported(ctx):
        return Outcome(NA, "Domestic product - no import markers on the label.")
    imp = ctx.decl.get("importer")
    man = ctx.decl.get("manufacturer") or {}
    if not imp and man.get("role") == "imported":
        imp = {"name": man.get("name"), "address": man.get("address"), "pin_code": bool(man.get("pin_code")), "lines": man.get("lines", [])}
    expected = "Importer's name and complete address in India"
    if not imp:
        return _missing(ctx, "manufacturer", "The importer's name and address", expected=expected)
    if not imp.get("pin_code"):
        return Outcome(REVIEW, "Importer named but the Indian address has no PIN code.", expected, imp.get("address"), lines=imp.get("lines", []))
    return Outcome(PASS, f"Imported by {imp.get('name')} with an Indian address.", expected, imp.get("address"), lines=imp.get("lines", []))


def v_country_of_origin(ctx: RuleContext, rule: Rule) -> Outcome:
    o = ctx.decl.get("origin") or {}
    if not _imported(ctx):
        return Outcome(NA, "Domestic product - no import markers on the label.", None, o.get("country") or "Domestic", ["origin"])
    if o.get("country"):
        return Outcome(PASS, f"Country of origin declared: {o['country']}.", "Country of origin", o["country"], ["origin"])
    return _missing(ctx, "country_of_origin", "Country of origin for an imported package", ["origin"], "Country of origin")


def _category_word(ctx: RuleContext) -> tuple[str, list[int]] | None:
    for k in ctx.category.strong:
        m, idx = ctx.search(r"(?<![a-z0-9])" + re.escape(k).replace(r"\ ", r"[\s\-]*") + r"(?![a-z0-9])")
        if m:
            return m.group(0), idx
    return None


def v_generic_name(ctx: RuleContext, rule: Rule) -> Outcome:
    g = ctx.decl.get("generic_name")
    if g:
        note = "" if g.get("matches_case_product") or not ctx.expected.get("product_words") else " (differs from the case product)"
        return Outcome(PASS, f"Generic name '{g['value']}' declared{note}.", "Common / generic name", g["value"], ["generic_name"])
    word = _category_word(ctx)
    if word:
        return Outcome(PASS, f"Generic name '{word[0]}' declared.", "Common / generic name", word[0], lines=word[1])
    qr = ctx.decl.get("qr_hint")
    if ctx.has("qr_allowed") and qr:
        return Outcome(PASS, "The pack directs consumers to the QR code for the generic name, as the Rule 6(1)(b) proviso for electronic "
                             "products allows.", "Generic name or QR instruction", "QR instruction", lines=qr["lines"])
    ai_name = (ctx.ai or {}).get("generic_name")
    if ai_name:
        return Outcome(REVIEW, f"OCR could not confirm the generic name; the AI reader suggests '{ai_name}'.", "Common / generic name", ai_name)
    return _missing(ctx, "generic_name", "Common or generic name of the commodity", expected="Common / generic name")


def v_group_package(ctx: RuleContext, rule: Rule) -> Outcome:
    g = ctx.decl.get("group_marking")
    if not g:
        return Outcome(NA, "Not a group, combination or multi-piece package.")
    m, idx = ctx.search(r"\b\d+\s*[x×]\s*\d+(?:[.,]\d+)?\s*(g|kg|ml|l|n)\b|\b\d+\s*(pieces|pcs|packs|packets|nos)\b.*\b\d+(?:[.,]\d+)?\s*(g|ml)\s*each")
    if m:
        return Outcome(PASS, f"Multi-piece contents declared ('{m.group(0)}') as Rule 13(6) requires.", "Number x quantity of each piece",
                       m.group(0), lines=idx)
    return Outcome(REVIEW, f"The pack looks grouped ('{g['value']}'). Confirm the number and quantity of each piece is declared "
                           "(Rule 13(6)) and that each inner pack carries its own Rule 6 declarations (Rule 4(2)).",
                   "Number x quantity of each piece", g["value"], lines=g["lines"])


def v_customer_care(ctx: RuleContext, rule: Rule) -> Outcome:
    c = ctx.decl.get("customer_care")
    expected = "Telephone and e-mail for complaints"
    if not c or not (c.get("phones") or c.get("emails")):
        if c and c.get("label"):
            return Outcome(REVIEW, "A consumer care heading was found but no phone number or e-mail could be read.", expected,
                           c.get("text"), ["customer_care"])
        return _missing(ctx, "customer_care", "Consumer care telephone / e-mail", ["customer_care"], expected)
    parts = (c.get("phones") or [])[:1] + (c.get("emails") or [])[:1]
    if c.get("phones") and c.get("emails"):
        return Outcome(PASS, "Consumer care telephone and e-mail declared.", expected, ", ".join(parts), ["customer_care"])
    gap = "e-mail address" if c.get("phones") else "telephone number"
    if ctx.weak_capture or ctx.ai_saw("customer_care"):
        return Outcome(REVIEW, f"Only one contact channel was read; the {gap} may be on the pack but unreadable.", expected,
                       ", ".join(parts), ["customer_care"])
    return Outcome(FAIL, f"The consumer care {gap} is missing - Rule 6(2) (as substituted w.e.f. 01.01.2016) requires name, address, "
                         "telephone and e-mail.", expected, ", ".join(parts), ["customer_care"])


def v_care_phone_and_email(ctx: RuleContext, rule: Rule) -> Outcome:
    c = ctx.decl.get("customer_care") or {}
    phones, emails = c.get("phones") or [], c.get("emails") or []
    expected = "Telephone and e-mail printed on the package"
    if phones and emails:
        return Outcome(PASS, "Telephone and e-mail are printed on the package itself.", expected, f"{phones[0]}, {emails[0]}", ["customer_care"])
    gap = " and ".join(x for x, ok in (("telephone", phones), ("e-mail", emails)) if not ok)
    if ctx.weak_capture:
        return Outcome(REVIEW, f"The {gap} could not be read on the package.", expected, ", ".join(phones + emails) or "Not found", ["customer_care"])
    return Outcome(FAIL, f"The {gap} must be printed on the package itself - a QR code is not enough for consumer care details.",
                   expected, ", ".join(phones + emails) or "Not found", ["customer_care"])


# ---------------------------------------------------------------------------
# Price
# ---------------------------------------------------------------------------

def _price_exempt(ctx: RuleContext) -> str | None:
    if ctx.has("mrp_exempt"):
        return f"No retail sale price is required on {ctx.category.name.lower()} (Rule 6(1)(g) proviso C(i))."
    q = ctx.decl.get("net_quantity") or {}
    if ctx.category.id == "lpg" and ctx.decl.get("lpg_domestic") and q.get("base_value") in (14200.0, 5000.0):
        return "Domestic LPG cylinder priced under the Administrative Price Mechanism - no MRP required (Rule 6(1)(g) proviso C(ii))."
    return None


def v_mrp_declared(ctx: RuleContext, rule: Rule) -> Outcome:
    exempt = _price_exempt(ctx)
    if exempt:
        return Outcome(NA, exempt)
    m = ctx.decl.get("mrp")
    if not m:
        if ctx.has("excise_price"):
            return Outcome(REVIEW, "No MRP was read. For liquor the State Excise law of the State of manufacture governs the price "
                                   "declaration - confirm against the State's excise labelling rules.", "MRP as the State Excise law requires", "Not found", ["mrp"])
        return _missing(ctx, "mrp", "Retail sale price (MRP)", ["mrp"], "MRP on the package")
    if m.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "MRP was read with low confidence.", "MRP on the package", _fmt_money(m["value"]), ["mrp"])
    return Outcome(PASS, f"MRP {_fmt_money(m['value'])} declared.", "MRP on the package", _fmt_money(m["value"]), ["mrp"])


def v_mrp_format(ctx: RuleContext, rule: Rule) -> Outcome:
    exempt = _price_exempt(ctx)
    if exempt:
        return Outcome(NA, exempt)
    m = ctx.decl.get("mrp")
    if not m:
        return Outcome(NA, "No MRP found to assess (see LM-06).", "Rs. / ₹ and 'inclusive of all taxes'", None, [])
    found = m.get("text")
    if m.get("label_missing"):
        return Outcome(REVIEW, "A price was found but it is not described as the MRP / maximum retail price.", "'MRP' or 'Maximum retail price'",
                       found, ["mrp"])
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


_PER = {"g": ("g", 1), "gm": ("g", 1), "gram": ("g", 1), "100 g": ("g", 100), "100g": ("g", 100), "kg": ("g", 1000),
        "ml": ("ml", 1), "100 ml": ("ml", 100), "100ml": ("ml", 100), "l": ("ml", 1000), "ltr": ("ml", 1000),
        "unit": ("count", 1), "n": ("count", 1), "pc": ("count", 1), "piece": ("count", 1), "nos": ("count", 1)}


def _prescribed_unit(q: dict) -> tuple[str, float] | None:
    """Rule 6(11) as substituted 2022: (label, base amount) of the prescribed unit, None when USP = MRP."""
    b, v = q["base_unit"], q["base_value"]
    if b == "g":
        return None if v == 1000 else (("g", 1) if v < 1000 else ("kg", 1000))
    if b == "ml":
        return None if v == 1000 else (("ml", 1) if v < 1000 else ("litre", 1000))
    if b == "cm":
        return None if v == 100 else (("cm", 1) if v < 100 else ("metre", 100))
    if b == "count":
        return None if v == 1 else ("unit", 1)
    return None


def _usp_exempt(ctx: RuleContext) -> str | None:
    exempt = _price_exempt(ctx)
    if exempt:
        return exempt
    if ctx.has("excise_price"):
        return "Unit sale price of liquor follows the State Excise law (Rule 6(11) first proviso)."
    if ctx.decl.get("group_marking"):
        return "Combination, group and multi-piece packages need no unit sale price (Rule 6(11) third proviso, 2023)."
    q = ctx.decl.get("net_quantity")
    if q and _prescribed_unit(q) is None and q.get("base_unit") in ("g", "ml", "cm", "count"):
        return f"For {_fmt_qty(q)} the unit sale price equals the MRP, so it need not be declared (Rule 6(11) second proviso)."
    return None


def v_unit_sale_price(ctx: RuleContext, rule: Rule) -> Outcome:
    exempt = _usp_exempt(ctx)
    if exempt:
        return Outcome(NA, exempt)
    u = ctx.decl.get("unit_sale_price")
    if u:
        return Outcome(PASS, f"Unit sale price ₹{u['value']:g} per {u['per']} declared.", "Price per unit of measure",
                       f"₹{u['value']:g}/{u['per']}", ["unit_sale_price"])
    return _missing(ctx, "unit_sale_price", "Unit sale price", ["unit_sale_price"], "Price per g / kg / ml / L / unit")


def v_unit_price_math(ctx: RuleContext, rule: Rule) -> Outcome:
    if _usp_exempt(ctx):
        return Outcome(NA, "No unit sale price is required for this package (see LM-08).")
    u, m, q = ctx.decl.get("unit_sale_price"), ctx.decl.get("mrp"), ctx.decl.get("net_quantity")
    if not (u and m and q):
        return Outcome(NA, "MRP, net quantity and unit sale price are all needed to check the arithmetic.")
    per = re.sub(r"\s+", " ", u["per"].lower())
    per_base, per_amount = _PER.get(per, (None, None))
    prescribed = _prescribed_unit(q)
    if per_base is None or per_base != q["base_unit"]:
        return Outcome(REVIEW, f"Unit sale price is per '{u['per']}', which does not match the {_fmt_qty(q)} declaration.",
                       f"per {prescribed[0]}" if prescribed else None, f"₹{u['value']:g}/{u['per']}", ["unit_sale_price", "net_quantity"])
    exp_value = m["value"] / q["base_value"] * per_amount
    got = u["value"]
    expected = f"₹{exp_value:,.2f} per {u['per']} (₹{m['value']:g} ÷ {_fmt_qty(q)})"
    found = f"₹{got:g} per {u['per']}"
    tol = 0.011 + 0.005 * exp_value
    fields = ["unit_sale_price", "mrp", "net_quantity"]
    if abs(got - exp_value) > tol:
        if abs(got - exp_value) <= 0.05 * exp_value:
            return Outcome(REVIEW, "Unit sale price is close to, but not exactly, MRP ÷ net quantity - check the rounding.", expected, found, fields)
        return Outcome(FAIL, f"Unit sale price {found} is wrong - MRP ÷ net quantity gives {expected.split(' (')[0]}.", expected, found, fields)
    if prescribed and (prescribed[1] != per_amount):
        return Outcome(REVIEW, f"Unit sale price is arithmetically correct but stated per {u['per']}; Rule 6(11) prescribes per "
                               f"{prescribed[0]} for {_fmt_qty(q)}.", f"per {prescribed[0]}", found, fields)
    return Outcome(PASS, f"Unit sale price {found} matches MRP ÷ net quantity and uses the prescribed unit.", expected, found, fields)


def v_dual_mrp(ctx: RuleContext, rule: Rule) -> Outcome:
    if _price_exempt(ctx):
        return Outcome(NA, _price_exempt(ctx))
    values = ctx.decl.get("mrp_all") or []
    sticker, idx = ctx.search(r"\b(revised|new|old|reduced)\s*m\.?\s*r\.?\s*p\b")
    if len(values) > 1:
        lines = sorted({i for v in values for i in v["lines"]})
        shown = ", ".join(_fmt_money(v["value"]) for v in values)
        return Outcome(REVIEW, f"Different MRPs appear on the pack ({shown}). Only a sticker with a lower MRP that leaves the original "
                               "visible is allowed (Rule 6(3)); a different MRP on an identical product is prohibited (Rule 18(2A)).",
                       "One MRP (or a lower-MRP sticker)", shown, lines=lines)
    if sticker:
        return Outcome(REVIEW, f"'{sticker.group(0)}' on the label - confirm the original MRP is not covered or altered (Rule 6(3), 18(5)).",
                       "Unaltered MRP", sticker.group(0), lines=idx)
    if not ctx.decl.get("mrp"):
        return Outcome(NA, "No MRP found to assess (see LM-06).")
    return Outcome(PASS, "A single MRP is declared.", "One MRP", _fmt_money(ctx.decl["mrp"]["value"]), ["mrp"])


# ---------------------------------------------------------------------------
# Quantity
# ---------------------------------------------------------------------------

def v_net_quantity(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return _missing(ctx, "net_quantity", "Net quantity", ["net_quantity"], "Net quantity in g / kg / ml / L / N")
    found = _fmt_qty(q)
    if not q.get("standard_symbol", True):
        return Outcome(REVIEW, f"Net quantity uses the non-standard symbol '{q['unit']}' (standard: g, kg, ml, L, cm, m).",
                       "Standard unit symbol", found, ["net_quantity"])
    if q.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Net quantity was read with low confidence.", "Net quantity in standard units", found, ["net_quantity"])
    label = "" if q.get("labelled") else " (no 'Net Qty' label next to it)"
    return Outcome(PASS, f"Net quantity {found} declared in standard units{label}.", "Net quantity in standard units", found, ["net_quantity"])


_BIG = {"g": ("kg", 1000), "ml": ("L", 1000), "cm": ("m", 100)}
_SMALL_UNITS = {"g": ("g", "gm", "gms", "gram", "grams", "gr", "mg"), "ml": ("ml", "mls", "cl"), "cm": ("cm", "mm")}


def v_unit_scale(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q or q["base_unit"] not in _BIG:
        return Outcome(NA, "No weight, volume or length quantity to check.")
    big, threshold = _BIG[q["base_unit"]]
    unit = q["unit"].lower()
    small = unit in _SMALL_UNITS[q["base_unit"]]
    v = q["base_value"]
    found = _fmt_qty(q)
    status = REVIEW if ctx.weak_capture or q.get("confidence", 1) < LOW_CONF else FAIL
    if small and v > threshold:
        return Outcome(status, f"{found} is {threshold:g} {q['base_unit']} or more and must be expressed in {big} "
                               f"({v / threshold:g} {big}) - Rule 13(3).", f"{v / threshold:g} {big}", found, ["net_quantity"])
    if not small and v < threshold:
        return Outcome(status, f"{found} is below 1 {big} and must be expressed in {q['base_unit']} ({v:g} {q['base_unit']}) - Rule 13(2).",
                       f"{v:g} {q['base_unit']}", found, ["net_quantity"])
    return Outcome(PASS, f"{found} uses the unit Rule 13 prescribes for that quantity.", "g / ml / cm below 1 kg / L / m, else kg / L / m",
                   found, ["net_quantity"])


def _qty_text(ctx: RuleContext) -> str:
    return " ".join([ctx.context.get("product_name") or "", ctx.joined[:600]])


def v_quantity_dimension(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return Outcome(NA, "No net quantity to check (see LM-04).")
    dims, ref = ctx.category.dims_for(_qty_text(ctx))
    have = q.get("dimension") or {"g": "mass", "ml": "volume", "count": "number", "cm": "length"}.get(q["base_unit"])
    also = {a.get("dimension") for a in (q.get("also") or [])}
    expected = " or ".join({"mass": "weight", "volume": "volume", "number": "number", "length": "length", "area": "area"}[d] for d in dims)
    found = f"{_fmt_qty(q)} ({have})"
    if have in dims or (also & set(dims)):
        return Outcome(PASS, f"{ctx.category.name}: declared by {have}, as prescribed ({ref}).", expected, found, ["net_quantity"])
    status = REVIEW if ctx.weak_capture else FAIL
    return Outcome(status, f"{ctx.category.name} must be declared by {expected} ({ref}); the label declares {found}.", expected, found,
                   ["net_quantity"])


def v_volume_with_mass(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return Outcome(NA, "No net quantity to check (see LM-04).")
    if q["base_unit"] == "g":
        return Outcome(PASS, f"Net quantity declared by weight ({_fmt_qty(q)}).", "Weight (or volume with weight)", _fmt_qty(q), ["net_quantity"])
    if q["base_unit"] != "ml":
        return Outcome(NA, "Net quantity is not declared by volume.")
    mass = next((a for a in q.get("also") or [] if a.get("base_unit") == "g"), None)
    if mass:
        return Outcome(PASS, f"Volume {_fmt_qty(q)} is accompanied by the weight {mass['value']:g} {mass['unit']}.", "Volume with weight",
                       f"{_fmt_qty(q)} ({mass['value']:g} {mass['unit']})", ["net_quantity"])
    m, idx = ctx.search(r"\b\d+(?:[.,]\d+)?\s*(g|kg|gm|grams?)\b(?!\s*(protein|fat|carb|sugar|per))")
    if m:
        return Outcome(REVIEW, f"Declared by volume ({_fmt_qty(q)}); a weight '{m.group(0)}' appears elsewhere on the label - confirm it is "
                               "the net weight of the contents.", "Volume with weight", _fmt_qty(q), ["net_quantity"], idx)
    status = REVIEW if ctx.weak_capture else FAIL
    return Outcome(status, f"{_fmt_qty(q)} is declared by volume only; the Fourth Schedule (item 11, as amended 06.10.2023) requires "
                           "the net weight as well.", "Volume with weight", _fmt_qty(q), ["net_quantity"])


def v_misleading_quantity_words(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return Outcome(NA, "No net quantity declaration to check (see LM-04).")
    w = ctx.decl.get("misleading_words")
    if w:
        own = [i for i in w["lines"] if i in (q.get("lines") or [])]
        if own:
            status = REVIEW if ctx.weak_capture else FAIL
            return Outcome(status, f"The quantity declaration is qualified by '{', '.join(w['words'])}' - Rule 12(6) prohibits words that "
                                   "give an exaggerated or misleading impression of quantity.", "Unqualified net quantity", w["text"],
                           ["net_quantity"], own)
        return Outcome(REVIEW, f"'{', '.join(w['words'])}' appears next to the quantity declaration - confirm it does not qualify the "
                               "net quantity (Rule 12(6)).", "Unqualified net quantity", w["text"], ["net_quantity"], w["lines"])
    return Outcome(PASS, "No qualifying words ('approx.', 'minimum', 'average' ...) in the quantity declaration.",
                   "Unqualified net quantity", _fmt_qty(q), ["net_quantity"])


THIRD_SCHEDULE = re.compile(r"\b(soap|lotion|cream|camphor|kapoor|kapur)\b", re.I)


def v_when_packed(ctx: RuleContext, rule: Rule) -> Outcome:
    wp = ctx.decl.get("when_packed")
    if not wp:
        return Outcome(NA, "The quantity is not qualified by 'when packed'.")
    permitted = ctx.has("third_schedule") or bool(THIRD_SCHEDULE.search(_qty_text(ctx)))
    if permitted:
        return Outcome(PASS, "'When packed' is permitted for this Third Schedule commodity (soaps, lotions, creams, camphor).",
                       "Third Schedule commodity", wp["text"], lines=wp["lines"])
    return Outcome(REVIEW if ctx.weak_capture else FAIL,
                   f"'When packed' may qualify the quantity only for Third Schedule commodities (Rule 11(4)); {ctx.category.name.lower()} "
                   "must declare the quantity the consumer receives (Rule 11(2), 11(3)).", "No 'when packed'", wp["text"], lines=wp["lines"])


def v_count_terms(ctx: RuleContext, rule: Rule) -> Outcome:
    ct = ctx.decl.get("count_terms")
    if ct:
        return Outcome(REVIEW if ctx.weak_capture else FAIL,
                       f"'{', '.join(ct['words'])}' is used - Rule 13(4) prohibits dozen, score, gross and the like; declare the number of pieces.",
                       "Number / units / pieces", ct["text"], lines=ct["lines"])
    q = ctx.decl.get("net_quantity")
    if q and q["base_unit"] == "count":
        return Outcome(PASS, f"Count declared as {_fmt_qty(q)}.", "Number / units / pieces", _fmt_qty(q), ["net_quantity"])
    pc = ctx.decl.get("piece_count")
    if "number" in ctx.category.dims and ctx.category.dims[0] == "number" and pc:
        return Outcome(PASS, f"Count declared ({pc['text']}).", "Number / units / pieces", pc["text"], lines=pc["lines"])
    return Outcome(NA, "Not sold by number, and no dozen / score / gross on the label.")


# ---------------------------------------------------------------------------
# Dates
# ---------------------------------------------------------------------------

def v_mfg_date(ctx: RuleContext, rule: Rule) -> Outcome:
    if ctx.has("mfg_date_exempt") and not ctx.has("drug"):
        return Outcome(NA, f"No month and year of manufacture is required for {ctx.category.name.lower()} (Rule 6(1)(g) proviso A).")
    if ctx.has("seeds") and re.search(r"certified|certification\s*tag|truthful", ctx.joined, re.I):
        return Outcome(NA, "Certified seeds labelled under the Seeds Act, 1966 need no month and year of manufacture (Rule 6(1)(d) proviso).")
    q = ctx.decl.get("net_quantity") or {}
    if ctx.category.id == "lpg" and ctx.decl.get("lpg_domestic") and q.get("base_value") in (14200.0, 5000.0):
        return Outcome(NA, "Domestic LPG cylinders of 14.2 kg / 5 kg of a PSU need no month and year of manufacture (Rule 6(1)(g) proviso A(ii)).")
    d = ctx.decl.get("mfg_date")
    if not d:
        return _missing(ctx, "mfg_date", "Month and year of manufacture", ["mfg_date"], "Month & year")
    if d.get("confidence", 0) < LOW_CONF:
        return Outcome(REVIEW, "Manufacture date was read with low confidence.", "Month & year", d["iso"], ["mfg_date"])
    return Outcome(PASS, f"Manufacture / packing date {d['iso']} declared.", "Month & year", d["iso"], ["mfg_date"])


def v_best_before(ctx: RuleContext, rule: Rule) -> Outcome:
    b = ctx.decl.get("best_before")
    what = "Expiry date" if ctx.has("drug") else "Best before / use-by / expiry date"
    if not b:
        return _missing(ctx, "best_before", what, ["best_before"], "Best before, use-by or expiry")
    found = b.get("iso") or f"{b.get('amount')} {b.get('unit')} from manufacture"
    return Outcome(PASS, f"{what} declared ({found}).", "Best before, use-by or expiry", found, ["best_before"])


def v_date_consistency(ctx: RuleContext, rule: Rule) -> Outcome:
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


# ---------------------------------------------------------------------------
# Legibility & presentation
# ---------------------------------------------------------------------------

def v_numeral_height(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    if not q:
        return Outcome(NA, "No quantity declaration to measure.", None, None, [])
    if not ctx.calibration.get("detected"):
        status = REVIEW if ctx.require_calibration else NA
        return Outcome(status, "No ArUco calibration marker in the frame, so physical size cannot be measured. "
                               "Place the 20 mm marker beside the pack to enable this check.", None, None, ["net_quantity"])
    meas = ctx.measurements.get("numeral_height")
    required = ctx.measurements.get("numeral_required_mm")
    pdp = ctx.measurements.get("pdp") or {}
    band = f"for a {pdp.get('area_cm2', 0):g} cm² display panel ({pdp.get('source', 'estimated')})" if pdp else ""
    if not meas or not required:
        return Outcome(REVIEW, "Numeral glyphs could not be isolated for measurement.",
                       f"≥ {required:g} mm {band}".strip() if required else None, None, ["net_quantity", "calibration"])
    got = meas["height_mm"]
    expected = f"≥ {required:g} mm {band}".strip()
    found = f"{got:.1f} mm ({meas.get('field', 'net quantity').replace('_', ' ')})"
    fields = ["net_quantity", "calibration"] + (["mrp"] if meas.get("field") == "mrp" else [])
    if got >= required:
        return Outcome(PASS, f"Smallest declaration numerals are {got:.1f} mm tall (minimum {required:g} mm {band}).", expected, found, fields)
    if got >= required * 0.9 or pdp.get("source") == "estimated" and got >= required * 0.75:
        return Outcome(REVIEW, f"Numerals measure {got:.1f} mm against a {required:g} mm minimum {band} - within measurement / panel-area "
                               "tolerance; measure the panel to confirm.", expected, found, fields)
    return Outcome(FAIL, f"Numerals are only {got:.1f} mm tall; Table-I requires at least {required:g} mm {band}.", expected, found, fields)


def v_contrast(ctx: RuleContext, rule: Rule) -> Outcome:
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


def v_language(ctx: RuleContext, rule: Rule) -> Outcome:
    lang = ctx.decl.get("language") or {}
    dev, lat = lang.get("devanagari_lines", 0), lang.get("latin_lines", 0)
    if dev or lat >= 2:
        parts = (["English"] if lat >= 2 else []) + (["Hindi (Devanagari)"] if dev else [])
        return Outcome(PASS, f"Declarations are in {' and '.join(parts)}.", "Hindi (Devanagari) or English", " + ".join(parts))
    if ctx.ocr_line_count == 0:
        return Outcome(REVIEW, "No text was read, so the language could not be confirmed.", "Hindi (Devanagari) or English", None)
    return Outcome(REVIEW, "Too little English or Devanagari text was read to confirm the declaration language.",
                   "Hindi (Devanagari) or English", f"{lat} English line(s)")


def v_image_quality(ctx: RuleContext, rule: Rule) -> Outcome:
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


# ---------------------------------------------------------------------------
# Cross-law traceability, food, cosmetics, drugs, devices
# ---------------------------------------------------------------------------

def v_batch_no(ctx: RuleContext, rule: Rule) -> Outcome:
    b = ctx.decl.get("batch_no")
    if b:
        return Outcome(PASS, f"Batch / lot number {b['value']} declared.", "Batch, lot or code number", b["value"], ["batch_no"])
    return _missing(ctx, "batch_no", "Batch / lot number", ["batch_no"], "Batch, lot or code number")


def v_fssai_licence(ctx: RuleContext, rule: Rule) -> Outcome:
    lic = ctx.decl.get("fssai_license")
    expected = "FSSAI logo with a 14-digit licence / registration number"
    if lic:
        if not lic.get("valid_format", True):
            return Outcome(REVIEW, f"A 14-digit number {lic['value']} was read but FSSAI numbers start with 1 (licence) or 2 (registration) - "
                                   "check the digits.", expected, lic["value"], ["fssai_license"])
        logo = "" if ctx.decl.get("fssai_logo_word") else " (the FSSAI logo itself could not be read as text - confirm it is printed)"
        return Outcome(PASS, f"FSSAI licence / registration number {lic['value']} declared{logo}.", expected, lic["value"], ["fssai_license"])
    if ctx.decl.get("fssai_logo_word"):
        return Outcome(REVIEW, "'FSSAI' appears on the label but the 14-digit number could not be read.", expected, "FSSAI word only")
    return _missing(ctx, "fssai_licence", "FSSAI licence number", ["fssai_license"], expected)


def v_veg_mark(ctx: RuleContext, rule: Rule) -> Outcome:
    mark = ctx.measurements.get("veg_mark") or {}
    expected = "Green (veg) or brown (non-veg) symbol on the display panel"
    if mark.get("found"):
        kind = "vegetarian (green)" if mark["kind"] == "veg" else "non-vegetarian (brown)"
        return Outcome(PASS, f"The {kind} symbol was detected on the label (score {mark['score']:.2f}).", expected, kind, ["veg_mark"])
    ai_mark = (ctx.ai or {}).get("veg_mark")
    words = ctx.decl.get("veg_words")
    if ai_mark in ("veg", "non_veg"):
        return Outcome(REVIEW, f"The AI reader reports a {'vegetarian' if ai_mark == 'veg' else 'non-vegetarian'} symbol that the detector "
                               "could not confirm - check the symbol on the pack.", expected, "AI reader only")
    if words:
        return Outcome(REVIEW, f"'{words['value']}' is written on the label but the coloured symbol was not detected - confirm it is printed.",
                       expected, words["value"], lines=words["lines"])
    return Outcome(REVIEW, "The vegetarian / non-vegetarian symbol was not detected - confirm on the pack (symbols on curved or "
                           "glossy surfaces are easy to miss).", expected, "Not detected")


def v_veg_mark_optional(ctx: RuleContext, rule: Rule) -> Outcome:
    mark = ctx.measurements.get("veg_mark") or {}
    if mark.get("found"):
        kind = "green (vegetarian)" if mark["kind"] == "veg" else "red / brown (non-vegetarian)"
        return Outcome(PASS, f"A {kind} dot is shown, as Rule 6(4A)(d) permits.", "Optional dot at the top of the display panel", kind, ["veg_mark"])
    return Outcome(NA, "No vegetarian / non-vegetarian dot - optional for soaps, shampoos, toothpaste and cosmetics since 21.09.2026 "
                       "(Rule 6(8) omitted).")


def v_ingredients(ctx: RuleContext, rule: Rule) -> Outcome:
    ing = ctx.decl.get("ingredients")
    if ing:
        return Outcome(PASS, f"Ingredient list declared ({ing.get('items', 0)} item(s) read).", "List of ingredients",
                       (ing.get("value") or "")[:90], ["ingredients"])
    return _missing(ctx, "ingredients", "List of ingredients", ["ingredients"], "List of ingredients")


NUTRIENT_CORE = ("energy", "protein", "carbohydrate", "sugars", "fat")


def v_nutrition(ctx: RuleContext, rule: Rule) -> Outcome:
    n = ctx.decl.get("nutrition")
    expected = "Energy, protein, carbohydrate, sugars, fat (per 100 g / ml)"
    if not n:
        return _missing(ctx, "nutrition", "Nutritional information", ["nutrition"], expected)
    have = [k for k in NUTRIENT_CORE if k in n["nutrients"]]
    gaps = [k for k in NUTRIENT_CORE if k not in n["nutrients"]]
    found = ", ".join(have) or "panel heading only"
    if len(have) >= 4 and n.get("per_100"):
        return Outcome(PASS, "Nutritional information declared per 100 g / ml" + (f" ({', '.join(gaps)} not read)" if gaps else "") + ".",
                       expected, found, ["nutrition"])
    reason = (f"Nutrition panel found but {', '.join(gaps)} could not be read" if gaps else "Nutrition values found") + \
             ("" if n.get("per_100") else "; the per 100 g / ml basis was not found") + " - confirm the panel."
    return Outcome(REVIEW, reason, expected, found, ["nutrition"])


def v_allergens(ctx: RuleContext, rule: Rule) -> Outcome:
    a = ctx.decl.get("allergens") or {}
    if a.get("statement"):
        return Outcome(PASS, "An allergen declaration is printed.", "Allergen declaration", a.get("text"), ["allergens"])
    sources = a.get("sources") or []
    if not ctx.decl.get("ingredients") or not sources:
        return Outcome(NA, "No common allergen was read in the ingredients.")
    return Outcome(REVIEW, f"The label mentions {', '.join(sources[:5])} but no allergen declaration ('Contains: ...') was found - "
                           "confirm the allergen statement.", "Allergen declaration", ", ".join(sources[:5]), ["ingredients"])


def _patterns(rule: Rule) -> tuple[list[str], bool]:
    p = rule.params or {}
    if p.get("all"):
        return list(p["all"]), True
    return list(p.get("patterns") or []), False


def v_text_required(ctx: RuleContext, rule: Rule) -> Outcome:
    p = rule.params or {}
    what = p.get("what", "the required statement")
    trigger = p.get("trigger")
    if trigger:
        hit = next((ctx.search(t) for t in trigger if ctx.search(t)[0]), None)
        if not hit:
            return Outcome(NA, f"Not applicable - the label does not show {p.get('trigger_what', 'the marking that triggers this rule')}.")
    patterns, need_all = _patterns(rule)
    found, missing, lines = [], [], []
    for pat in patterns:
        m, idx = ctx.search(pat)
        if m:
            found.append(m.group(0))
            lines += idx
            if not need_all:
                break
        elif need_all:
            missing.append(pat)
    ok = bool(found) and (not need_all or not missing)
    if ok:
        return Outcome(PASS, f"Found {what}: '{found[0][:80]}'.", what[0].upper() + what[1:], "; ".join(f[:60] for f in found),
                       lines=sorted(set(lines)))
    if found and need_all:
        return Outcome(REVIEW if ctx.weak_capture else FAIL, f"Only part of {what} was found ('{found[0][:60]}').",
                       what[0].upper() + what[1:], "; ".join(found), lines=sorted(set(lines)))
    if ctx.weak_capture:
        return Outcome(REVIEW, f"{what[0].upper() + what[1:]} was not found; the capture quality may have hidden it - re-scan or verify.",
                       what[0].upper() + what[1:], "Not found")
    return Outcome(FAIL, f"{what[0].upper() + what[1:]} is not on the label.", what[0].upper() + what[1:], "Not found")


def v_visual_check(ctx: RuleContext, rule: Rule) -> Outcome:
    p = rule.params or {}
    what = p.get("what", "this visual requirement")
    est = (ctx.ai or {}).get(p.get("ai_key", ""))
    try:
        est = float(est) if est is not None else None
    except (TypeError, ValueError):
        est = None
    if est is not None:
        cue = "meets" if est >= p.get("min", 0) else "falls short of"
        return Outcome(REVIEW, f"The AI reader estimates {est:.0f}%, which {cue} the requirement - an inspector must confirm {what}.",
                       f"≥ {p.get('min')}%", f"~{est:.0f}% (AI estimate)")
    return Outcome(REVIEW, f"Needs a visual check by the inspector: {what}.", f"≥ {p.get('min')}%" if p.get("min") else None, "Not measured")


LICENCE_WHAT = {"drug": "Drug manufacturing licence number", "cosmetic": "Cosmetic manufacturing licence / import registration number",
                "ayush": "AYUSH manufacturing licence number", "medical_device": "Medical device licence number",
                "insecticide": "Insecticide registration number"}


def v_licence_number(ctx: RuleContext, rule: Rule) -> Outcome:
    kind = (rule.params or {}).get("kind", "drug")
    what = LICENCE_WHAT.get(kind, "Licence number")
    lic = ctx.decl.get(f"licence_{kind}")
    if lic:
        return Outcome(PASS, f"{what} declared ({lic['value']}).", what, lic["value"], lines=lic["lines"])
    return _missing(ctx, f"licence_{kind}", what, expected=what)


def v_bis_mark(ctx: RuleContext, rule: Rule) -> Outcome:
    std = ctx.category.standards or {}
    scheme = std.get("scheme", "ISI")
    codes = set(std.get("codes") or [])
    bis = ctx.decl.get("bis") or {}
    law = std.get("law", "the applicable Quality Control Order")
    if scheme == "CRS":
        if bis.get("crs"):
            return Outcome(PASS, f"BIS Standard Mark with registration {bis['crs']} declared.", "BIS Standard Mark with R-number", bis["crs"],
                           lines=bis.get("lines", []))
        return Outcome(REVIEW, f"No BIS R-number was read. If this product is notified under the {law}, the Standard Mark with "
                               "the R-number is mandatory - check the product list.", "BIS Standard Mark with R-number", ", ".join(bis.get("codes") or []) or "Not found",
                       lines=bis.get("lines", []))
    found_codes = set(bis.get("codes") or [])
    expected = "ISI mark with " + (" / ".join(sorted(codes)) if codes else "the IS number") + " and CM/L licence"
    if codes and found_codes & codes or (not codes and found_codes):
        code = sorted(found_codes & codes or found_codes)[0]
        if bis.get("cml"):
            return Outcome(PASS, f"ISI mark details declared: {code}, licence CM/L-{bis['cml']}.", expected, f"{code}, CM/L-{bis['cml']}",
                           lines=bis.get("lines", []))
        return Outcome(REVIEW, f"{code} is printed but the BIS licence number (CM/L-) could not be read - confirm the ISI mark.",
                       expected, code, lines=bis.get("lines", []))
    if found_codes:
        return Outcome(REVIEW, f"{', '.join(sorted(found_codes))} is printed, which is not the standard notified for "
                               f"{ctx.category.name.lower()} ({', '.join(sorted(codes))}).", expected, ", ".join(sorted(found_codes)), lines=bis.get("lines", []))
    if std.get("strict") and not ctx.weak_capture:
        return Outcome(FAIL, f"No ISI mark / IS number was found - mandatory for {ctx.category.name.lower()} under the {law}.", expected, "Not found")
    return Outcome(REVIEW, f"No ISI mark / IS number was read - confirm whether the product is covered by the {law}.", expected, "Not found")


# ---------------------------------------------------------------------------
# Textiles, dimensions, sheets, containers, wholesale
# ---------------------------------------------------------------------------

def v_size_metric(ctx: RuleContext, rule: Rule) -> Outcome:
    s = ctx.decl.get("size")
    expected = "Size in cm / m (optionally with S, M, L ...)"
    if s and s.get("metric"):
        return Outcome(PASS, "Garment size is given in metric notation.", expected, s["text"][:80], lines=s["lines"])
    if s and s.get("letters"):
        return Outcome(REVIEW if ctx.weak_capture else FAIL, "Only a letter size (S / M / L ...) was found - the size must also be given in "
                                                              "cm or m (Rule 26(f)(iii)).", expected, s["text"][:80], lines=s["lines"])
    return _missing(ctx, "size", "Garment size in metric notation", expected=expected)


def v_dimensions(ctx: RuleContext, rule: Rule) -> Outcome:
    p = rule.params or {}
    what = p.get("what", "the dimensions")
    d = ctx.decl.get("dimensions")
    q = ctx.decl.get("net_quantity")
    count = (q if q and q["base_unit"] == "count" else None) or ctx.decl.get("piece_count")
    if d and (count or not p.get("require_count")):
        c = _fmt_qty(count) if count and "unit" in count else (count or {}).get("text", "")
        return Outcome(PASS, f"Number and dimensions declared ({', '.join(d['values'][:2])}{'; ' + c if c else ''}).", what,
                       ", ".join(d["values"][:2]), lines=d["lines"])
    if d:
        return Outcome(REVIEW, "Dimensions are declared but the number of pieces could not be read.", what, ", ".join(d["values"][:2]), lines=d["lines"])
    if count:
        return Outcome(REVIEW if ctx.weak_capture else FAIL, "The number is declared but not the dimensions of each piece.", what,
                       count.get("text") or _fmt_qty(count))
    return _missing(ctx, "dimensions", what[0].upper() + what[1:], expected=what)


SIZE_KEY = {"tyres": "tyre_size", "footwear": "foot_size", "helmets": "helmet_size"}


def v_size_declared(ctx: RuleContext, rule: Rule) -> Outcome:
    key = SIZE_KEY.get(ctx.category.id)
    s = ctx.decl.get(key) if key else (ctx.decl.get("size") or ctx.decl.get("dimensions"))
    if s:
        return Outcome(PASS, "The size of the commodity is declared.", "Size / dimensions", s.get("text", "")[:80], lines=s.get("lines", []))
    return _missing(ctx, key or "size", f"The size of the {ctx.category.name.lower()}", expected="Size / dimensions")


def v_sheet_count(ctx: RuleContext, rule: Rule) -> Outcome:
    n = ctx.decl.get("sheet_count")
    d = ctx.decl.get("dimensions")
    expected = "Number of usable sheets and size of each sheet"
    if n and d:
        return Outcome(PASS, f"{n['value']} sheets of {d['values'][0]} declared.", expected, f"{n['value']} sheets, {d['values'][0]}",
                       lines=sorted(set(n["lines"] + d["lines"])))
    if n or d:
        gap = "the size of each sheet" if n else "the number of usable sheets"
        have = f"{n['value']} sheets" if n else d["values"][0]
        return Outcome(REVIEW if ctx.weak_capture else FAIL, f"{gap[0].upper() + gap[1:]} is missing (Rule 16).", expected, have,
                       lines=(n or d)["lines"])
    return _missing(ctx, "sheet_count", "The number of usable sheets and sheet size", expected=expected)


def v_wholesale_count(ctx: RuleContext, rule: Rule) -> Outcome:
    q = ctx.decl.get("net_quantity")
    w = ctx.decl.get("wholesale_marking")
    pc = ctx.decl.get("piece_count")
    expected = "Number of retail packages or net quantity"
    if w and re.search(r"\d", str(w.get("value"))):
        return Outcome(PASS, f"Number of retail packages declared ('{w['value']}').", expected, w["value"], lines=w["lines"])
    if q:
        return Outcome(PASS, f"Net quantity {_fmt_qty(q)} declared.", expected, _fmt_qty(q), ["net_quantity"])
    if pc:
        return Outcome(PASS, f"Count declared ({pc['text']}).", expected, pc["text"], lines=pc["lines"])
    return _missing(ctx, "net_quantity", "The number of retail packages or the net quantity", ["net_quantity"], expected)


# ---------------------------------------------------------------------------
# Case cross-checks
# ---------------------------------------------------------------------------

def v_mrp_matches_case(ctx: RuleContext, rule: Rule) -> Outcome:
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


def v_quantity_matches_case(ctx: RuleContext, rule: Rule) -> Outcome:
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
    "categorisation": v_categorisation,
    "chapter2_scope": v_chapter2_scope,
    "small_pack_exemption": v_small_pack_exemption,
    "export_package": v_export_package,
    "manufacturer": v_manufacturer,
    "importer": v_importer,
    "country_of_origin": v_country_of_origin,
    "generic_name": v_generic_name,
    "group_package": v_group_package,
    "customer_care": v_customer_care,
    "care_phone_and_email": v_care_phone_and_email,
    "mrp_declared": v_mrp_declared,
    "mrp_format": v_mrp_format,
    "unit_sale_price": v_unit_sale_price,
    "unit_price_math": v_unit_price_math,
    "dual_mrp": v_dual_mrp,
    "net_quantity": v_net_quantity,
    "unit_scale": v_unit_scale,
    "quantity_dimension": v_quantity_dimension,
    "volume_with_mass": v_volume_with_mass,
    "misleading_quantity_words": v_misleading_quantity_words,
    "when_packed": v_when_packed,
    "count_terms": v_count_terms,
    "mfg_date": v_mfg_date,
    "best_before": v_best_before,
    "date_consistency": v_date_consistency,
    "numeral_height": v_numeral_height,
    "contrast": v_contrast,
    "language": v_language,
    "image_quality": v_image_quality,
    "batch_no": v_batch_no,
    "fssai_licence": v_fssai_licence,
    "veg_mark": v_veg_mark,
    "veg_mark_optional": v_veg_mark_optional,
    "ingredients": v_ingredients,
    "nutrition": v_nutrition,
    "allergens": v_allergens,
    "text_required": v_text_required,
    "visual_check": v_visual_check,
    "licence_number": v_licence_number,
    "bis_mark": v_bis_mark,
    "size_metric": v_size_metric,
    "dimensions": v_dimensions,
    "size_declared": v_size_declared,
    "sheet_count": v_sheet_count,
    "wholesale_count": v_wholesale_count,
    "mrp_matches_case": v_mrp_matches_case,
    "quantity_matches_case": v_quantity_matches_case,
}


def evaluate(rules: list[Rule], ctx: RuleContext) -> list[tuple[Rule, Outcome]]:
    gate = scope_gate(ctx)
    results = []
    for rule in rules:
        if gate and rule.scope == "retail":
            results.append((rule, Outcome(NA, gate["reason"])))
            continue
        try:
            outcome = VALIDATORS[rule.validator](ctx, rule)
        except Exception as exc:  # a broken validator must never silently pass a package
            outcome = Outcome(REVIEW, f"Automated check could not complete ({exc.__class__.__name__}); verify manually.")
        results.append((rule, outcome))
    return results


def applicability(rule: Rule, category: Category, glossary: dict) -> str:
    """Plain-language reason a rule was applied to this category."""
    positives = [s for s in rule.applies_to if not s.startswith("-")]
    if not positives or positives == ["all"]:
        base = {"wholesale": "Every wholesale package (Chapter III)", "always": "Every package inspected",
                "retail": "Every retail package (Chapter II)"}[rule.scope]
        return base
    for s in positives:
        if cat_mod._match(s, category):
            kind, _, value = s.partition(":")
            if kind == "cat":
                return f"Category: {category.name}"
            if kind == "group":
                return f"Product group: {value}"
            return glossary.get(value, value.replace("_", " ").capitalize()).split(" - ")[0].split(" (")[0]
    return "Selected for this category"


def verdict(statuses: list[str]) -> str:
    if FAIL in statuses:
        return FAIL
    if REVIEW in statuses:
        return REVIEW
    return PASS
