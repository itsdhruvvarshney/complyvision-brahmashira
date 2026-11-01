from datetime import date

from app.extract.fields import expected_from_context, extract, normalise_text, parse_date, quantity_from_text
from app.extract.label import extract_label_facts
from app.ocr.engine import OcrLine
from app.rules import engine as re_
from app.rules.catalog import load_taxonomy, select_rules
from app.rules.classifier import classify

TODAY = date(2026, 11, 1)
VEG = {"found": True, "kind": "veg", "score": 0.9, "box": [0, 0, 20, 20]}


def lines_of(*texts, conf=0.95):
    return [OcrLine(t, conf, (10, 40 * i, 400, 30)) for i, t in enumerate(texts)]


def decl_of(lines, context=None):
    d = extract(lines, context or {}, TODAY)
    d.update(extract_label_facts(lines, [normalise_text(ln.text) for ln in lines], d))
    return d


def run_rules(lines, context=None, quality=None, calibration=None, measurements=None, ai=None, conf=0.95):
    """Categorise, select the category's rules and evaluate them - exactly as the pipeline does."""
    context = context or {}
    decl = decl_of(lines, context)
    texts = [normalise_text(ln.text) for ln in lines]
    cl = classify(load_taxonomy(), context, "\n".join(texts), ai or {})
    selected, _ = select_rules(re_.load_rules(), cl["category"], cl["package_type"]["id"])
    ctx = re_.RuleContext(
        decl=decl, quality=quality or {"grade": "Good", "score": 88}, calibration=calibration or {"detected": False},
        measurements={"contrast": {"worst": {"field": "mrp", "ratio": 7.0}}, "veg_mark": VEG, **(measurements or {})},
        expected=expected_from_context(context), ai=ai or {}, ocr_mean_confidence=conf, ocr_line_count=len(lines), today=TODAY,
        category=cl["category"], classification=cl, package_type=cl["package_type"]["id"], texts=texts, context=context)
    return {r.id: o for r, o in re_.evaluate(selected, ctx)}, cl


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
    "FSSAI Lic. No. 10012345000123",
)
RICE = {"product_name": "Premium Rice (1 kg)", "category": "Food Grains"}


def test_extracts_all_declarations():
    d = decl_of(COMPLIANT, RICE)
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
    assert d["fssai_license"]["value"] == "10012345000123"
    assert d["is_food"]["value"]
    assert not d["origin"]["imported"]
    assert [m["value"] for m in d["mrp_all"]] == [145.0]


def test_rice_is_categorised_and_gets_only_its_rules():
    res, cl = run_rules(COMPLIANT, {**RICE, "declared_mrp": 145})
    assert cl["category"].id == "food_grains" and cl["confidence"] >= 0.6
    # Food rules apply, cosmetic / drug / tobacco / textile rules do not even get evaluated
    assert {"FS-01", "FS-02", "LM-31"} <= set(res)
    assert not {"CS-01", "DR-02", "TB-01", "TX-01", "AY-01", "WH-01"} & set(res)
    # Single-ingredient rice is exempt from the ingredient list and nutrition panel
    assert "FS-03" not in res and "FS-04" not in res


def test_compliant_label_passes_every_applicable_rule():
    res, _ = run_rules(COMPLIANT, {**RICE, "declared_mrp": 145})
    bad = {k: (o.status, o.reason) for k, o in res.items() if o.status not in ("PASS", "NOT_APPLICABLE")}
    assert bad == {}
    assert res["LM-02"].status == "NOT_APPLICABLE"   # domestic
    assert res["LM-11"].status == "NOT_APPLICABLE"   # no ArUco marker
    assert res["LM-08"].status == "NOT_APPLICABLE"   # 1 kg: unit sale price equals the MRP
    assert res["LM-13"].status == "PASS" and res["LM-16"].status == "PASS"
    assert res["LM-00"].status == "PASS"


def test_violations_are_failed():
    lines = lines_of("WHEAT ATTA", "Net Wt 900 g", "MRP 320.00", "Mfd by: Annapurna Mills, Haridwar 249401", "Mfg: 12/2026")
    res, _ = run_rules(lines, {"product_name": "Wheat Atta (1 kg)", "category": "Food Grains", "declared_mrp": 285})
    assert res["LM-07"].status == "FAIL"        # no "inclusive of all taxes"
    assert res["LM-08"].status == "FAIL"        # 900 g needs a unit sale price per gram
    assert res["LM-09"].status == "FAIL"        # no consumer care
    assert res["LM-10"].status == "FAIL"        # food without best before
    assert res["LM-13"].status == "FAIL"        # ₹320 > ₹285 on record
    assert res["LM-14"].status == "FAIL"        # Dec 2026 manufacture is in the future
    assert res["LM-16"].status == "FAIL"        # 900 g < 1 kg
    assert res["FS-01"].status == "FAIL"        # no FSSAI licence
    assert re_.verdict([o.status for o in res.values()]) == "FAIL"


def test_missing_fields_become_review_on_poor_capture_or_ai_sighting():
    lines = lines_of("SALT", "Net Qty 1 kg")
    poor, _ = run_rules(lines, {"category": "Staples"}, quality={"grade": "Poor", "score": 30})
    assert poor["LM-06"].status == "REVIEW"
    seen, cl = run_rules(lines, {"category": "Staples"}, ai={"declarations_seen": {"mrp": True}})
    assert cl["category"].id == "salt_sugar"
    assert seen["LM-06"].status == "REVIEW" and seen["LM-09"].status == "FAIL"


def test_non_standard_unit_and_imports():
    res, _ = run_rules(lines_of("Choco Biscuits", "Net Wt. 500 gms", "Imported & Marketed by: Global Foods, Mumbai 400001"))
    assert res["LM-04"].status == "REVIEW"
    assert res["LM-02"].status in ("FAIL", "REVIEW")
    assert res["LM-20"].status == "PASS"        # importer with an Indian PIN code
    res2, _ = run_rules(lines_of("Country of Origin: Malaysia", "Net Qty 1 L"))
    assert res2["LM-02"].status == "PASS"


def test_numeral_height_uses_display_panel_table():
    lines = lines_of("Net Qty: 1 kg")
    cal = {"detected": True, "px_per_mm": 8.0}
    pdp = {"pdp": {"area_cm2": 300.0, "source": "entered by inspector"}, "numeral_required_mm": 2.5}
    ok, _ = run_rules(lines, calibration=cal, measurements={**pdp, "numeral_height": {"height_mm": 3.1}})
    small, _ = run_rules(lines, calibration=cal, measurements={**pdp, "numeral_height": {"height_mm": 1.4}})
    edge, _ = run_rules(lines, calibration=cal, measurements={**pdp, "numeral_height": {"height_mm": 2.4}})
    assert (ok["LM-11"].status, small["LM-11"].status, edge["LM-11"].status) == ("PASS", "FAIL", "REVIEW")


def test_rule7_table_by_display_panel_area():
    from app.vision.measure import required_numeral_height_mm as req
    assert [req(a) for a in (30, 50, 80, 300, 1000, 4000)] == [1.0, 1.0, 1.5, 2.5, 4.0, 6.0]
    assert req(30, moulded=True) == 2.0  # corrigendum G.S.R. 1373(E)


def test_edible_oil_volume_needs_weight_and_unit_price_math():
    oil = {"product_name": "Refined Sunflower Oil (1 L)", "category": "Edible Oils"}
    vol_only = lines_of("REFINED SUNFLOWER OIL", "Net Vol: 500 ml", "MRP Rs. 95.00 incl. of all taxes", "Unit sale price Rs. 0.19 per ml")
    res, cl = run_rules(vol_only, oil)
    assert cl["category"].id == "edible_oil"
    assert res["LM-26"].status == "FAIL"        # Fourth Schedule item 11: weight required too
    assert res["LM-22"].status == "PASS"        # 95 / 500 = 0.19 per ml
    both = lines_of("REFINED SUNFLOWER OIL", "Net Vol: 500 ml (455 g)", "MRP Rs. 95.00 incl. of all taxes", "Unit sale price Rs. 0.25 per ml")
    res2, _ = run_rules(both, oil)
    assert res2["LM-26"].status == "PASS"
    assert res2["LM-22"].status == "FAIL"       # 0.25 is not 95 / 500


def test_quantity_dimension_and_unit_scale():
    honey = lines_of("PURE HONEY", "Net Vol 500 ml", "MRP Rs. 260 incl. of all taxes")
    res, cl = run_rules(honey, {"product_name": "Honey", "category": "Health Foods"})
    assert cl["category"].id == "honey_syrup"
    assert res["LM-25"].status == "FAIL"        # Fourth Schedule item 14: honey by weight
    big = lines_of("BASMATI RICE", "Net Wt 1500 g")
    res2, _ = run_rules(big, RICE)
    assert res2["LM-24"].status == "FAIL"       # Rule 13(3): 1.5 kg, not 1500 g


def test_misleading_words_count_terms_and_when_packed():
    res, _ = run_rules(lines_of("WHEAT ATTA", "Net Wt approx. 5 kg", "1 dozen packets inside", "Weight when packed"),
                       {"product_name": "Atta", "category": "Food Grains"})
    assert res["LM-27"].status == "FAIL"
    assert res["LM-29"].status == "FAIL"
    assert res["LM-28"].status == "FAIL"        # atta is not a Third Schedule commodity
    soap, cl = run_rules(lines_of("TOILET SOAP", "Net Wt 100 g when packed"), {"product_name": "Bath soap", "category": "Personal Care"})
    assert cl["category"].id == "soaps" and soap["LM-28"].status == "PASS"


def test_tobacco_never_small_pack_exempt_and_needs_warnings():
    lines = lines_of("GUTKHA", "Net Wt 5 g", "MRP Rs. 5 incl. of all taxes", "Tobacco causes painful death")
    res, cl = run_rules(lines, {"product_name": "Gutkha sachet"})
    assert cl["category"].id == "smokeless_tobacco"
    assert res["LM-06"].status == "PASS"        # declarations still required at 5 g
    assert res["TB-01"].status == "PASS" and res["TB-02"].status == "FAIL" and res["TB-03"].status == "REVIEW"
    small, _ = run_rules(lines_of("JEERA", "Net Wt 8 g"), {"product_name": "Cumin seeds sachet", "category": "Spices"})
    assert small["LM-06"].status == "NOT_APPLICABLE" and "26(a)" in small["LM-06"].reason


def test_inspector_category_override_and_drug_exemption():
    lines = lines_of("PARACETAMOL TABLETS IP 500 mg", "Each uncoated tablet contains Paracetamol IP 500 mg", "10 Tablets",
                     "Mfg. Lic. No. MB/07/123", "Batch No. PX2301", "Mfg. Date 03/2026", "Exp. Date 02/2028", "MRP Rs. 25.00 incl. of all taxes",
                     "Schedule H drug", "Mfd by: Acme Pharma Ltd, Plot 4, MIDC, Pune, Maharashtra 411019")
    res, cl = run_rules(lines, {"product_name": "Paracetamol 500"})
    assert cl["category"].id == "drugs"
    assert "LM-08" not in res                   # DPCO formulations are outside the LM rules (Rule 26(c))
    assert res["DR-02"].status == "PASS" and res["DR-06"].status == "PASS"
    assert res["DR-08"].status == "FAIL"        # Schedule H caution missing
    _, forced = run_rules(lines, {"product_name": "Paracetamol 500", "category_id": "ayush"})
    assert forced["category"].id == "ayush" and forced["source"] == "inspector"


def test_export_and_institutional_packages():
    exp, _ = run_rules(lines_of("BASMATI RICE", "Net Wt 5 kg", "FOR EXPORT ONLY"), RICE)
    assert exp["LM-19"].status == "FAIL"
    inst, cl = run_rules(lines_of("BASMATI RICE", "Net Wt 5 kg", "NOT FOR RETAIL SALE"), RICE)
    assert cl["package_type"]["id"] == "institutional"
    assert inst["LM-17"].status == "REVIEW" and inst["LM-06"].status == "NOT_APPLICABLE"


def test_customer_care_email_is_mandatory_since_2016():
    lines = lines_of("BASMATI RICE", "Net Qty 1 kg", "Customer care: 1800-120-4455")
    res, _ = run_rules(lines, RICE)
    assert res["LM-09"].status == "FAIL" and "e-mail" in res["LM-09"].reason


def test_cosmetics_rules_and_optional_veg_dot():
    lines = lines_of("HERBAL SHAMPOO", "Net Vol 180 ml", "Ingredients: Aqua, Sodium Laureth Sulfate", "Mfg. Lic. No. 25/HD/2019",
                     "Batch No. SH204", "Mfg 06/2026", "Use before 05/2028")
    res, cl = run_rules(lines, {"product_name": "Herbal shampoo", "category": "Personal Care"}, measurements={"veg_mark": {"found": False}})
    assert cl["category"].id == "cosmetics"
    assert res["CS-01"].status == "PASS" and res["CS-02"].status == "PASS" and res["LM-10"].status == "PASS"
    assert res["CS-03"].status == "NOT_APPLICABLE"  # optional since 21.09.2026
    assert "FS-01" not in res


def test_parsers():
    assert parse_date("Best before 03/2027")[0]["iso"] == "2027-03"
    assert parse_date("PKD: 14-Aug-26")[0]["iso"] == "2026-08-14"
    assert parse_date("EXP 2027-01-31")[0]["iso"] == "2027-01-31"
    assert parse_date("no date here") is None
    assert quantity_from_text("Net 2.5 L")["base_value"] == 2500
    assert quantity_from_text("250 ml")["base_unit"] == "ml"
    assert quantity_from_text("10 kgs")["standard_symbol"] is False
    assert quantity_from_text("2.25 m")["dimension"] == "length"
    assert expected_from_context({"product_name": "Refined Oil (1 L)", "declared_mrp": "168"}) == {
        "mrp": 168.0, "quantity": {"value": 1.0, "unit": "L", "base_unit": "ml", "base_value": 1000.0, "standard_symbol": True,
                                   "dimension": "volume"}}


def test_ocr_digit_confusions_are_tolerated():
    d = extract(lines_of("MRP Rs. 1O5.5O incl. of all taxes"), {}, TODAY)
    assert d["mrp"]["value"] == 105.5


def test_use_before_duration_and_rupee_misreads():
    d = extract(lines_of("Use before 36 months from manufacture", "Mfd. 09/2026", "MRP < 120.00 (incl. of all taxes)",
                         "Unit Sale Price: 2 30.00 per 100 ml"), {}, TODAY)
    assert d["best_before"]["iso"] == "2029-09"
    assert d["mrp"]["value"] == 120.0 and d["mrp"]["currency_symbol"]
    assert d["unit_sale_price"]["value"] == 30.0


def test_catalog_integrity():
    rules = re_.load_rules()
    tax = load_taxonomy()
    assert len(rules) >= 70 and len(tax.categories) >= 55
    # Every category gets the core declarations; every rule reaches at least one category
    core = {"LM-01", "LM-04", "LM-06", "LM-09"}
    for c in tax.categories:
        chosen = {r.id for r in select_rules(rules, c, "retail")[0]}
        assert "LM-00" in chosen and "LM-15" in chosen, c.id
        if not c.has("lm_exempt"):
            assert core <= chosen, c.id
    reached = {r.id for c in tax.categories for p in ("retail", "wholesale") for r in select_rules(rules, c, p)[0]}
    assert reached == {r.id for r in rules}


def test_gm_food_needs_gm_marking():
    gm, _ = run_rules(lines_of("CORN FLAKES", "Net Wt 500 g", "Ingredients: Maize (genetically modified), Sugar"),
                      {"product_name": "Corn flakes", "category": "Health Foods"})
    assert gm["FS-13"].status == "FAIL"
    marked, _ = run_rules(lines_of("GM", "CORN FLAKES", "Net Wt 500 g", "Ingredients: Maize (genetically modified), Sugar"),
                          {"product_name": "Corn flakes", "category": "Health Foods"})
    assert marked["FS-13"].status == "PASS"
    plain, _ = run_rules(COMPLIANT, RICE)
    assert plain["FS-13"].status == "NOT_APPLICABLE"
