"""Field extraction: turns OCR lines into the declarations required by the Legal Metrology
(Packaged Commodities) Rules - MRP, net quantity, dates, manufacturer, consumer care, origin...

Every field keeps the OCR line indices it came from, so the evidence builder can crop exactly the
region that supports each compliance decision.
"""

from __future__ import annotations

import calendar
import re
from dataclasses import dataclass, field
from datetime import date

from ..ocr.engine import OcrLine

# ---------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------

_DIGIT_FIX = str.maketrans({"O": "0", "o": "0", "D": "0", "I": "1", "l": "1", "|": "1", "S": "5", "B": "8", "Z": "2"})


def fix_number(raw: str) -> float | None:
    """'1O5,50' -> 105.5 ; tolerant to common OCR digit confusions."""
    s = raw.strip().translate(_DIGIT_FIX).replace(" ", "")
    if s.count(",") == 1 and s.count(".") == 0 and len(s.split(",")[1]) in (1, 2):
        s = s.replace(",", ".")
    s = s.replace(",", "")
    try:
        return float(s)
    except ValueError:
        return None


def normalise_text(text: str) -> str:
    t = text.replace("`", "₹").replace("₨", "₹").replace("|", "I")
    t = re.sub(r"\bR[sS]\s*[.,:]?\s*(?=\d)", "Rs. ", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip()


# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

MONTHS = {m.lower(): i for i, m in enumerate(calendar.month_abbr) if m}
MONTHS.update({m.lower(): i for i, m in enumerate(calendar.month_name) if m})
MONTHS["sept"] = 9
_MON = r"(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

DATE_PATTERNS = [
    ("dmy", re.compile(r"\b(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{4}|\d{2})\b")),
    ("ymd", re.compile(r"\b(20\d{2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{1,2})\b")),
    ("d_mon_y", re.compile(r"\b(\d{1,2})\s*[\-./ ]?\s*" + _MON + r"\.?\s*[\-./' ]?\s*(\d{4}|\d{2})\b", re.I)),
    ("mon_y", re.compile(r"\b" + _MON + r"\.?\s*[\-./' ]?\s*(\d{4}|\d{2})\b", re.I)),
    ("my", re.compile(r"\b(\d{1,2})\s*[/.\-]\s*(20\d{2}|\d{2})\b")),
]

MFG_LABEL = re.compile(
    r"(date\s*of\s*(?:mfg|manufactur\w*|packing|packaging|pkg|import)|month\s*(?:&|and)\s*year\s*of\s*(?:mfg|manufactur\w*|pack\w*|pkg)"
    r"|mfg\.?\s*(?:date|dt|on)?|mfd\.?\s*(?:date|dt|on)?|manufactured\s*on|packed\s*on|pkd\.?\s*(?:on|date)?|pkg\.?\s*(?:date|dt)|packing\s*date|d\.?\s*o\.?\s*m\.?)"
    r"(?!\s*(?:&\s*)?(?:by|for)\b)",
    re.I,
)
BB_LABEL = re.compile(r"(best\s*before|best\s*by|use\s*by|use\s*before|expiry\s*date|exp(?:iry)?\.?\s*(?:date|dt)?|expires?\s*on|\bbb\b)", re.I)
BB_DURATION = re.compile(
    r"(?:best\s*before|use\s*(?:by|within)|consume\s*within|shelf\s*life)\D{0,20}?(\d{1,2})\s*(months?|mths?|days?|years?|yrs?)"
    r"(?:\s*(?:from|after|of)\s*(?:the\s*)?(?:date\s*of\s*)?(?:mfg|manufactur\w*|pack\w*|pkg|pkd))?",
    re.I,
)

MRP_LABEL = re.compile(r"(m\s*\.?\s*r\s*\.?\s*p\b\.?|max(?:imum)?\.?\s*retail\s*(?:sale\s*)?price|retail\s*sale\s*price|\brsp\b)", re.I)
CURRENCY = re.compile(r"(₹|\brs\b\.?|\binr\b|rupees)", re.I)
PRICE_AFTER_LABEL = re.compile(
    r"^[\s:.\-]*(?:\(?\s*(?:incl\w*\.?\s*(?:of\s*)?all\s*tax\w*|inclusive\s*of\s*(?:all\s*)?taxes)\s*\)?\s*[:.\-]?\s*)?"
    r"(₹|rs\.?|inr|rupees)?\s*[:.\-]?\s*([0-9][0-9OoIl]{0,5}(?:[.,][0-9Oo]{1,2})?)\s*(/-)?",
    re.I,
)
TAX_INCLUSIVE = re.compile(r"(incl\w*\.?\s*(?:of\s*)?(?:all\s*)?tax\w*|inclusive\s*of\s*(?:all\s*)?tax\w*|\(?\s*incl\.?\s*taxes\s*\)?)", re.I)

UNIT_PRICE = re.compile(
    r"(?:unit\s*sale\s*price|u\.?\s*s\.?\s*p\.?|unit\s*price)\s*[:\-]?\s*(?:₹|rs\.?|inr)?\s*([0-9][0-9OoIl.,]{0,7})\s*(?:/|per)\s*"
    r"(?:1\s*)?(100\s*g|100\s*ml|kg|g|gm|l|ltr|ml|unit|n|pc|piece|nos)\b",
    re.I,
)
UNIT_PRICE_LOOSE = re.compile(
    r"(?:₹|rs\.?|inr)\s*([0-9][0-9OoIl.,]{0,7})\s*(?:/|per)\s*(?:1\s*)?(100\s*g|100\s*ml|kg|g|l|ltr|ml|unit|pc)\b", re.I)

UNITS = {
    "kg": ("g", 1000.0, True), "kgs": ("g", 1000.0, False),
    "g": ("g", 1.0, True), "gm": ("g", 1.0, False), "gms": ("g", 1.0, False), "gr": ("g", 1.0, False),
    "gram": ("g", 1.0, True), "grams": ("g", 1.0, True), "mg": ("g", 0.001, True),
    "l": ("ml", 1000.0, True), "ltr": ("ml", 1000.0, False), "ltrs": ("ml", 1000.0, False),
    "litre": ("ml", 1000.0, True), "litres": ("ml", 1000.0, True), "liter": ("ml", 1000.0, True), "liters": ("ml", 1000.0, True),
    "ml": ("ml", 1.0, True), "mls": ("ml", 1.0, False), "cl": ("ml", 10.0, True),
    "n": ("count", 1.0, True), "nos": ("count", 1.0, False), "pcs": ("count", 1.0, False), "pieces": ("count", 1.0, True),
    "u": ("count", 1.0, True), "unit": ("count", 1.0, True), "units": ("count", 1.0, True),
}
_UNIT_RX = r"(kgs?|gms?|grams?|gr|mg|g|ltrs?|litres?|liters?|l|mls?|ml|cl|nos|pcs|pieces|units?|n|u)"
QTY = re.compile(r"(?<![\d.])([0-9][0-9OoIl]*(?:[.,][0-9]+)?)\s*" + _UNIT_RX + r"\b", re.I)
NETQ_LABEL = re.compile(
    r"(net\s*(?:qty|quantity|wt|weight|vol|volume|content|contents|mass)\w*\.?|\bn\.?\s*wt\.?|\bnet\b)", re.I)
NUTRITION_CONTEXT = re.compile(r"(per\s*100|/\s*100|per\s*serv|serving|energy|kcal|protein|fat|carbohydrate|sugar|sodium|"
                               r"unit\s*sale|u\.?s\.?p|/\s*kg|/\s*g\b|per\s*kg|per\s*g\b)", re.I)

MANUFACTURER = re.compile(
    r"\b(?:mfd\.?|mf?g\.?|manufactured|packed|pkd\.?|marketed|mktd\.?|mkd\.?|imported|distributed|processed|bottled)"
    r"\s*(?:(?:&|and)\s*(?:marketed|packed|distributed)\s*)?(?:by|for)\b\s*[:\-]?\s*(.*)",
    re.I,
)
ADDRESS_HINT = re.compile(
    r"(\b[1-9]\d{2}\s?\d{3}\b|\broad\b|\brd\b|street|\bst\.|nagar|sector|plot|industrial|\barea\b|estate|\bdist|district|"
    r"village|tehsil|marg|lane|phase|block|floor|colony|complex|park|india|pradesh|uttarakhand|gujarat|maharashtra|"
    r"karnataka|tamil|kerala|punjab|haryana|rajasthan|bengal|bihar|delhi|telangana|odisha|assam|goa)",
    re.I,
)
CARE_LABEL = re.compile(r"(customer\s*care|consumer\s*care|customer\s*service|consumer\s*service|helpline|toll\s*free|"
                        r"complaint|feedback|for\s*(?:any\s*)?quer|contact\s*us|care\s*no|cust\.?\s*care)", re.I)
PHONE = re.compile(r"((?:\+?91[\-\s]?)?(?:1800[\-\s]?\d{3}[\-\s]?\d{3,4}|1860[\-\s]?\d{3}[\-\s]?\d{4}|[6-9]\d{4}[\-\s]?\d{5}|0\d{2,4}[\-\s]?\d{6,8}))")
EMAIL = re.compile(r"([A-Za-z0-9._%+\-]+\s?@\s?[A-Za-z0-9.\-]+\s?\.\s?[A-Za-z]{2,})")

ORIGIN = re.compile(r"(?:country\s*of\s*origin|made\s*in|product\s*of|origin)\s*[:\-]?\s*([A-Za-z][A-Za-z .]{2,30})", re.I)
IMPORT_HINT = re.compile(r"(imported\s*(?:&|and)?\s*(?:marketed\s*)?by|\bimporter\b|date\s*of\s*import|import\s*date)", re.I)
BATCH = re.compile(r"\b(?:batch\s*(?:no|number|code)?|lot\s*(?:no|number)?|b\.?\s*no)\.?\s*[:\-#]?\s*([A-Z0-9][A-Z0-9\-/]{2,15})", re.I)
FSSAI = re.compile(r"(?:fssai|lic(?:ence|ense)?\.?\s*no\.?)\D{0,12}(\d{14})", re.I)

GENERIC_WORDS = [
    "basmati rice", "rice", "atta", "wheat flour", "flour", "maida", "besan", "sooji", "rava", "poha",
    "refined oil", "sunflower oil", "mustard oil", "groundnut oil", "soyabean oil", "edible oil", "oil", "ghee", "butter",
    "salt", "sugar", "jaggery", "dal", "toor", "moong", "chana", "rajma", "pulses",
    "tea", "coffee", "biscuits", "biscuit", "cookies", "noodles", "pasta", "namkeen", "chips", "snacks",
    "detergent", "washing powder", "soap", "dishwash", "shampoo", "toothpaste", "cleaner",
    "honey", "milk", "paneer", "curd", "water", "juice", "spices", "masala", "turmeric", "chilli", "almonds", "cashew",
    "cereal", "baby food", "jam", "ketchup", "sauce", "pickle", "vinegar",
]
FOOD_CATEGORIES = {"food grains", "edible oils", "pulses", "staples", "beverages", "packaged water", "dairy", "snacks",
                   "health foods", "baby care", "dry fruits", "spices", "confectionery", "bakery"}
FOOD_HINT = re.compile(r"(ingredients|nutrition|nutritional|energy|kcal|fssai|veg(?:etarian)?|best\s*before|use\s*by|allergen)", re.I)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

@dataclass
class Found:
    value: dict
    text: str
    lines: list[int]
    confidence: float
    extra: dict = field(default_factory=dict)

    def to_json(self) -> dict:
        return {**self.value, "text": self.text, "lines": self.lines, "confidence": round(self.confidence, 3), **self.extra}


def _conf(lines: list[OcrLine], idx: list[int]) -> float:
    return min((lines[i].confidence for i in idx), default=0.0)


def _windows(texts: list[str], max_span: int = 2):
    """Yields (joined_text, [line indices]) for single lines first, then pairs of consecutive lines."""
    for span in range(1, max_span + 1):
        for i in range(len(texts) - span + 1):
            yield " ".join(texts[i:i + span]), list(range(i, i + span))


def parse_date(text: str) -> tuple[dict, tuple[int, int]] | None:
    """Returns ({year, month, day?, iso}, span) for the first date in the text."""
    best = None
    for kind, rx in DATE_PATTERNS:
        m = rx.search(text)
        if not m:
            continue
        try:
            if kind == "dmy":
                d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            elif kind == "ymd":
                y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            elif kind == "d_mon_y":
                d, mo, y = int(m.group(1)), MONTHS[m.group(2).lower()[:3]], int(m.group(3))
            elif kind == "mon_y":
                d, mo, y = None, MONTHS[m.group(1).lower()[:3]], int(m.group(2))
            else:
                d, mo, y = None, int(m.group(1)), int(m.group(2))
        except (KeyError, ValueError):
            continue
        if y < 100:
            y += 2000
        if not (1 <= mo <= 12) or not (1990 <= y <= 2100):
            continue
        if d is not None and not (1 <= d <= calendar.monthrange(y, mo)[1]):
            continue
        cand = ({"year": y, "month": mo, "day": d,
                 "iso": f"{y:04d}-{mo:02d}" + (f"-{d:02d}" if d else ""), "precision": "day" if d else "month"}, m.span())
        if best is None or cand[1][0] < best[1][0]:
            best = cand
    return best


def _add_months(y: int, m: int, months: int) -> tuple[int, int]:
    total = y * 12 + (m - 1) + months
    return total // 12, total % 12 + 1


def quantity_from_text(text: str) -> dict | None:
    m = QTY.search(text or "")
    if not m:
        return None
    value = fix_number(m.group(1))
    unit = m.group(2).lower()
    if value is None or unit not in UNITS:
        return None
    base, factor, standard = UNITS[unit]
    return {"value": value, "unit": m.group(2), "base_unit": base, "base_value": round(value * factor, 3),
            "standard_symbol": standard}


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------

def extract(lines: list[OcrLine], context: dict | None = None, today: date | None = None) -> dict:
    context = context or {}
    today = today or date.today()
    texts = [normalise_text(ln.text) for ln in lines]
    full = "\n".join(texts)
    out: dict[str, dict | None] = {}

    # ---- MRP --------------------------------------------------------------
    mrp = None
    for joined, idx in _windows(texts):
        for m in MRP_LABEL.finditer(joined):
            after = joined[m.end():]
            pm = PRICE_AFTER_LABEL.match(after)
            if not pm:
                continue
            value = fix_number(pm.group(2))
            tail = after[pm.end():pm.end() + 12]
            if value is None or value <= 0 or re.match(r"\s*(/|per)\s*(1\s*)?(kg|g|l|ml|100)", tail, re.I):
                continue
            currency = bool(pm.group(1)) or bool(CURRENCY.search(joined))
            mrp = Found({"value": value, "currency_symbol": currency}, joined, idx, _conf(lines, idx))
            break
        if mrp:
            break
    if mrp is None:  # price with currency on a line that also says "price"
        for joined, idx in _windows(texts, 1):
            if re.search(r"price", joined, re.I) and not re.search(r"unit", joined, re.I):
                cm = re.search(r"(₹|rs\.?|inr)\s*([0-9][0-9OoIl]{0,5}(?:[.,][0-9]{1,2})?)", joined, re.I)
                if cm and fix_number(cm.group(2)):
                    mrp = Found({"value": fix_number(cm.group(2)), "currency_symbol": True}, joined, idx, _conf(lines, idx),
                                {"label_missing": True})
                    break
    if mrp is not None:
        tax_idx = [i for i, t in enumerate(texts) if TAX_INCLUSIVE.search(t)]
        near = [i for i in tax_idx if min(abs(i - j) for j in mrp.lines) <= 2]
        mrp.value["inclusive_of_taxes"] = bool(near) or bool(TAX_INCLUSIVE.search(mrp.text))
        if near:
            mrp.extra["tax_lines"] = near
    out["mrp"] = mrp.to_json() if mrp else None

    # ---- Unit sale price ----------------------------------------------------
    usp = None
    for rx in (UNIT_PRICE, UNIT_PRICE_LOOSE):
        for joined, idx in _windows(texts):
            m = rx.search(joined)
            if m and fix_number(m.group(1)):
                usp = Found({"value": fix_number(m.group(1)), "per": re.sub(r"\s+", " ", m.group(2).lower())}, joined, idx, _conf(lines, idx))
                break
        if usp:
            break
    out["unit_sale_price"] = usp.to_json() if usp else None

    # ---- Net quantity ------------------------------------------------------
    netq = None
    for joined, idx in _windows(texts):
        lm = NETQ_LABEL.search(joined)
        if not lm:
            continue
        q = quantity_from_text(joined[lm.end():lm.end() + 40])
        if q:
            netq = Found(q, joined, idx, _conf(lines, idx), {"labelled": True})
            break
    if netq is None:
        candidates = []
        for i, t in enumerate(texts):
            if NUTRITION_CONTEXT.search(t) or re.search(r"\b(pack|case)\s*of\b", t, re.I):
                continue
            q = quantity_from_text(t)
            if q and q["base_unit"] in ("g", "ml") and q["base_value"] >= 1:
                candidates.append((lines[i].box[3], i, q))
        if candidates:
            _, i, q = max(candidates)  # the most prominent (tallest) quantity on the label
            netq = Found(q, texts[i], [i], lines[i].confidence, {"labelled": False})
    out["net_quantity"] = netq.to_json() if netq else None

    # ---- Dates -------------------------------------------------------------
    mfg = None
    for joined, idx in _windows(texts):
        for lm in MFG_LABEL.finditer(joined):
            parsed = parse_date(joined[lm.end():lm.end() + 40])
            if parsed:
                mfg = Found(parsed[0], joined, idx, _conf(lines, idx), {"label": lm.group(1).strip()})
                break
        if mfg:
            break
    out["mfg_date"] = mfg.to_json() if mfg else None

    bb = None
    for joined, idx in _windows(texts):
        dm = BB_DURATION.search(joined)
        if dm:
            n = int(dm.group(1))
            unit = dm.group(2).lower()
            months = n if unit.startswith("m") else n * 12 if unit.startswith("y") else None
            value = {"relative": True, "amount": n, "unit": unit}
            if mfg and months is not None:
                y, mo = _add_months(mfg.value["year"], mfg.value["month"], months)
                value.update({"year": y, "month": mo, "day": None, "iso": f"{y:04d}-{mo:02d}", "precision": "month"})
            elif mfg and unit.startswith("d"):
                value.update({"days": n})
            bb = Found(value, joined, idx, _conf(lines, idx))
            break
        lm = BB_LABEL.search(joined)
        if lm:
            parsed = parse_date(joined[lm.end():lm.end() + 40])
            if parsed:
                bb = Found(parsed[0], joined, idx, _conf(lines, idx), {"label": lm.group(1).strip()})
                break
    out["best_before"] = bb.to_json() if bb else None

    # ---- Manufacturer / packer -------------------------------------------
    man = None
    stop = re.compile(r"(m\.?r\.?p|net\s*(qty|wt|quantity)|batch|lot\s*no|best\s*before|mfg\.?\s*date|customer\s*care|consumer\s*care|"
                      r"fssai|ingredients|nutrition)", re.I)
    for i, t in enumerate(texts):
        m = MANUFACTURER.search(t)
        if not m:
            continue
        parts, idx = [m.group(1).strip(" :-,")], [i]
        for j in range(i + 1, min(i + 4, len(texts))):
            if stop.search(texts[j]) or MANUFACTURER.search(texts[j]):
                break
            parts.append(texts[j])
            idx.append(j)
            if re.search(r"\b[1-9]\d{2}\s?\d{3}\b", texts[j]):  # address usually ends with the PIN code
                break
        body = ", ".join(p for p in parts if p)
        name = re.split(r",|\s{2,}", body)[0].strip() if body else ""
        man = Found({"role": re.match(r"\w+", t[m.start():]).group(0).lower(), "name": name, "address": body,
                     "address_complete": bool(ADDRESS_HINT.search(body)) and len(body) >= 15,
                     "pin_code": (re.search(r"\b([1-9]\d{2}\s?\d{3})\b", body).group(1).replace(" ", "")
                                  if re.search(r"\b([1-9]\d{2}\s?\d{3})\b", body) else None)},
                    t, idx, _conf(lines, idx))
        if man.value["address_complete"]:
            break
    out["manufacturer"] = man.to_json() if man else None

    # ---- Consumer care ------------------------------------------------------
    phones, emails, care_idx = [], [], []
    label_seen = False
    for i, t in enumerate(texts):
        if CARE_LABEL.search(t):
            label_seen = True
            care_idx.append(i)
        for pm in PHONE.finditer(t):
            if not re.search(r"(batch|lot|fssai|lic)", t, re.I):
                phones.append(re.sub(r"\s", "", pm.group(1)))
                care_idx.append(i)
        for em in EMAIL.finditer(t):
            emails.append(re.sub(r"\s", "", em.group(1)))
            care_idx.append(i)
    care_idx = sorted(set(care_idx))
    out["customer_care"] = ({"label": label_seen, "phones": sorted(set(phones)), "emails": sorted(set(emails)),
                             "lines": care_idx, "confidence": round(_conf(lines, care_idx), 3),
                             "text": " | ".join(texts[i] for i in care_idx[:3])}
                            if (label_seen or phones or emails) else None)

    # ---- Origin / import -----------------------------------------------------
    origin = None
    for i, t in enumerate(texts):
        m = ORIGIN.search(t)
        if m:
            country = re.split(r"[,.;|]", m.group(1))[0].strip().title()
            origin = Found({"country": country}, t, [i], lines[i].confidence)
            break
    import_idx = [i for i, t in enumerate(texts) if IMPORT_HINT.search(t)]
    imported = bool(import_idx) or bool(origin and origin.value["country"].lower() not in ("india", "bharat"))
    out["origin"] = {**(origin.to_json() if origin else {"country": None, "lines": []}),
                     "imported": imported, "import_lines": import_idx}

    # ---- Batch / licence -----------------------------------------------------
    batch = None
    for i, t in enumerate(texts):
        m = BATCH.search(t)
        if m and re.search(r"\d", m.group(1)):
            batch = Found({"value": m.group(1).upper()}, t, [i], lines[i].confidence)
            break
    out["batch_no"] = batch.to_json() if batch else None
    lic = None
    for i, t in enumerate(texts):
        m = FSSAI.search(t)
        if m:
            lic = Found({"value": m.group(1)}, t, [i], lines[i].confidence)
            break
    out["fssai_license"] = lic.to_json() if lic else None

    # ---- Generic name ---------------------------------------------------------
    low = full.lower()
    wanted = []
    for src in (context.get("product_name"), context.get("category")):
        if src:
            wanted += [w for w in GENERIC_WORDS if re.search(r"\b" + re.escape(w) + r"\b", src.lower())]
    generic = None
    for word in wanted + GENERIC_WORDS:
        m = re.search(r"\b" + re.escape(word) + r"s?\b", low)
        if m:
            line_i = next((i for i, t in enumerate(texts) if re.search(r"\b" + re.escape(word), t.lower())), None)
            generic = {"value": word, "matches_case_product": word in wanted,
                       "lines": [line_i] if line_i is not None else [],
                       "confidence": round(lines[line_i].confidence, 3) if line_i is not None else 0.0,
                       "text": texts[line_i] if line_i is not None else word}
            break
    out["generic_name"] = generic

    # ---- Context ---------------------------------------------------------------
    category = (context.get("category") or "").strip().lower()
    out["is_food"] = {"value": category in FOOD_CATEGORIES or bool(FOOD_HINT.search(full)),
                      "source": "case category" if category in FOOD_CATEGORIES else "label text" if FOOD_HINT.search(full) else "none"}
    out["_meta"] = {"line_count": len(lines), "today": today.isoformat()}
    return out


def expected_from_context(context: dict) -> dict:
    """What the case record says the package should be (used by the cross-check rules)."""
    exp: dict = {}
    if context.get("declared_mrp") not in (None, ""):
        try:
            exp["mrp"] = float(context["declared_mrp"])
        except (TypeError, ValueError):
            pass
    q = quantity_from_text(context.get("declared_quantity") or "") or quantity_from_text(context.get("product_name") or "")
    if q:
        exp["quantity"] = q
    return exp
