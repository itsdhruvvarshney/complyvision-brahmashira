import json

import httpx

from app.ai import gemini as g
from app.ai import review


class Resp:
    def __init__(self, status, payload=None, text=""):
        self.status_code = status
        self._payload = payload or {}
        self.text = text

    def json(self):
        return self._payload


def ok_payload(data):
    return {"candidates": [{"content": {"parts": [{"text": "```json\n" + json.dumps(data) + "\n```"}]}}]}


def test_pool_picks_random_healthy_keys_and_skips_rejected():
    pool = g.KeyPool(["a", "b", "c", "a"])
    assert len(pool) == 3
    assert {pool.acquire()[1] for _ in range(80)} == {"a", "b", "c"}          # spread across the pool
    pool.cool_down(1, 60)
    pool.reject(2, "invalid")
    assert {pool.acquire()[1] for _ in range(30)} == {"a"}
    assert pool.acquire(exclude={0}) is None                                    # nothing else is healthy
    pool.cool_down(2, 5)                                                        # a rejected key stays rejected
    assert pool.status() == {"keys": 3, "available": 1, "resting": 1, "rejected": 1}


def test_quota_key_is_dropped_and_another_used(monkeypatch):
    calls = []

    def fake_post(url, headers, json, timeout):
        key = headers["x-goog-api-key"]
        calls.append(key)
        if key == "k1":
            return Resp(429, text="Quota exceeded for metric ... per day")
        return Resp(200, ok_payload({"summary": "Rice pack", "declarations_seen": {"mrp": True}}))

    monkeypatch.setattr(httpx, "post", fake_post)
    client = g.GeminiClient(["k1", "k2"], "gemini-test", 5)
    for _ in range(6):
        out = client.enrich(b"jpg", "MRP 10", {}, {})
        assert out["available"] and out["summary"] == "Rice pack" and out["key_slot"] == 1
        assert "k1" not in json.dumps(out) and "k2" not in json.dumps(out)
    assert calls.count("k1") <= 1                                               # tried at most once, then rested for an hour


def test_invalid_key_is_rejected_for_good(monkeypatch):
    seen = []

    def fake_post(url, headers, json, timeout):
        seen.append(headers["x-goog-api-key"])
        if headers["x-goog-api-key"] == "bad":
            return Resp(400, text="API key not valid. Please pass a valid API key.")
        return Resp(200, ok_payload({"summary": "ok"}))

    monkeypatch.setattr(httpx, "post", fake_post)
    client = g.GeminiClient(["bad", "good"], "m", 5)
    for _ in range(8):
        assert client.enrich(b"", "", {}, {})["available"]
    assert seen.count("bad") <= 1
    if "bad" in seen:
        assert client.pool.status()["rejected"] == 1


def test_model_fallback_on_404(monkeypatch):
    models = []

    def fake_post(url, headers, json, timeout):
        models.append(url.split("models/")[1].split(":")[0])
        if "old-model" in url:
            return Resp(404, text="model no longer available")
        return Resp(200, ok_payload({"summary": "ok"}))

    monkeypatch.setattr(httpx, "post", fake_post)
    client = g.GeminiClient(["k"], "old-model,new-model", 5)
    out = client.enrich(b"", "", {}, {})
    assert out["available"] and out["model"] == "new-model" and models[:2] == ["old-model", "new-model"]
    models.clear()
    client.enrich(b"", "", {}, {})
    assert models == ["new-model"]                                              # the dead model is skipped afterwards


def test_unconfigured_or_all_failing(monkeypatch):
    assert not g.GeminiClient([], "m", 5).enrich(b"", "", {}, {})["available"]
    monkeypatch.setattr(httpx, "post", lambda *a, **k: Resp(503))
    out = g.GeminiClient(["x"], "m", 5).enrich(b"", "", {}, {})
    assert not out["available"] and "HTTP 503" in out["reason"]


# ---------------------------------------------------------------- how much of Gemini's answer may be applied
OCR = "Net Qty 1 kg MRP Rs 145.00 Batch No RH2608-114 Mfd by Royal Harvest Foods Pvt Ltd Karnal"


def rows():
    return [{"id": "LM-31", "title": "Batch", "status": "FAIL", "reason": "No batch number found", "found": None},
            {"id": "LM-11", "title": "Numeral height", "status": "FAIL", "reason": "Too small", "found": "1 mm"},
            {"id": "FS-03", "title": "Ingredients", "status": "REVIEW", "reason": "unclear", "found": None}]


VALIDATORS = {"LM-31": "batch_no", "LM-11": "numeral_height", "FS-03": "ingredients"}


def verdicts(**over):
    base = {"available": True, "rules": [
        {"id": "LM-31", "opinion": "present", "evidence_text": "Batch No RH2608-114", "image": 1, "confidence": 0.95, "note": ""},
        {"id": "LM-11", "opinion": "present", "evidence_text": "1 kg", "image": 0, "confidence": 0.99, "note": ""},
        {"id": "FS-03", "opinion": "unreadable", "evidence_text": None, "image": None, "confidence": 0.3, "note": ""}]}
    base.update(over)
    return base


def test_consumer_declaration_rules_can_be_confirmed_but_measurements_cannot():
    r = rows()
    audit = review.reverify(r, VALIDATORS, verdicts(), OCR, "consumer")
    assert r[0]["status"] == "PASS" and r[0]["verified_by"] == "gemini" and r[0]["original_status"] == "FAIL"
    assert r[1]["status"] == "FAIL"                       # numeral height is a physical measurement
    assert r[2]["status"] == "REVIEW"                     # unreadable stays uncertain
    assert audit["confirmed_by_gemini"] == 1


def test_consumer_override_needs_evidence_or_certainty():
    r = rows()
    weak = verdicts(rules=[{"id": "LM-31", "opinion": "present", "evidence_text": "Batch XYZ-999", "image": None, "confidence": 0.8}])
    review.reverify(r, VALIDATORS, weak, OCR, "consumer")
    assert r[0]["status"] == "FAIL"                       # quote not in OCR text, not certain, no photo named


def test_inspector_and_org_never_change_status():
    for mode in ("inspector", "org"):
        r = rows()
        audit = review.reverify(r, VALIDATORS, verdicts(), OCR, mode)
        assert [x["status"] for x in r] == ["FAIL", "FAIL", "REVIEW"]
        assert r[0]["manual_review"] and "stands" in r[0]["gemini"]["flag"] and audit["flagged_for_review"] == 1
        assert audit["confirmed_by_gemini"] == 0


def test_intelligence_quotes_are_checked_against_ocr():
    intel = {"available": True, "ingredients": [
        {"name": "Sugar", "evidence": "Sugar", "origin": "natural", "health_impact": "moderate"},
        {"name": "Mystery", "evidence": "Zorblax 12", "origin": "weird", "health_impact": "??"}],
        "claims_check": [
            {"claim": "No added sugar", "verdict": "misleading", "proof": "Ingredients: Sugar, Water"},
            {"claim": "100% natural", "verdict": "misleading", "proof": "Contains Zorblax"}],
        "dates": {"expiry": "01/2000"}}
    out = review.validate_intelligence(intel, "Ingredients: Sugar, Water, Salt")
    ing = {i["name"]: i for i in out["ingredients"]}
    assert ing["Sugar"]["verified_in_ocr"] and not ing["Mystery"]["verified_in_ocr"]
    assert ing["Mystery"]["origin"] == "unknown" and ing["Mystery"]["health_impact"] == "unknown"
    verdict = {c["claim"]: c["verdict"] for c in out["claims_check"]}
    assert verdict == {"No added sugar": "misleading", "100% natural": "unverifiable"}   # an accusation needs proof on the label
    assert out["dates"]["shelf_life"]["status"] == "expired"


def test_review_disputes_need_evidence_and_known_rules():
    raw = {"available": True, "rule_findings": [
        {"rule_id": "LM-06", "gemini_opinion": "disputes", "evidence_text": "MRP Rs 999", "confidence": 0.9},
        {"rule_id": "LM-04", "gemini_opinion": "disputes", "evidence_text": "Net Qty 1 kg", "confidence": 0.9},
        {"rule_id": "ZZ-99", "gemini_opinion": "supports"}],
        "missing_information": [{"item": "Licence", "rule_id": "NOPE"}]}
    out = review.validate_review(raw, OCR, {"LM-06", "LM-04"})
    ops = {f["rule_id"]: f["gemini_opinion"] for f in out["rule_findings"]}
    assert ops == {"LM-06": "cannot_tell", "LM-04": "disputes"} and out["missing_information"][0]["rule_id"] is None


def test_shelf_life_parsing():
    from datetime import date
    assert review.parse_date("12/08/2027") == date(2027, 8, 12)
    assert review.parse_date("Aug 2027") == date(2027, 8, 31)
    assert review.parse_date("03/2026") == date(2026, 3, 31)
    sl = review.shelf_life({"manufacture": "08/2026", "best_before": "12 months from mfg"}, today=date(2026, 11, 1))
    assert sl["date"] == "2027-08-31" and sl["status"] == "ok"
