import math
from datetime import date

import cv2
import numpy as np
from fastapi.testclient import TestClient

from app.config import Settings
from app.pipeline import analyze
from app.vision.aruco import calibrate
from app.vision.preprocess import estimate_skew, preprocess
from app.vision.quality import assess

from .conftest import FakeEngine, encode, make_label

TODAY = date(2026, 11, 1)
CONTEXT = {"case_code": "LM-2026-0040", "product_name": "Premium Rice (1 kg)", "category": "Food Grains", "declared_mrp": "145"}


def rotate(img, deg):
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), deg, 1.0)
    cos, sin = abs(m[0, 0]), abs(m[0, 1])
    nw, nh = int(h * sin + w * cos), int(h * cos + w * sin)
    m[0, 2] += nw / 2 - w / 2
    m[1, 2] += nh / 2 - h / 2
    return cv2.warpAffine(img, m, (nw, nh), borderValue=(247, 247, 247))


def test_skew_is_measured_and_corrected():
    img, _ = make_label(with_marker=False)
    for deg in (-9, -4, 5, 11):
        tilted = rotate(img, deg)
        est = estimate_skew(cv2.cvtColor(tilted, cv2.COLOR_BGR2GRAY))
        assert est is not None and abs(est - deg) < 1.5, (deg, est)
        out, steps, geo = preprocess(tilted, assess(tilted), 2400)
        assert abs(estimate_skew(cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)) or 0) < 1.0


def test_glare_dark_and_low_contrast_are_repaired():
    img, _ = make_label(with_marker=False)
    glare = img.copy()
    cv2.circle(glare, (900, 380), 70, (255, 255, 255), -1)
    q = assess(glare)
    assert any(w["code"] == "GLARE" for w in q["warnings"])
    _, steps, _ = preprocess(glare, q, 2400)
    assert next(s for s in steps if s["step"] == "glare")["applied"]

    dark = (img * 0.25).astype(np.uint8)
    q = assess(dark)
    assert any(w["code"] == "LOW_LIGHT" for w in q["warnings"])
    out, steps, _ = preprocess(dark, q, 2400)
    assert next(s for s in steps if s["step"] == "exposure")["applied"]
    assert cv2.cvtColor(out, cv2.COLOR_BGR2GRAY).mean() > 95


def test_aruco_calibration():
    img, _ = make_label(marker_px=160)
    cal = calibrate(img, 20.0)
    assert cal["detected"] and cal["dictionary"] == "DICT_4X4_50" and cal["marker_id"] == 0
    assert math.isclose(cal["px_per_mm"], 8.0, rel_tol=0.03)
    assert not calibrate(make_label(with_marker=False)[0], 20.0)["detected"]


def test_full_pipeline_on_synthetic_label():
    img, texts = make_label()
    tilted = rotate(img, 6)
    engine = FakeEngine(texts)
    report = analyze(encode(tilted), "pack.jpg", "image/jpeg", CONTEXT, engine, None, Settings(), today=TODAY)

    assert report["schema"] == "complyvision.package-report/1.1"
    assert report["input"]["mime"] == "image/jpeg"
    assert report["calibration"]["detected"]
    assert report["ocr"]["lines"] == len(texts)
    # Categorised first, then only the category's rules are evaluated
    cl = report["classification"]
    assert cl["category"]["id"] == "food_grains" and cl["package_type"]["id"] == "retail"
    assert cl["rules_applied"] == len(report["rules"]) < cl["rules_in_catalog"]
    assert {n["id"] for n in cl["not_relevant"]} & {"CS-01", "TB-01", "DR-02"}
    rules = {r["id"]: r for r in report["rules"]}
    assert all(r["section"] and r["applies"] for r in report["rules"])
    # The synthetic label is fully compliant
    assert {k: r["status"] for k, r in rules.items() if r["status"] not in ("PASS", "NOT_APPLICABLE")} == {}
    assert rules["LM-11"]["status"] == "PASS", rules["LM-11"]          # measured with the ArUco scale
    assert "mm" in rules["LM-11"]["found"]
    assert report["measurements"]["pdp"]["source"] == "estimated"
    assert rules["FS-02"]["status"] == "PASS", rules["FS-02"]          # green veg symbol detected from pixels
    assert report["measurements"]["veg_mark"]["kind"] == "veg"
    assert report["summary"]["verdict"] == "PASS" and report["summary"]["failed"] == 0
    # Evidence mapping: every rule that used a field points at a crop that exists
    ids = {e["id"] for e in report["evidence"]}
    assert rules["LM-06"]["evidence"] and set(rules["LM-06"]["evidence"]) <= ids
    assert all(e["image"].startswith("data:image/jpeg;base64,") for e in report["evidence"])
    assert report["annotated_image"].startswith("data:image/jpeg;base64,")
    assert [s["stage"] for s in report["pipeline"]] == [
        "validate", "quality", "preprocess", "calibration", "enhance", "ocr", "rescue", "verify", "extract", "measure", "ai",
        "classify", "rules", "evidence"]
    assert {s["section"] for s in report["summary"]["sections"]} >= {"Mandatory declaration", "Food safety (FSS)"}
    assert any(s["step"] == "deskew" and s["applied"] for s in report["preprocessing"])


def test_pipeline_flags_overpriced_pack():
    img, texts = make_label()
    ctx = {**CONTEXT, "declared_mrp": "120"}
    # Consumer checks keep the strict rule-based verdict
    consumer = analyze(encode(img, ".png"), "pack.png", "image/png", {**ctx, "mode": "consumer"}, FakeEngine(texts), None, Settings(), today=TODAY)
    assert {r["id"]: r for r in consumer["rules"]}["LM-13"]["status"] == "FAIL"
    assert consumer["summary"]["verdict"] == "FAIL" and consumer["summary"]["verdict_basis"] == "rules"
    # Inspector and organisation checks pass at 75% or more; the failed rule is still listed
    for mode in ("inspector", "org"):
        report = analyze(encode(img, ".png"), "pack.png", "image/png", {**ctx, "mode": mode}, FakeEngine(texts), None, Settings(), today=TODAY)
        rules = {r["id"]: r for r in report["rules"]}
        assert rules["LM-13"]["status"] == "FAIL" and "LM-13" in report["summary"]["critical_failures"]
        s = report["summary"]
        assert s["score"] >= 75 and s["verdict"] == "PASS" and s["verdict_basis"] == "score_threshold" and s["engine_verdict"] == "FAIL"
        assert "pass mark" in s["headline"]


def test_below_the_pass_mark_stays_failed():
    img, texts = make_label()
    lines = [t for t in texts if not t.startswith(("Net Qty", "MRP", "Unit Sale", "Mfg Date", "Batch", "Mfd by", "Customer", "FSSAI"))]
    report = analyze(encode(img, ".png"), "pack.png", "image/png", {**CONTEXT, "mode": "inspector"}, FakeEngine(lines), None, Settings(), today=TODAY)
    assert report["summary"]["score"] < 75 and report["summary"]["verdict"] != "PASS"


def test_api(monkeypatch):
    from app import main

    img, texts = make_label()
    monkeypatch.setattr(main, "get_engine", lambda *a, **k: FakeEngine(texts))
    client = TestClient(main.app)
    assert client.get("/health").json()["status"] == "ok"
    assert len(client.get("/rules").json()["rules"]) >= 70
    cats = client.get("/categories").json()
    assert len(cats["categories"]) >= 55 and any(c["id"] == "edible_oil" and c["rules"] > 20 for c in cats["categories"])
    oil = client.get("/rules", params={"category": "edible_oil"}).json()
    assert "LM-26" in {r["id"] for r in oil["rules"]} and "CS-01" in oil["not_relevant"]
    assert client.get("/rules", params={"category": "nope"}).status_code == 404

    ok = client.post("/analyze", files={"image": ("pack.jpg", encode(img), "image/jpeg")}, data=CONTEXT)
    assert ok.status_code == 200, ok.text
    assert ok.json()["summary"]["verdict"] in ("PASS", "REVIEW")
    forced = client.post("/analyze", files={"image": ("pack.jpg", encode(img), "image/jpeg")},
                         data={**CONTEXT, "category_id": "pulses"})
    assert forced.json()["classification"]["category"]["id"] == "pulses"
    assert forced.json()["classification"]["source"] == "inspector"

    bad = client.post("/analyze", files={"image": ("pack.gif", b"GIF89a" + b"\x00" * 100, "image/gif")})
    assert bad.status_code == 415 and bad.json()["code"] == "unsupported_type"


def test_white_print_on_coloured_pack_is_not_glare():
    from app.vision.quality import glare_mask

    img = np.full((600, 900, 3), (40, 40, 210), np.uint8)  # red packaging
    cv2.putText(img, "NET QTY 1 KG", (60, 300), cv2.FONT_HERSHEY_SIMPLEX, 3.2, (255, 255, 255), 14, cv2.LINE_AA)
    assert (glare_mask(img) > 0).sum() == 0


def test_rescue_merges_tile_reads_without_duplicates():
    from app.ocr.engine import OcrLine
    from app.ocr.rescue import merge_lines, needs_rescue

    base = [OcrLine("MRP Rs. 1", 0.6, (10, 10, 100, 20))]
    better = OcrLine("MRP Rs. 145", 0.95, (12, 11, 110, 20))
    new = OcrLine("Net Qty 1 kg", 0.9, (10, 60, 100, 20))
    merged = merge_lines(base, [better, new])
    assert [ln.text for ln in merged] == ["MRP Rs. 145", "Net Qty 1 kg"]
    assert needs_rescue({}, merged) is not None
    assert needs_rescue({"mrp": 1, "net_quantity": 1, "mfg_date": 1}, merged * 3) is None
