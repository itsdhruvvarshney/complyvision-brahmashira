"""Applies Gemini's answers to a deterministic report, within strict limits.

  * Consumer mode: a FAIL / REVIEW on a *declaration-presence* rule may be turned into PASS when Gemini reads the
    declaration on a photo and its quoted words are found in the OCR text (or it is certain and names the photo).
  * Inspector and organisation modes: Gemini NEVER changes a status. Its opinion is attached to the rule, and a
    disagreement with the engine flags the rule for manual review.
  * Physical measurements (numeral height, contrast, image quality, arithmetic, case matches) are never overridable.
  * Everything Gemini quotes is checked against the OCR text; what cannot be found is marked unverified.
"""

from __future__ import annotations

import re
from datetime import date, timedelta

# Validators whose outcome is "is this declaration printed on the pack?" - the only ones Gemini may settle
OVERRIDABLE = {
    "manufacturer", "importer", "country_of_origin", "generic_name", "customer_care", "mrp_declared", "net_quantity",
    "mfg_date", "best_before", "batch_no", "fssai_licence", "veg_mark", "veg_mark_optional", "ingredients", "nutrition",
    "allergens", "text_required", "licence_number", "care_phone_and_email", "bis_mark", "unit_sale_price",
}

MIN_PRESENT_CONFIDENCE = 0.75
CERTAIN = 0.9


def _tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+", (text or "").lower())


def evidence_in_ocr(evidence: str | None, ocr_text: str) -> bool:
    """True when (nearly) everything Gemini quoted can be found in the OCR text; digits must match exactly."""
    ev = [t for t in _tokens(evidence or "") if len(t) > 1 or t.isdigit()]
    if not ev:
        return False
    pool = set(_tokens(ocr_text))
    flat = "".join(_tokens(ocr_text))
    hits = sum(1 for t in ev if t in pool or (len(t) >= 4 and t in flat))
    digits = [t for t in ev if any(c.isdigit() for c in t)]
    if any(d not in pool and d not in flat for d in digits):
        return False
    return hits / len(ev) >= 0.6


def reverify(rows: list[dict], validators: dict[str, str], result: dict, ocr_text: str, mode: str) -> dict:
    """Mutates `rows` according to Gemini's per-rule opinions. Returns a small audit summary."""
    audit = {"checked": 0, "confirmed_by_gemini": 0, "flagged_for_review": 0, "kept": 0}
    if not result.get("available"):
        return audit
    by_id = {str(r.get("id")): r for r in (result.get("rules") or []) if isinstance(r, dict)}
    for row in rows:
        op = by_id.get(row["id"])
        if op is None or row["status"] not in ("FAIL", "REVIEW"):
            continue
        audit["checked"] += 1
        opinion = str(op.get("opinion", "unclear"))
        try:
            conf = max(0.0, min(1.0, float(op.get("confidence", 0))))
        except (TypeError, ValueError):
            conf = 0.0
        evidence = (op.get("evidence_text") or "").strip() or None
        quote_ok = evidence_in_ocr(evidence, ocr_text)
        image = op.get("image") if isinstance(op.get("image"), int) else None
        info = {"opinion": opinion, "confidence": round(conf, 2), "evidence": evidence, "image": image,
                "quote_in_ocr": quote_ok, "note": str(op.get("note") or "")[:200]}
        row["gemini"] = info

        settles = validators.get(row["id"]) in OVERRIDABLE
        supported = opinion == "present" and conf >= MIN_PRESENT_CONFIDENCE and evidence and (quote_ok or (conf >= CERTAIN and image is not None))
        if mode == "consumer" and settles and supported:
            row["original_status"] = row["status"]
            row["status"] = "PASS"
            row["verified_by"] = "gemini"
            row["reason"] = f"Re-verified on the label by AI: \"{evidence}\"" + (f" (photo {image + 1})" if image is not None else "") + \
                f". The automatic text reader had reported: {row.get('reason') or 'not found'}"
            row["found"] = evidence
            audit["confirmed_by_gemini"] += 1
        elif opinion == "present" and supported and settles:
            # Inspector / organisation, or a rule Gemini may not settle: the engine's status stands
            row["manual_review"] = True
            info["flag"] = "Gemini read this declaration on the label; the engine's result stands - confirm manually."
            audit["flagged_for_review"] += 1
        else:
            audit["kept"] += 1
    return audit


# ----------------------------------------------------------------------------- consumer intelligence
_ORIGINS = {"natural", "synthetic", "mineral", "processed", "unknown"}
_IMPACT = {"negligible", "low", "moderate", "high", "unknown"}
_VERDICT = {"supported", "unsupported", "misleading", "unverifiable"}


def _s(v, limit=400):
    return str(v).strip()[:limit] if v not in (None, "") else None


def _list(v, n=40):
    return [x for x in v if x][:n] if isinstance(v, list) else []


def validate_intelligence(intel: dict, ocr_text: str) -> dict:
    """Normalises Gemini's product intelligence and checks its quotes against the OCR text."""
    out: dict = {"available": bool(intel.get("available")), "model": intel.get("model"), "latency_ms": intel.get("latency_ms")}
    if not out["available"]:
        out["reason"] = intel.get("reason", "Gemini unavailable")
        return out

    ingredients = []
    for it in _list(intel.get("ingredients"), 80):
        if not isinstance(it, dict) or not it.get("name"):
            continue
        origin = str(it.get("origin", "unknown")).lower()
        impact = str(it.get("health_impact", "unknown")).lower()
        ingredients.append({
            "name": _s(it["name"], 120), "purpose": _s(it.get("purpose"), 160),
            "origin": origin if origin in _ORIGINS else "unknown", "uses": _s(it.get("uses"), 300),
            "health_impact": impact if impact in _IMPACT else "unknown", "concern": _s(it.get("concern"), 500),
            "who_should_be_careful": [str(x)[:80] for x in _list(it.get("who_should_be_careful"), 6)],
            "allergen": bool(it.get("allergen")), "evidence": _s(it.get("evidence"), 200),
            "verified_in_ocr": evidence_in_ocr(it.get("evidence") or it.get("name"), ocr_text),
        })
    out["ingredients"] = ingredients

    claims = []
    for c in _list(intel.get("claims_check"), 20):
        if not isinstance(c, dict) or not c.get("claim"):
            continue
        verdict = str(c.get("verdict", "unverifiable")).lower()
        verdict = verdict if verdict in _VERDICT else "unverifiable"
        proof = _s(c.get("proof"), 300)
        proof_ok = evidence_in_ocr(proof, ocr_text) if proof else False
        note = None
        if verdict in ("misleading", "unsupported") and not proof_ok:
            # An accusation needs proof printed on the label; without it we only say it could not be verified
            verdict, note = "unverifiable", "Gemini suspected a problem but could not point to proof on the label."
        claims.append({
            "claim": _s(c["claim"], 200), "verdict": verdict, "proof": proof, "proof_verified": proof_ok,
            "image": c.get("image") if isinstance(c.get("image"), int) else None,
            "proof_image": c.get("proof_image") if isinstance(c.get("proof_image"), int) else None,
            "explanation": _s(c.get("explanation"), 500) or note,
        })
    out["claims_check"] = claims

    out["product"] = intel.get("product") if isinstance(intel.get("product"), dict) else {}
    allergens = intel.get("allergens") if isinstance(intel.get("allergens"), dict) else {}
    out["allergens"] = {"declared": [str(x)[:60] for x in _list(allergens.get("declared"), 20)],
                        "sensitivity_related": [str(x)[:100] for x in _list(allergens.get("sensitivity_related"), 20)],
                        "cross_contamination_warning": _s(allergens.get("cross_contamination_warning"), 300)}
    nut = intel.get("nutrition") if isinstance(intel.get("nutrition"), dict) else {}
    out["nutrition"] = {k: nut.get(k) for k in ("per", "calories_kcal", "protein_g", "carbohydrate_g", "sugar_g", "added_sugar_g",
                                                 "fat_g", "saturated_fat_g", "trans_fat_g", "sodium_mg", "fibre_g")}
    out["nutrition"]["observations"] = [
        {"text": _s(o.get("text"), 240), "level": o.get("level") if o.get("level") in ("info", "watch", "good") else "info"}
        for o in _list(nut.get("observations"), 12) if isinstance(o, dict) and o.get("text")]
    dates = intel.get("dates") if isinstance(intel.get("dates"), dict) else {}
    out["dates"] = {k: _s(dates.get(k), 60) for k in ("manufacture", "expiry", "best_before", "batch_no", "date_format_note")}
    out["dates"]["shelf_life"] = shelf_life(out["dates"])
    out["transparency"] = [
        {"kind": t.get("kind") if t.get("kind") in ("missing", "unclear", "unreadable", "misleading") else "unclear",
         "item": _s(t.get("item"), 120), "detail": _s(t.get("detail"), 300)}
        for t in _list(intel.get("transparency"), 20) if isinstance(t, dict) and t.get("item")]
    overall = intel.get("overall") if isinstance(intel.get("overall"), dict) else {}
    out["overall"] = {"summary": _s(overall.get("summary"), 600),
                      "watch_points": [str(x)[:200] for x in _list(overall.get("watch_points"), 8)],
                      "good_points": [str(x)[:200] for x in _list(overall.get("good_points"), 8)]}
    try:
        out["confidence"] = max(0.0, min(1.0, float(intel.get("confidence", 0))))
    except (TypeError, ValueError):
        out["confidence"] = 0.0
    out["disclaimer"] = "General information from the label, not medical advice."
    return out


_MONTHS = {m: i for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


def parse_date(text: str | None) -> date | None:
    """dd/mm/yyyy, dd-mm-yy, mm/yyyy, 'Aug 2027', '12 Aug 2027'. Month/year only -> last day of that month."""
    if not text:
        return None
    t = text.strip().lower()
    m = re.search(r"(\d{1,2})[./\- ]([a-z]{3})[a-z]*[./\- ,]*(\d{2,4})", t)
    if m and m.group(2) in _MONTHS:
        return _mk(int(m.group(3)), _MONTHS[m.group(2)], int(m.group(1)))
    m = re.search(r"([a-z]{3})[a-z]*[./\- ,]*(\d{2,4})", t)
    if m and m.group(1) in _MONTHS:
        return _mk(int(m.group(2)), _MONTHS[m.group(1)], None)
    m = re.search(r"(\d{1,2})[./\-](\d{1,2})[./\-](\d{2,4})", t)
    if m:
        return _mk(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    m = re.search(r"(\d{1,2})[./\-](\d{2,4})", t)
    if m:
        return _mk(int(m.group(2)), int(m.group(1)), None)
    return None


def _mk(year: int, month: int, day: int | None) -> date | None:
    year += 2000 if year < 100 else 0
    if not (1 <= month <= 12 and 2000 <= year <= 2100):
        return None
    try:
        if day is None:
            nxt = date(year + (month == 12), month % 12 + 1, 1)
            return nxt - timedelta(days=1)
        return date(year, month, day)
    except ValueError:
        return None


def shelf_life(dates: dict, today: date | None = None) -> dict:
    """Remaining shelf life from the printed expiry / best-before, computed here (not by the model)."""
    today = today or date.today()
    for key in ("expiry", "best_before"):
        d = parse_date(dates.get(key))
        if d:
            left = (d - today).days
            status = "expired" if left < 0 else "expiring_soon" if left <= 30 else "ok"
            return {"basis": key, "date": d.isoformat(), "days_left": left, "status": status}
    mfg, months = parse_date(dates.get("manufacture")), None
    note = (dates.get("best_before") or "") + " " + (dates.get("date_format_note") or "")
    m = re.search(r"(\d{1,2})\s*months?", note.lower())
    if mfg and m:
        months = int(m.group(1))
        y, mo = divmod(mfg.month - 1 + months, 12)
        d = _mk(mfg.year + y, mo + 1, None)
        if d:
            left = (d - today).days
            return {"basis": "manufacture + months", "date": d.isoformat(), "days_left": left,
                    "status": "expired" if left < 0 else "expiring_soon" if left <= 30 else "ok"}
    return {"basis": None, "date": None, "days_left": None, "status": "unknown"}


# ----------------------------------------------------------------------------- inspector / organisation review
def validate_review(review: dict, ocr_text: str, rule_ids: set[str]) -> dict:
    """Normalises the formal compliance review: only known rule ids, quotes checked, nothing that changes a verdict."""
    out: dict = {"available": bool(review.get("available")), "model": review.get("model"), "latency_ms": review.get("latency_ms")}
    if not out["available"]:
        out["reason"] = review.get("reason", "Gemini unavailable")
        return out
    out["summary"] = _s(review.get("summary"), 800)
    out["cross_check"] = [
        {"field": _s(c.get("field"), 60), "engine": _s(c.get("engine"), 120), "gemini": _s(c.get("gemini"), 120),
         "status": c.get("status") if c.get("status") in ("agrees", "differs", "engine_missed", "gemini_unsure") else "gemini_unsure",
         "evidence": _s(c.get("evidence"), 200), "image": c.get("image") if isinstance(c.get("image"), int) else None}
        for c in _list(review.get("cross_check"), 30) if isinstance(c, dict) and c.get("field")]
    out["inconsistencies"] = [{"item": _s(i.get("item"), 120), "detail": _s(i.get("detail"), 400),
                               "images": [x for x in _list(i.get("images"), 6) if isinstance(x, int)]}
                              for i in _list(review.get("inconsistencies"), 15) if isinstance(i, dict) and i.get("item")]
    out["missing_information"] = [{"item": _s(i.get("item"), 140), "why_it_matters": _s(i.get("why_it_matters"), 300),
                                   "rule_id": i.get("rule_id") if i.get("rule_id") in rule_ids else None}
                                  for i in _list(review.get("missing_information"), 20) if isinstance(i, dict) and i.get("item")]
    out["missing_documents"] = [str(x)[:160] for x in _list(review.get("missing_documents"), 12)]
    findings = []
    for f in _list(review.get("rule_findings"), 80):
        if not isinstance(f, dict) or f.get("rule_id") not in rule_ids:
            continue
        ev = _s(f.get("evidence_text"), 240)
        opinion = f.get("gemini_opinion") if f.get("gemini_opinion") in ("supports", "disputes", "cannot_tell") else "cannot_tell"
        ok = evidence_in_ocr(ev, ocr_text) if ev else False
        if opinion == "disputes" and not ok:
            opinion = "cannot_tell"  # a dispute needs evidence that really is on the label
        try:
            conf = round(max(0.0, min(1.0, float(f.get("confidence", 0)))), 2)
        except (TypeError, ValueError):
            conf = 0.0
        findings.append({"rule_id": f["rule_id"], "engine_status": _s(f.get("engine_status"), 20), "gemini_opinion": opinion,
                         "evidence_text": ev, "quote_in_ocr": ok, "confidence": conf,
                         "image": f.get("image") if isinstance(f.get("image"), int) else None, "note": _s(f.get("note"), 300)})
    out["rule_findings"] = findings
    out["recommendations"] = [
        {"rule_id": r.get("rule_id") if r.get("rule_id") in rule_ids else None, "issue": _s(r.get("issue"), 300), "action": _s(r.get("action"), 400),
         "priority": r.get("priority") if r.get("priority") in ("high", "medium", "low") else "medium"}
        for r in _list(review.get("recommendations"), 20) if isinstance(r, dict) and r.get("issue")]
    out["manual_review"] = [str(x)[:240] for x in _list(review.get("manual_review"), 15)]
    try:
        out["confidence"] = max(0.0, min(1.0, float(review.get("confidence", 0))))
    except (TypeError, ValueError):
        out["confidence"] = 0.0
    out["note"] = "AI assists the analysis; the rule engine is the final authority on every rule."
    return out
