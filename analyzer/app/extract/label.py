"""Category-specific label facts: licence and certification numbers, ingredient / nutrition / allergen
panels, statutory warnings, dimensions and sheet counts, quantity qualifiers and package-type markers.

Like the core fields, every fact keeps the OCR line indices it was read from so the evidence builder
can crop the exact region that supports each rule.
"""

from __future__ import annotations

import re

LINE_RE = "lines"


def _hits(texts: list[str], rx: re.Pattern, limit: int = 6) -> list[int]:
    return [i for i, t in enumerate(texts) if rx.search(t)][:limit]


def _first(texts: list[str], rx: re.Pattern) -> tuple[int, re.Match] | None:
    for i, t in enumerate(texts):
        m = rx.search(t)
        if m:
            return i, m
    return None


def _found(texts, lines, idx: list[int], **value) -> dict:
    conf = min((lines[i].confidence for i in idx), default=0.0)
    return {**value, "lines": idx, "text": " | ".join(texts[i] for i in idx[:3]), "confidence": round(conf, 3)}


# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

INGREDIENTS = re.compile(r"\b(ingredients?|composition|made\s*(from|with)\s*:)|सामग्री", re.I)
NUTRIENTS = {
    "energy": re.compile(r"\b(energy|kcal|calories)\b", re.I),
    "protein": re.compile(r"\bprotein", re.I),
    "carbohydrate": re.compile(r"\bcarbohydrates?\b|\bcarbs?\b", re.I),
    "sugars": re.compile(r"\b(total\s*)?sugars?\b", re.I),
    "fat": re.compile(r"\b(total\s*)?fat\b", re.I),
    "sodium": re.compile(r"\bsodium\b|\bsalt\b", re.I),
}
NUTRITION_HEAD = re.compile(r"(nutrition(al)?\s*(information|facts|value)|per\s*100\s*(g|ml)|per\s*serv)", re.I)
ALLERGEN_STATEMENT = re.compile(r"(allergen|contains\s*:|\bcontains\s+(milk|wheat|gluten|soy|nuts?|peanuts?|eggs?)|may\s*contain|traces\s*of|processed\s*in\s*a\s*facility)", re.I)
ALLERGEN_SOURCES = re.compile(r"\b(wheat|gluten|barley|rye|oats|milk|milk\s*solids|whey|casein|butter|cheese|egg|eggs|fish|prawn|shrimp|crab|crustacean|peanut|groundnut|almond|cashew|walnut|pistachio|hazelnut|nuts?|soy|soya|soybean|sulphite|sulfite|mustard|sesame)\b", re.I)
VEG_WORDS = re.compile(r"\b(100\s*%\s*)?(pure\s*)?(veg(etarian)?|non[\s\-]*veg(etarian)?)\b", re.I)

FSSAI = re.compile(r"(?:fssai|f\.?s\.?s\.?a\.?i\.?|lic(?:ence|ense)?\.?\s*no\.?|reg(?:istration)?\.?\s*no\.?)\D{0,14}(\d[\d\s]{12,18}\d)", re.I)

LICENCE = {
    "drug": re.compile(r"(?:mfg\.?|manufacturing|mfd\.?)?\s*\b(?:lic(?:ence|ense)?|l)\.?\s*(?:no\.?|number|#)\s*[:.\-]?\s*([A-Z0-9][A-Z0-9/\-.()]{2,24})"
                       r"|\bM\.?\s?L\.?\s*(?:no\.?)?\s*[:.\-]?\s*([A-Z0-9][A-Z0-9/\-.()]{2,24})|\bD\.?\s?L\.?\s*no\.?\s*[:.\-]?\s*([A-Z0-9/\-]{3,24})", re.I),
    "cosmetic": re.compile(r"(?:mfg\.?|manufacturing|cosmetic)?\s*\blic(?:ence|ense)?\.?\s*(?:no\.?|number)\s*[:.\-]?\s*([A-Z0-9][A-Z0-9/\-.()]{2,24})"
                           r"|\bM\.?\s?L\.?\s*(?:no\.?)?\s*[:.\-]?\s*([A-Z0-9][A-Z0-9/\-.()]{2,24})"
                           r"|(?:import\s*)?reg(?:istration|n)?\.?\s*(?:cert(?:ificate)?\.?)?\s*no\.?\s*[:.\-]?\s*([A-Z0-9/\-]{3,24})|\bR\.?C\.?\s*no\.?\s*[:.\-]?\s*([A-Z0-9/\-]{3,24})", re.I),
    "ayush": re.compile(r"(?:mfg\.?|manufacturing|drug)?\s*\b(?:lic(?:ence|ense)?|l)\.?\s*(?:no\.?|number)\s*[:.\-]?\s*([A-Z0-9][A-Z0-9/\-.()]{2,24})"
                        r"|\b(?:A|S|U)\s*[/\-]?\s*D\s*[/\-]?\s*\d{2,}", re.I),
    "medical_device": re.compile(r"\b(MD\s*[\-–]?\s*(5|9|15|13|14)\b|lic(?:ence|ense)?\.?\s*(?:no\.?|number)\s*[:.\-]?\s*([A-Z0-9/\-.]{3,30})|import\s*lic(?:ence|ense)?)", re.I),
    "insecticide": re.compile(r"(\bCIR\b[\s\-/:.]*[\d\-/(),A-Z]{2,}|reg(?:istration|n)?\.?\s*no\.?\s*[:.\-]?\s*[A-Z0-9/\-()]{3,}|mfg\.?\s*lic(?:ence|ense)?\.?\s*no)", re.I),
}

IS_CODE = re.compile(r"\bIS[\s:\-.]*(\d{3,5})(?:\s*(?:\(|:|-|part)\s*(?:part\s*)?\d{1,2}\)?)?", re.I)
CM_L = re.compile(r"\bCM\s*/\s*L[\s\-:]*([0-9]{7,10})", re.I)
CRS_R = re.compile(r"\bR[\s\-]?(\d{8})\b")
ISI_WORD = re.compile(r"\bISI\b|\bBIS\b", re.I)

QR_HINT = re.compile(r"scan\s*(the\s*)?(qr|q\.r\.)|qr\s*code\s*for|\bqr\s*code\b", re.I)
IMPORTER = re.compile(r"(imported\s*(?:&|and)?\s*(?:marketed\s*)?by|\bimporter\b|import(?:ed)?\s*&\s*(?:distributed|marketed)\s*by)\s*[:\-]?\s*(.*)", re.I)

DIM_PAIR = re.compile(
    r"(\d+(?:[.,]\d+)?)\s*(mm|cm|m|in|inch(?:es)?|\")?\s*[x×X\*]\s*(\d+(?:[.,]\d+)?)\s*(mm|cm|m|in|inch(?:es)?|\")?(?:\s*[x×X\*]\s*(\d+(?:[.,]\d+)?)\s*(mm|cm|m|micron|µ|in)?)?", re.I)
DIAMETER = re.compile(r"(diameter|dia\.?|ø)\s*[:\-]?\s*\d+(?:[.,]\d+)?\s*(mm|cm)", re.I)
CAPACITY = re.compile(r"\b\d+(?:[.,]\d+)?\s*(ml|l|litre|liter|ltr)\s*(capacity|each)?\b|\bcapacity\b", re.I)
COUNT = re.compile(r"\b(\d{1,5})\s*(?:n\b|u\b|nos?\.?|pcs?\.?|pieces?|pairs?|sets?|units?|bags?|cups?|plates?|containers?|sheets?|pulls?|tissues?|rolls?|towels?|covers?|napkins?|sticks?|tablets?|capsules?|cigarettes?|bidis?|beedis?)\b", re.I)
SHEETS = re.compile(r"\b(\d{1,5})\s*(?:usable\s*)?(sheets?|pulls?|tissues?|sheets?\s*per\s*roll|napkins?)\b|\b(sheets?|pulls?)\s*[:\-]?\s*(\d{1,5})\b", re.I)
SIZE_LETTERS = re.compile(r"(?<![A-Za-z])(XXXL|XXL|XL|XS|S|M|L|3XL|4XL|\d{2,3}\s*(?:cm)?)(?![A-Za-z])")
SIZE_LABEL = re.compile(r"\b(size|chest|waist|length|bust|hip|inseam|to\s*fit)\b", re.I)
METRIC_LEN = re.compile(r"\b\d{2,3}(?:[.,]\d)?\s*(cm|centimet(?:er|re)s?)\b|\b\d(?:[.,]\d{1,2})?\s*(m|metres?|meters?)\b", re.I)
TYRE_SIZE = re.compile(r"\b\d{2,3}\s*/\s*\d{2}\s*[-\s]?\s*(r|zr|d)?\s*\d{2}\b|\b\d{1,2}(\.\d{1,2})?\s*[-x]\s*\d{2}\b", re.I)
FOOT_SIZE = re.compile(r"\b(uk|ind|eu|us)\s*[:\-]?\s*\d{1,2}\b|\bsize\s*[:\-]?\s*\d{1,2}\b", re.I)
HELMET_SIZE = re.compile(r"\b(size\s*[:\-]?\s*(XS|S|M|L|XL|XXL)|\d{2}\s*[-–]\s*\d{2}\s*cm)\b", re.I)

WHEN_PACKED = re.compile(r"when\s*packed", re.I)
MISLEADING = re.compile(r"\b(minimum|min\.|not\s*less\s*than|average|avg\.?|about|approx(?:imately|\.)?|approximate|around|nearly|at\s*least|upto|up\s*to|more\s*than)\b", re.I)
COUNT_TERMS = re.compile(r"\b(?:(?:\d+|one|two|half|a)\s*(?:dozens?|doz\.?)|(?:\d+|one|a)\s*(?:great\s*gross|gross)(?!\s*(?:wt|weight|wgt|mass|vehicle|tonnage))|(?:\d+|one|a)\s*scores?)\b", re.I)

EXPORT = re.compile(r"(for\s*export\s*only|export\s*only|not\s*(?:for|to\s*be)\s*sold\s*in\s*india|not\s*for\s*sale\s*in\s*india)", re.I)
INSTITUTIONAL = re.compile(r"(not\s*for\s*retail\s*sale|for\s*institutional\s*(?:use|consumers?|sale)|for\s*industrial\s*(?:use|consumers?)\s*only|institutional\s*pack|horeca|for\s*catering\s*(?:use|purpose))", re.I)
WHOLESALE = re.compile(r"(wholesale\s*pack(?:age)?|for\s*wholesale|contains\s*\d+\s*(?:retail\s*)?(?:packs|packets|units|pouches|bottles)|\b\d+\s*(?:packs|packets|pouches|bottles)\s*of\s*\d+)", re.I)
GROUP = re.compile(r"(\bpack\s*of\s*\d+|\bcombo\b|combination\s*pack|\b\d+\s*[x×]\s*\d+(?:[.,]\d+)?\s*(?:g|kg|ml|l|n)\b|buy\s*\d+\s*get\s*\d+|\bfree\b|\bassorted\b|\bvalue\s*pack|multi[\s\-]*pack)", re.I)
DEVANAGARI = re.compile(r"[\u0900-\u097F]")
LATIN_WORD = re.compile(r"[A-Za-z]{3,}")
LPG_DOMESTIC = re.compile(r"(domestic|14\.2\s*kg|5\s*kg)", re.I)


def extract_label_facts(lines, texts: list[str], decl: dict) -> dict:
    """Adds category-specific facts to the declarations; returns the new keys only."""
    out: dict = {}

    # FSSAI licence (14 digits); keep the stricter core parser's result when it found one
    if not decl.get("fssai_license"):
        for i, t in enumerate(texts):
            m = FSSAI.search(t)
            if m:
                digits = re.sub(r"\D", "", m.group(1))
                if len(digits) == 14:
                    out["fssai_license"] = _found(texts, lines, [i], value=digits)
                    break
    lic = decl.get("fssai_license") or out.get("fssai_license")
    if lic:
        lic["valid_format"] = bool(re.fullmatch(r"[12]\d{13}", str(lic.get("value", ""))))
    out["fssai_logo_word"] = bool(re.search(r"\bfssai\b", " ".join(texts), re.I))

    # Ingredient list
    hit = _first(texts, INGREDIENTS)
    if hit:
        i, m = hit
        after = texts[i][m.end():].strip(" :-")
        idx = [i] + ([i + 1] if i + 1 < len(texts) and len(after) < 12 else [])
        body = " ".join([after] + [texts[j] for j in idx[1:]]).strip()
        out["ingredients"] = _found(texts, lines, idx, value=body[:240], items=len([p for p in re.split(r"[,;()]", body) if p.strip()]))

    # Nutrition panel
    found_n = {k: _hits(texts, rx, 2) for k, rx in NUTRIENTS.items()}
    present = sorted(k for k, ix in found_n.items() if ix)
    head = _hits(texts, NUTRITION_HEAD, 2)
    if present or head:
        idx = sorted({i for ix in found_n.values() for i in ix} | set(head))[:10]
        out["nutrition"] = _found(texts, lines, idx, nutrients=present, per_100=bool(re.search(r"per\s*100\s*(g|ml)|/\s*100\s*(g|ml)", " ".join(texts), re.I)))

    # Allergens
    stmt = _hits(texts, ALLERGEN_STATEMENT, 3)
    sources = sorted({m.group(1).lower() for t in texts for m in ALLERGEN_SOURCES.finditer(t)})
    if stmt or sources:
        out["allergens"] = _found(texts, lines, stmt, statement=bool(stmt), sources=sources[:12])

    # Vegetarian wording (the symbol itself is detected from pixels)
    veg = _first(texts, VEG_WORDS)
    if veg:
        out["veg_words"] = _found(texts, lines, [veg[0]], value=veg[1].group(0))

    # Licence / registration numbers by regime
    for kind, rx in LICENCE.items():
        hit = _first(texts, rx)
        if hit:
            i, m = hit
            value = next((g for g in m.groups() if g), m.group(0)).strip(" :.-")
            out[f"licence_{kind}"] = _found(texts, lines, [i], value=value[:40])

    # BIS certification
    codes, idx = [], []
    for i, t in enumerate(texts):
        for m in IS_CODE.finditer(t):
            codes.append(f"IS {m.group(1)}")
            idx.append(i)
    cml = _first(texts, CM_L)
    crs = _first(texts, CRS_R)
    isi = _hits(texts, ISI_WORD, 2)
    if codes or cml or crs or isi:
        all_idx = sorted(set(idx + ([cml[0]] if cml else []) + ([crs[0]] if crs else []) + isi))[:6]
        out["bis"] = _found(texts, lines, all_idx, codes=sorted(set(codes)), cml=cml[1].group(1) if cml else None,
                            crs=("R-" + crs[1].group(1)) if crs else None, isi_word=bool(isi))

    qr = _hits(texts, QR_HINT, 2)
    if qr:
        out["qr_hint"] = _found(texts, lines, qr, value=True)

    imp = _first(texts, IMPORTER)
    if imp:
        i, m = imp
        idx = [i] + [j for j in range(i + 1, min(i + 3, len(texts)))]
        body = ", ".join([m.group(2).strip(" :-,")] + [texts[j] for j in idx[1:]])
        out["importer"] = _found(texts, lines, idx, name=re.split(r",|\s{2,}", body)[0].strip(), address=body,
                                 pin_code=bool(re.search(r"\b[1-9]\d{2}\s?\d{3}\b", body)))

    # Dimensions, counts and sizes
    dims = [(i, m) for i, t in enumerate(texts) for m in DIM_PAIR.finditer(t)
            if (m.group(2) or m.group(4)) and not re.search(r"\b\d+\s*[x×]\s*\d+\s*(g|kg|ml|l)\b", t, re.I)]
    if dims or _hits(texts, DIAMETER, 1):
        idx = sorted({i for i, _ in dims} | set(_hits(texts, DIAMETER, 2)))[:6]
        out["dimensions"] = _found(texts, lines, idx, values=[m.group(0).strip() for _, m in dims][:6])
    counts = [(i, int(m.group(1))) for i, t in enumerate(texts) for m in COUNT.finditer(t) if int(m.group(1)) > 0]
    if counts:
        out["piece_count"] = _found(texts, lines, sorted({i for i, _ in counts})[:4], value=counts[0][1])
    sheets = [(i, m) for i, t in enumerate(texts) for m in SHEETS.finditer(t)]
    if sheets:
        i, m = sheets[0]
        n = int(m.group(1) or m.group(4))
        out["sheet_count"] = _found(texts, lines, sorted({j for j, _ in sheets})[:3], value=n)
    size_lines = [i for i, t in enumerate(texts) if SIZE_LABEL.search(t)]
    metric = [i for i, t in enumerate(texts) if METRIC_LEN.search(t)]
    letters = [i for i in size_lines if SIZE_LETTERS.search(texts[i])]
    if size_lines or metric:
        idx = sorted(set(size_lines + metric))[:6]
        out["size"] = _found(texts, lines, idx, metric=bool(set(metric) & (set(size_lines) | {j + 1 for j in size_lines} | {j - 1 for j in size_lines}) or metric),
                             letters=bool(letters))
    for key, rx in (("tyre_size", TYRE_SIZE), ("foot_size", FOOT_SIZE), ("helmet_size", HELMET_SIZE)):
        ix = _hits(texts, rx, 2)
        if ix:
            out[key] = _found(texts, lines, ix, value=True)

    # Quantity qualifiers (only those next to the quantity declaration count)
    q = decl.get("net_quantity") or {}
    window = q.get("window") or q.get("lines") or []
    mis = [(i, MISLEADING.search(texts[i])) for i in window if 0 <= i < len(texts) and MISLEADING.search(texts[i])]
    if mis:
        out["misleading_words"] = _found(texts, lines, [i for i, _ in mis], words=sorted({m.group(1).lower() for _, m in mis}))
    wp = _hits(texts, WHEN_PACKED, 2)
    if wp:
        out["when_packed"] = _found(texts, lines, wp, value=True)
    ct = [(i, m) for i, t in enumerate(texts) for m in COUNT_TERMS.finditer(t)]
    if ct:
        out["count_terms"] = _found(texts, lines, sorted({i for i, _ in ct})[:3], words=sorted({m.group(0).lower() for _, m in ct}))

    # Package-type markers
    for key, rx in (("export_marking", EXPORT), ("institutional_marking", INSTITUTIONAL), ("wholesale_marking", WHOLESALE),
                    ("group_marking", GROUP)):
        ix = _hits(texts, rx, 3)
        if ix:
            m = rx.search(texts[ix[0]])
            out[key] = _found(texts, lines, ix, value=m.group(0) if m else True)

    # Script / language
    devanagari = sum(1 for t in texts if DEVANAGARI.search(t))
    latin = sum(1 for t in texts if LATIN_WORD.search(t))
    out["language"] = {"devanagari_lines": devanagari, "latin_lines": latin}
    out["lpg_domestic"] = bool(_hits(texts, LPG_DOMESTIC, 1))
    return out
