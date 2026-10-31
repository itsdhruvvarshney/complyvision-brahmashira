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
            └─ Compliance engine  ── prototype rule CSV + deterministic validators
                                     → PASS / FAIL / REVIEW / NOT_APPLICABLE
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

* `GET /health` – status, OCR readiness, Gemini key-pool status, limits.
* `GET /rules` – the rule catalog (from `app/rules/lm_rules.csv`).
* `POST /analyze` – multipart form: `image` (JPG / JPEG / PNG / WEBP) plus optional `case_code`,
  `product_name`, `brand`, `category`, `declared_mrp`, `declared_quantity`, `marker_mm`, `source`.

Errors return `{"code": ..., "message": ...}`: `415 unsupported_type / mime_mismatch`, `413 too_large`,
`400 corrupt_image / too_small`, `401 unauthorized`, `503 ocr_unavailable`, `504 timeout`.

## Decisions

* Package verdict: any **FAIL** → Failed; otherwise any **REVIEW** → Under Review; otherwise Passed.
* A missing declaration is a FAIL only on a good capture. On a poor capture, or when Gemini reports
  seeing it, the rule goes to REVIEW for the inspector to confirm. Gemini never passes a rule.
* `rules/lm_rules.csv` mirrors `public.compliance_rules` in Supabase (`supabase/inspector_module.sql`).

## Tests

```bash
pip install -r requirements-dev.txt
pytest -q
```

The tests run the whole pipeline (validation, OpenCV corrections, ArUco calibration, extraction,
rules, evidence, API) on synthetic labels with an OCR stand-in, so they do not need PaddleOCR.
