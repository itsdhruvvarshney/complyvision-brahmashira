"""Product categorisation: decides which category's rules and standards apply to a package.

Evidence is scored from four independent sources, strongest first:
  * the inspector's explicit choice (category_id)           - final, confidence 1.0
  * the product name on the case record                      - strong keyword 4.0, weak 1.5
  * the category on the case record ("Edible Oils", ...)     - 2.5 to every category that lists it
  * the label text read by OCR                               - strong 2.0, weak 0.5 (capped)
  * the AI reader's category / generic name                  - strong 3.0, weak 1.0
plus a few regulatory fingerprints (an FSSAI number points to food, "Mfg. Lic." with "IP"/"BP" to a
drug, a CIR number to an insecticide). The package type (retail / wholesale / institutional / export)
is read from the label markings.
"""

from __future__ import annotations

import re
from functools import lru_cache

from .catalog import Category, Taxonomy

STRONG_NAME, WEAK_NAME = 4.0, 1.5
STRONG_TEXT, WEAK_TEXT = 2.0, 0.5
STRONG_AI, WEAK_AI = 3.0, 1.0
LEGACY = 2.5
TEXT_CAP = 8.0
CONFIDENT = 0.6


@lru_cache(maxsize=2048)
def _kw(keyword: str) -> re.Pattern:
    body = re.escape(keyword).replace(r"\ ", r"[\s\-]*")
    return re.compile(r"(?<![a-z0-9])" + body + r"(?![a-z0-9])", re.I)


def _hits(cat: Category, text: str) -> tuple[list[str], list[str]]:
    if not text:
        return [], []
    return [k for k in cat.strong if _kw(k).search(text)], [k for k in cat.weak if _kw(k).search(text)]


FINGERPRINTS = [
    # (pattern on label text, category ids or tag, weight, label)
    (re.compile(r"\bfssai\b|\blic(ence|ense)?\.?\s*no\.?\s*[:\-]?\s*[12]\d{13}\b", re.I), "tag:food", 1.5, "FSSAI licence on the label"),
    (re.compile(r"(mfg\.?\s*lic|m\.\s?l\.\s*no|schedule\s*h|\brx\b).*", re.I), "cat:drugs", 1.0, "drug licence / schedule marking"),
    (re.compile(r"\b(i\.?\s?p\.?|b\.?\s?p\.?|u\.?s\.?p\.?)\b\s*\d+\s*mg", re.I), "cat:drugs", 3.0, "pharmacopoeial strength (IP / BP / USP mg)"),
    (re.compile(r"\bCIR\b|insecticides\s*act", re.I), "cat:insecticides", 3.0, "Insecticides Act registration"),
    (re.compile(r"cosmetics?\s*rules|cosmetic\s*lic", re.I), "tag:cosmetic", 1.5, "Cosmetics Rules marking"),
    (re.compile(r"ayurvedic\s*(proprietary\s*)?medicine|unani\s*medicine|siddha\s*medicine", re.I), "cat:ayush", 4.0, "AYUSH medicine marking"),
    (re.compile(r"statutory\s*warning|tobacco\s*causes|1800[\s\-]*11[\s\-]*2356", re.I), "tag:tobacco", 3.0, "tobacco health warning"),
    (re.compile(r"consumption\s*of\s*alcohol|don'?t\s*drink\s*(&|and)\s*drive|%\s*v\s*/\s*v", re.I), "cat:alcoholic_beverages", 4.0, "alcohol warning / strength"),
    (re.compile(r"mother'?s\s*milk\s*is\s*best", re.I), "cat:infant_food", 5.0, "IMS Act notice"),
    (re.compile(r"not\s*for\s*medicinal\s*use", re.I), "cat:health_supplements", 4.0, "'not for medicinal use' statement"),
    (re.compile(r"\bIS\s*(14543|13428)\b", re.I), "cat:packaged_water", 4.0, "IS 14543 / IS 13428"),
    (re.compile(r"\bIS\s*(269|1489|455|8112|12269)\b", re.I), "cat:cement", 4.0, "cement IS standard"),
    (re.compile(r"\bIS\s*9873\b", re.I), "cat:toys", 4.0, "IS 9873 (toys)"),
    (re.compile(r"\bIS\s*4151\b", re.I), "cat:helmets", 4.0, "IS 4151 (helmets)"),
    (re.compile(r"\bIS\s*2347\b", re.I), "cat:kitchen_appliances", 4.0, "IS 2347 (pressure cookers)"),
    (re.compile(r"\bR[\s\-]?\d{8}\b.*|\bcompulsory\s*registration", re.I), "tag:bis_crs", 1.5, "BIS CRS registration number"),
    (re.compile(r"germination|physical\s*purity|truthfully\s*labell?ed", re.I), "cat:seeds", 4.0, "seed certification data"),
    (re.compile(r"\bmedical\s*device\b|\bsterile\b|\bivd\b|\bMD[\s\-]?(5|9|15)\b", re.I), "cat:medical_devices", 2.0, "medical device marking"),
]


def _target(cat: Category, selector: str) -> bool:
    kind, _, value = selector.partition(":")
    return (kind == "tag" and value in cat.tags) or (kind == "cat" and value == cat.id)


def classify(taxonomy: Taxonomy, context: dict, ocr_text: str, ai: dict | None = None) -> dict:
    ai = ai or {}
    override = taxonomy.get(context.get("category_id"))
    package = package_type(taxonomy, context, ocr_text)
    if override:
        return _result(override, 1.0, "inspector", [{"source": "Inspector selection", "detail": override.name, "weight": None}],
                       [], package)

    name = (context.get("product_name") or "") + " " + (context.get("brand") or "")
    legacy = (context.get("category") or "").strip().lower()
    ai_text = " ".join(str(ai.get(k) or "") for k in ("category", "generic_name", "product_name"))
    scores: dict[str, float] = {}
    evidence: dict[str, list[dict]] = {}

    def add(cat: Category, weight: float, source: str, detail: str):
        scores[cat.id] = scores.get(cat.id, 0.0) + weight
        evidence.setdefault(cat.id, []).append({"source": source, "detail": detail, "weight": round(weight, 2)})

    for cat in taxonomy.categories:
        s, w = _hits(cat, name)
        for k in s:
            add(cat, STRONG_NAME, "Product name", k)
        for k in w:
            add(cat, WEAK_NAME, "Product name", k)
        if legacy and legacy in cat.legacy:
            add(cat, LEGACY, "Case category", context.get("category"))
        s, w = _hits(cat, ocr_text)
        text_score = min(TEXT_CAP, STRONG_TEXT * len(s) + WEAK_TEXT * len(w))
        if text_score:
            add(cat, text_score, "Label text", ", ".join((s + w)[:5]))
        s, w = _hits(cat, ai_text)
        for k in s[:2]:
            add(cat, STRONG_AI, "AI reader", k)
        for k in w[:1]:
            add(cat, WEAK_AI, "AI reader", k)
        if ai_text.strip() and cat.name.lower() in ai_text.lower():
            add(cat, STRONG_AI, "AI reader", cat.name)

    for rx, selector, weight, label in FINGERPRINTS:
        if rx.search(ocr_text or ""):
            for cat in taxonomy.categories:
                if _target(cat, selector) and scores.get(cat.id, 0) > 0:  # fingerprints only reinforce candidates
                    add(cat, weight, "Regulatory marking", label)
            if selector.startswith("cat:"):
                cat = taxonomy.get(selector[4:])
                if cat and scores.get(cat.id, 0) == 0:
                    add(cat, weight * 0.75, "Regulatory marking", label)

    ranked = sorted(((v, k) for k, v in scores.items() if k != "general"), reverse=True)
    if not ranked or ranked[0][0] < 2.0:
        return _result(taxonomy.general, 0.3, "fallback",
                       [{"source": "No category evidence", "detail": "general Legal Metrology rules applied", "weight": None}],
                       [_alt(taxonomy, k, v) for v, k in ranked[:3]], package)
    top, top_id = ranked[0]
    second = ranked[1][0] if len(ranked) > 1 else 0.0
    margin = 1.0 - second / top if top else 0.0
    confidence = round(min(1.0, 0.25 + 0.45 * margin + 0.3 * min(1.0, top / 10.0)), 3)
    cat = taxonomy.get(top_id)
    return _result(cat, confidence, "auto", sorted(evidence[top_id], key=lambda e: -(e["weight"] or 0)),
                   [_alt(taxonomy, k, v) for v, k in ranked[1:4]], package)


def _alt(taxonomy: Taxonomy, cid: str, score: float) -> dict:
    c = taxonomy.get(cid)
    return {**c.summary(), "score": round(score, 2)}


def _result(cat: Category, confidence: float, source: str, evidence: list, alternatives: list, package: dict) -> dict:
    return {"category": cat, "confidence": confidence, "source": source, "evidence": evidence[:8],
            "alternatives": alternatives, "package_type": package}


PACKAGE_ORDER = ("export", "wholesale", "institutional")  # most specific marking first


def package_type(taxonomy: Taxonomy, context: dict, ocr_text: str) -> dict:
    chosen = (context.get("package_type") or "").strip().lower()
    names = {p["id"]: p for p in taxonomy.package_types}
    if chosen in names:
        return {"id": chosen, "name": names[chosen]["name"], "ref": names[chosen].get("ref"), "source": "inspector", "evidence": None}
    for pid in PACKAGE_ORDER:
        p = names.get(pid)
        for marker in (p or {}).get("markers", []):
            m = re.search(marker, ocr_text or "", re.I)
            if m:
                return {"id": pid, "name": p["name"], "ref": p.get("ref"), "source": "label", "evidence": m.group(0)}
    p = names.get("retail", {"name": "Retail package", "ref": "Rule 2(k)"})
    return {"id": "retail", "name": p["name"], "ref": p.get("ref"), "source": "default", "evidence": None}
