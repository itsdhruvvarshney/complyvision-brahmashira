"""Several photos of one product, consumer mode without ArUco, and Gemini applied inside the pipeline."""

from datetime import date

from app.config import Settings
from app.pipeline import analyze
from tests.conftest import LABEL_LINES, FakeEngine, encode, make_label

TODAY = date(2026, 11, 1)
CONTEXT = {"product_name": "Basmati rice"}

FRONT = LABEL_LINES[:3]
BACK = LABEL_LINES[3:]


class SeqEngine(FakeEngine):
    """Returns the text set of the next photo on every read()."""

    def __init__(self, sets):
        super().__init__(sets[0])
        self.sets, self.i = sets, -1

    def read(self, img):
        self.i += 1
        self.texts = self.sets[min(self.i, len(self.sets) - 1)]
        return super().read(img)


def test_two_photos_are_read_as_one_product():
    front, ft = make_label(FRONT, with_marker=False, veg_mark=False)
    back, bt = make_label(BACK, with_marker=False, veg_mark=False)
    engine = SeqEngine([ft, bt])
    rep = analyze(encode(front), "front.jpg", "image/jpeg", {**CONTEXT, "photo_labels": "front,back"}, engine, None, Settings(ocr_rescue=False),
                  today=TODAY, extra_images=[(encode(back), "back.jpg", "image/jpeg")])
    assert [p["label"] for p in rep["photos"]] == ["front", "back"]
    assert rep["ocr"]["lines"] == len(ft) + len(bt)
    d = rep["declarations"]
    assert d["mrp"] and d["net_quantity"] and d["batch_no"] and d["mfg_date"]   # MRP on the front, the rest on the back
    assert {e["photo"] for e in rep["evidence"]} == {0, 1}
    assert "photos" in [s["stage"] for s in rep["pipeline"]]


def test_consumer_mode_skips_aruco_entirely():
    img, texts = make_label()                      # the label even carries a marker; consumers never use it
    rep = analyze(encode(img), "p.jpg", "image/jpeg", {**CONTEXT, "mode": "consumer"}, FakeEngine(texts), None, Settings(), today=TODAY)
    assert rep["mode"] == "consumer" and rep["calibration"]["skipped"] and not rep["calibration"]["detected"]
    assert "calibration" not in [s["stage"] for s in rep["pipeline"]]
    assert not any(w["code"] == "NO_CALIBRATION" for w in rep["warnings"])
    lm11 = next(r for r in rep["rules"] if r["id"] == "LM-11")
    assert lm11["status"] == "NOT_APPLICABLE" and "inspectors" in lm11["reason"]


class FakeGemini:
    enabled = True

    def enrich(self, images, ocr, decl, ctx, labels=None, budget=None):
        assert isinstance(images, list) and len(images) >= 1
        return {"available": True, "category": "rice", "declarations_seen": {}, "allergens": [], "claims": [], "certifications": [],
                "nutrition_highlights": [], "consumer_advisory": [], "regulatory_observations": [], "suspected_issues": [],
                "ocr_corrections": [], "summary": "Rice."}

    def verify_rules(self, images, ocr, rules, labels, mode, budget=None):
        return {"available": True, "rules": [
            {"id": r["id"], "opinion": "present", "evidence_text": "Batch No: RH2608-114", "image": 0, "confidence": 0.95, "note": ""}
            for r in rules]}

    def intelligence(self, images, ocr, enrich, labels, budget=None):
        return {"available": True, "ingredients": [{"name": "Basmati rice", "evidence": "BASMATI RICE", "origin": "natural"}],
                "overall": {"summary": "Plain rice"}}

    def compliance_review(self, images, ocr, rules, enrich, authority, labels, mode, budget=None):
        return {"available": True, "summary": "Formal review", "rule_findings": [], "recommendations": []}


def _run(mode, gemini):
    lines = [ln for ln in LABEL_LINES if not ln[0].startswith("Batch")]
    img, texts = make_label(lines, with_marker=False)
    return analyze(encode(img), "p.jpg", "image/jpeg", {**CONTEXT, "mode": mode}, FakeEngine(texts), gemini, Settings(), today=TODAY)


def test_consumer_gemini_confirms_a_declaration_the_ocr_missed():
    base = _run("consumer", None)
    assert next(r for r in base["rules"] if r["id"] == "LM-31")["status"] in ("FAIL", "REVIEW")
    rep = _run("consumer", FakeGemini())
    row = next(r for r in rep["rules"] if r["id"] == "LM-31")
    assert row["status"] == "PASS" and row["verified_by"] == "gemini" and row["original_status"] in ("FAIL", "REVIEW")
    assert rep["gemini_audit"]["confirmed_by_gemini"] >= 1
    assert rep["intelligence"]["ingredients"][0]["name"] == "Basmati rice" and "compliance_review" not in rep


def test_org_gemini_cannot_override_the_engine():
    base = _run("org", None)
    rep = _run("org", FakeGemini())
    row = next(r for r in rep["rules"] if r["id"] == "LM-31")
    assert row["status"] == next(r for r in base["rules"] if r["id"] == "LM-31")["status"]
    assert row.get("manual_review") and rep["summary"]["verdict"] == base["summary"]["verdict"]
    assert rep["compliance_review"]["summary"] == "Formal review" and "intelligence" not in rep


class ExplodingGemini(FakeGemini):
    """Any call means the time budget was ignored."""

    def enrich(self, *a, **k):
        raise AssertionError("Gemini must be skipped when there is no time left")


def test_gemini_is_skipped_not_fatal_when_time_is_short():
    import time
    img, texts = make_label(with_marker=False)
    rep = analyze(encode(img), "p.jpg", "image/jpeg", {**CONTEXT, "mode": "consumer"}, FakeEngine(texts), ExplodingGemini(), Settings(),
                  today=TODAY, deadline=time.monotonic() + 30)
    assert rep["summary"]["verdict"] in ("PASS", "REVIEW", "FAIL") and not rep["ai_insights"]["available"]
    assert "stay within the time limit" in rep["ai_insights"]["reason"] and not rep["intelligence"]["available"]
