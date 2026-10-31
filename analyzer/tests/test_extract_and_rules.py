from datetime import date

from app.extract.fields import expected_from_context, extract, parse_date, quantity_from_text
from app.ocr.engine import OcrLine
from app.rules import engine as re_

TODAY = date(2026, 11, 1)


def lines_of(*texts, conf=0.95):
    return [OcrLine(t, conf, (10, 40 * i, 400, 30)) for i, t in enumerate(texts)]


def run_rules(decl, context=None, quality=None, calibration=None, measurements=None, ai=None, conf=0.95, n=8):
    ctx = re_.RuleContext(
        decl=decl, quality=quality or {"grade": "Good", "score": 88}, calibration=calibration or {"detected": False},
        measurements={"contrast": {"worst": {"field": "mrp", "ratio": 7.0}}, **(measurements or {})}, expected=expected_from_context(context or {}), ai=ai or {},
        ocr_mean_confidence=conf, ocr_line_count=n, today=TODAY)
    return {r.id: o for r, o in re_.evaluate(re_.load_rules(), ctx)}


COMPLIANT = lines_of(
    "PREMIUM BASMATI RICE",
    "Net Qty: 1 kg",
    "M.R.P. ₹ 145.00",
    "(Inclusive of all taxes)",
    "Unit sale price: Rs. 145/kg",
    "Pkd on: AUG 2026",
    "Best before 12 months from packing",
    "Batch No: RH2608-114",
    "Marketed by: Royal Harvest Foods Pvt Ltd, Plot 12,",
    "Industrial Area, Karnal, Haryana 132001",
    "Customer care: 1800-120-4455, care@royalharvest.in",
)


def test_extracts_all_declarations():
    d = extract(COMPLIANT, {"product_name": "Premium Rice (1 kg)", "category": "Food Grains"}, TODAY)
    assert d["mrp"]["value"] == 145.0 and d["mrp"]["inclusive_of_taxes"] and d["mrp"]["currency_symbol"]
    assert d["net_quantity"]["base_value"] == 1000 and d["net_quantity"]["standard_symbol"]
    assert d["unit_sale_price"]["value"] == 145 and d["unit_sale_price"]["per"] == "kg"
    assert d["mfg_date"]["iso"] == "2026-08"
    assert d["best_before"]["iso"] == "2027-08"
    assert d["batch_no"]["value"] == "RH2608-114"
    assert d["manufacturer"]["address_complete"] and d["manufacturer"]["pin_code"] == "132001"
    assert d["manufacturer"]["lines"] == [8, 9]
    assert "18001204455" in d["customer_care"]["phones"][0].replace("-", "")
    assert d["customer_care"]["emails"] == ["care@royalharvest.in"]
    assert d["generic_name"]["value"] == "rice"
    assert d["is_food"]["value"]
    assert not d["origin"]["imported"]


def test_compliant_label_passes_every_applicable_rule():
    d = extract(COMPLIANT, {"product_name": "Premium Rice (1 kg)", "category": "Food Grains"}, TODAY)
    res = run_rules(d, {"product_name": "Premium Rice (1 kg)", "declared_mrp": 145})
    bad = {k: (o.status, o.reason) for k, o in res.items() if o.status not in ("PASS", "NOT_APPLICABLE")}
    assert bad == {}
    assert res["LM-02"].status == "NOT_APPLICABLE"   # domestic
    assert res["LM-11"].status == "NOT_APPLICABLE"   # no ArUco marker
    assert res["LM-13"].status == "PASS" and res["LM-16"].status == "PASS"


def test_violations_are_failed():
    lines = lines_of("WHEAT ATTA", "Net Wt 900 g", "MRP 320.00", "Mfd by: Annapurna Mills, Haridwar 249401", "Mfg: 12/2026")
    d = extract(lines, {"product_name": "Wheat Atta (1 kg)", "category": "Food Grains"}, TODAY)
    res = run_rules(d, {"product_name": "Wheat Atta (1 kg)", "declared_mrp": 285})
    assert res["LM-07"].status == "FAIL"        # no "inclusive of all taxes"
    assert res["LM-08"].status == "FAIL"        # no unit sale price
    assert res["LM-09"].status == "FAIL"        # no consumer care
    assert res["LM-10"].status == "FAIL"        # food without best before
    assert res["LM-13"].status == "FAIL"        # ₹320 > ₹285 on record
    assert res["LM-14"].status == "FAIL"        # Dec 2026 manufacture is in the future
    assert res["LM-16"].status == "FAIL"        # 900 g < 1 kg
    assert re_.verdict([o.status for o in res.values()]) == "FAIL"


def test_missing_fields_become_review_on_poor_capture_or_ai_sighting():
    d = extract(lines_of("SALT", "Net Qty 1 kg"), {"category": "Staples"}, TODAY)
    poor = run_rules(d, quality={"grade": "Poor", "score": 30})
    assert poor["LM-06"].status == "REVIEW"
    seen = run_rules(d, ai={"declarations_seen": {"mrp": True}})
    assert seen["LM-06"].status == "REVIEW" and seen["LM-09"].status == "FAIL"


def test_non_standard_unit_and_imports():
    d = extract(lines_of("Choco Biscuits", "Net Wt. 500 gms", "Imported & Marketed by: Global Foods, Mumbai 400001"), {}, TODAY)
    res = run_rules(d)
    assert res["LM-04"].status == "REVIEW"
    assert d["origin"]["imported"] and res["LM-02"].status in ("FAIL", "REVIEW")
    d2 = extract(lines_of("Country of Origin: Malaysia", "Net Qty 1 L"), {}, TODAY)
    assert run_rules(d2)["LM-02"].status == "PASS"


def test_numeral_height_rule_uses_rule7_table():
    d = extract(lines_of("Net Qty: 1 kg"), {}, TODAY)
    cal = {"detected": True, "px_per_mm": 8.0}
    ok = run_rules(d, calibration=cal, measurements={"numeral_required_mm": 4.0, "numeral_height": {"height_mm": 5.1}})
    small = run_rules(d, calibration=cal, measurements={"numeral_required_mm": 4.0, "numeral_height": {"height_mm": 2.5}})
    edge = run_rules(d, calibration=cal, measurements={"numeral_required_mm": 4.0, "numeral_height": {"height_mm": 3.8}})
    assert (ok["LM-11"].status, small["LM-11"].status, edge["LM-11"].status) == ("PASS", "FAIL", "REVIEW")


def test_parsers():
    assert parse_date("Best before 03/2027")[0]["iso"] == "2027-03"
    assert parse_date("PKD: 14-Aug-26")[0]["iso"] == "2026-08-14"
    assert parse_date("EXP 2027-01-31")[0]["iso"] == "2027-01-31"
    assert parse_date("no date here") is None
    assert quantity_from_text("Net 2.5 L")["base_value"] == 2500
    assert quantity_from_text("250 ml")["base_unit"] == "ml"
    assert quantity_from_text("10 kgs")["standard_symbol"] is False
    assert expected_from_context({"product_name": "Refined Oil (1 L)", "declared_mrp": "168"}) == {
        "mrp": 168.0, "quantity": {"value": 1.0, "unit": "L", "base_unit": "ml", "base_value": 1000.0, "standard_symbol": True}}


def test_ocr_digit_confusions_are_tolerated():
    d = extract(lines_of("MRP Rs. 1O5.5O incl. of all taxes"), {}, TODAY)
    assert d["mrp"]["value"] == 105.5
