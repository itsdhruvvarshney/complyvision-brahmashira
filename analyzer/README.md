# ComplyVision Package Analyzer

FastAPI service that checks a photo of a packaged commodity against the Legal Metrology
(Packaged Commodities) Rules, 2011 and returns a canonical JSON report. The Android inspector app
uploads each sample package here, stores the report in Supabase and builds PDF / JSON reports from it.

```
Mobile camera / gallery
  └─ POST /analyze (multipart)  ── CORS allow-list · X-API-Key · MIME validation · thread pool · temp workspace
       └─ Package analyzer      ── shared PaddleOCR · per-request workspace · sanitised evidence
            ├─ Computer-vision pipeline (OpenCV)
            │    image quality · perspective + tilt correction · ArUco scale calibration
            │    glare repair · illumination / exposure / CLAHE contrast · denoise · sharpen
            │    PaddleOCR · conditional OCR verification · field + MRP extraction
            │    numeral glyph height (Rule 7) · local contrast analysis · evidence crop builder
            ├─ Gemini enrichment (pool of API keys, round-robin with cool-down)
            ├─ Product categoriser ── 61 categories (categories.json): case record + label text + AI reader
            │                         + regulatory fingerprints → category, confidence, package type
            └─ Category rule engine ── 76-rule catalog (lm_rules.csv) narrowed to the category's rules,
                                       Rule 3 / Rule 26 applicability gates, deterministic validators
                                       → PASS / FAIL / REVIEW / NOT_APPLICABLE (+ inspector resolution)
  ◄─ Canonical JSON: declarations · rule results · evidence images · quality warnings · summary counts
```

## Run it

PaddlePaddle publishes wheels for **Python 3.9 – 3.12**.

```bash
cd analyzer
py -3.12 -m venv .venv
.venv\Scripts\activate            # source .venv/bin/activate on macOS / Linux
pip install -r requirements.txt
copy .env.example .env            # then add your Gemini keys
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

The first start downloads the PaddleOCR models (~100 MB) and warms them up; `GET /health` reports
`"ocr": {"ready": true}` once it can take requests.

### Point the app at it

Add to the project's `local.properties` (git-ignored) and rebuild:

```
analyzer.url=http://10.0.2.2:8000        # Android emulator -> this PC
# analyzer.url=http://192.168.1.20:8000  # real phone on the same Wi-Fi (debug builds allow LAN http)
# analyzer.apiKey=...                    # only if ANALYZER_API_KEYS is set on the server
```

Release builds only allow HTTPS (plus the emulator alias), so deploy the service behind TLS for field use.

## Configuration (`.env`)

| Variable | Default | Purpose |
| --- | --- | --- |
| `GEMINI_API_KEYS` | – | Comma-separated pool of Gemini keys. Empty = AI insights skipped. |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Model used for enrichment. |
| `ANALYZER_API_KEYS` | – | Shared secrets clients must send as `X-API-Key`. Empty = open. |
| `ALLOWED_ORIGINS` | `http://localhost:3000` | CORS allow-list for the web portal. |
| `MAX_UPLOAD_MB` | `10` | Upload limit (also enforced on the phone). |
| `ANALYZER_WORKERS` | `2` | Thread-pool size for concurrent analyses. |
| `ARUCO_MARKER_MM` | `20` | Side length of the printed calibration marker. |
| `REQUIRE_CALIBRATION` | `false` | `true` turns "no marker" into REVIEW instead of N/A for the numeral-height rule. |
| `KEEP_WORKSPACE` | `false` | Keep each request's original / processed image for debugging. |

## ArUco calibration card

Numeral height (Rule 7) needs a known scale in the frame:

```bash
python tools/make_aruco_card.py --mm 20
```

Print `aruco_card_20mm.png` at 100 % scale and place it flat beside the label.

## API

* `GET /health` – status, OCR readiness, Gemini key-pool status, limits, catalog sizes.
* `GET /categories` – the product taxonomy: governing laws, quantity measure, BIS standards, rule count.
* `GET /rules` – the whole rule catalog; `GET /rules?category=edible_oil&package_type=retail` returns
  only the rules that govern that category (and the ids that do not).
* `POST /analyze` – multipart form: `image` (JPG / JPEG / PNG / WEBP) plus optional `case_code`,
  `product_name`, `brand`, `category`, `declared_mrp`, `declared_quantity`, `marker_mm`, `source`,
  `category_id` (inspector's choice; omit to auto-detect), `package_type` (`retail` / `wholesale` /
  `institutional` / `export`; omit to read it from the label) and `pdp_area_cm2` (measured display
  panel, used by the Rule 7 font-size check instead of the photo estimate).

## Category-aware rule engine

1. **Categorise.** `rules/classifier.py` scores every category in `rules/categories.json` from the
   product name, the case category, the OCR text, the AI reader's category and regulatory fingerprints
   (FSSAI number, `Mfg. Lic.` + IP/BP strength, CIR number, IS 14543 …). Below 60 % confidence the
   report says so (`LM-00` → REVIEW) and the app asks the inspector to confirm before saving.
2. **Select.** Each rule's `applies_to` selector (`tag:food`, `cat:edible_oil`, `-tag:lm_exempt` …)
   and `scope` (`retail` / `wholesale` / `always`) pick the rules for the category and package type.
   The rest are listed under `classification.not_relevant`.
3. **Gate.** Rule 3 (over 25 kg / 'not for retail sale') and the Rule 26(a) small-pack exemption switch
   the Chapter II declarations to NOT_APPLICABLE, except for tobacco and pan masala.
4. **Verify.** Validators in `rules/engine.py` decide each rule with the reason, expected, found and the
   label lines used as evidence. The AI reader can only move a FAIL to REVIEW; only an inspector can
   settle a REVIEW (`inspector_resolve_scan` in Supabase).

The catalog covers the LM (Packaged Commodities) Rules 2011 as amended up to the Fourth Amendment of
21.09.2026 (unit sale price, Table-I font sizes by display-panel area, e-mail in consumer care,
Fourth Schedule weight-with-volume for edible oils, pan-masala small-pack carve-out, optional veg dot on
cosmetics), plus the FSS labelling regulations, COTPA, the Drugs and Cosmetics Rules, Cosmetics Rules,
Medical Devices Rules, Insecticides Rules, FCO, Seeds Act and the BIS Quality Control Orders. Sources
and the deliberate limits of photo-based checking are in `app/rules/SOURCES.md`.

To change the catalog, edit `lm_rules.csv` / `categories.json`, run the tests, then regenerate the
database copy:

```bash
python tools/export_catalog_sql.py     # writes ../supabase/rule_engine.sql
```

Run `supabase/rule_engine.sql` after `inspector_module.sql` in the Supabase SQL editor.

Errors return `{"code": ..., "message": ...}`: `415 unsupported_type / mime_mismatch`, `413 too_large`,
`400 corrupt_image / too_small`, `401 unauthorized`, `503 ocr_unavailable`, `504 timeout`.

## Decisions

* Package verdict: any **FAIL** → Failed; otherwise any **REVIEW** → Under Review; otherwise Passed.
* A missing declaration is a FAIL only on a good capture. On a poor capture, or when Gemini reports
  seeing it, the rule goes to REVIEW for the inspector to confirm. Gemini never passes a rule.
* `rules/lm_rules.csv` and `rules/categories.json` mirror `public.compliance_rules` and
  `public.product_categories` in Supabase (`supabase/rule_engine.sql`, generated).
* A category the engine is unsure of is never saved silently: the inspector confirms or changes it.

## Tests

```bash
pip install -r requirements-dev.txt
pytest -q
```

The tests run the whole pipeline (validation, OpenCV corrections, ArUco calibration, extraction,
rules, evidence, API) on synthetic labels with an OCR stand-in, so they do not need PaddleOCR.
