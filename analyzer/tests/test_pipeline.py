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

    assert report["schema"] == "complyvision.package-report/1.0"
    assert report["input"]["mime"] == "image/jpeg"
    assert report["calibration"]["detected"]
    assert report["ocr"]["lines"] == len(texts)
    rules = {r["id"]: r for r in report["rules"]}
    assert len(rules) == 16
    # The synthetic label is fully compliant
    assert {k: r["status"] for k, r in rules.items() if r["status"] not in ("PASS", "NOT_APPLICABLE")} == {}
    assert rules["LM-11"]["status"] == "PASS", rules["LM-11"]          # measured with the ArUco scale
    assert rules["LM-11"]["found"].endswith("mm")
    assert report["summary"]["verdict"] == "PASS" and report["summary"]["failed"] == 0
    # Evidence mapping: every rule that used a field points at a crop that exists
    ids = {e["id"] for e in report["evidence"]}
    assert rules["LM-06"]["evidence"] and set(rules["LM-06"]["evidence"]) <= ids
    assert all(e["image"].startswith("data:image/jpeg;base64,") for e in report["evidence"])
    assert report["annotated_image"].startswith("data:image/jpeg;base64,")
    assert [s["stage"] for s in report["pipeline"]] == [
        "validate", "quality", "preprocess", "calibration", "enhance", "ocr", "verify", "extract", "measure", "ai", "rules", "evidence"]
    assert any(s["step"] == "deskew" and s["applied"] for s in report["preprocessing"])


def test_pipeline_flags_overpriced_pack():
    img, texts = make_label()
    report = analyze(encode(img, ".png"), "pack.png", "image/png", {**CONTEXT, "declared_mrp": "120"},
                     FakeEngine(texts), None, Settings(), today=TODAY)
    rules = {r["id"]: r for r in report["rules"]}
    assert rules["LM-13"]["status"] == "FAIL"
    assert report["summary"]["verdict"] == "FAIL" and "LM-13" in report["summary"]["critical_failures"]


def test_api(monkeypatch):
    from app import main

    img, texts = make_label()
    monkeypatch.setattr(main, "get_engine", lambda lang, mkldnn=True: FakeEngine(texts))
    client = TestClient(main.app)
    assert client.get("/health").json()["status"] == "ok"
    assert len(client.get("/rules").json()["rules"]) == 16

    ok = client.post("/analyze", files={"image": ("pack.jpg", encode(img), "image/jpeg")}, data=CONTEXT)
    assert ok.status_code == 200, ok.text
    assert ok.json()["summary"]["verdict"] in ("PASS", "REVIEW")

    bad = client.post("/analyze", files={"image": ("pack.gif", b"GIF89a" + b"\x00" * 100, "image/gif")})
    assert bad.status_code == 415 and bad.json()["code"] == "unsupported_type"


def test_white_print_on_coloured_pack_is_not_glare():
    from app.vision.quality import glare_mask

    img = np.full((600, 900, 3), (40, 40, 210), np.uint8)  # red packaging
    cv2.putText(img, "NET QTY 1 KG", (60, 300), cv2.FONT_HERSHEY_SIMPLEX, 3.2, (255, 255, 255), 14, cv2.LINE_AA)
    assert (glare_mask(img) > 0).sum() == 0
