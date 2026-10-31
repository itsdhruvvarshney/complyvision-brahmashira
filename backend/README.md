# ComplyVision backend

The backend contains two independent services:

- `otp-server.js`: Express API for email OTP verification and registration.
- `analysis-service/`: FastAPI image analysis API with OCR, field extraction, package measurements, evidence images, and compliance report generation. It is stateless and does not connect to a database.

## Start locally

Install the Node dependencies from this directory with `npm install`. Copy `.env.example` to `.env` and set SMTP credentials plus a long random `OTP_SECRET` for real email delivery.

Set up the Python analysis service in another terminal:

```sh
cd backend/analysis-service
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
cd ../../frontend
npm run dev:analysis
```

Run `npm run dev` in `frontend` for Vite and the OTP server. Run `npm run dev:analysis` there for the analysis API. Vite proxies `/api/*` to port 3000 and `/analyze` plus `/health` to port 8000. PaddleOCR model initialization and downloads may take time on first startup. The API accepts JPEG and PNG images up to 10 MiB by default; set `SIH_MAX_UPLOAD_BYTES` to change the limit.

For analysis API deployment, use `analysis-service/render.yaml` as a Render Blueprint. Set `SIH_FRONTEND_ORIGINS` to the deployed frontend origin and configure `SIH_MAX_UPLOAD_BYTES` as needed. The service needs no database credentials.

The OTP service listens on port 3000. Set `ALLOWED_ORIGIN` to the deployed frontend origin. The in-memory OTP and registration stores are suitable for a single development process only.

## OTP endpoints

- `POST /api/otp/send` with `{ "email": "name@example.com" }`
- `POST /api/otp/verify` with `{ "email": "name@example.com", "code": "123456" }`
- `GET /api/health`

## Analysis endpoints

- `GET /health`
- `POST /analyze` with multipart form field `file` containing a JPEG or PNG image.

The analysis endpoint returns the canonical structured report, extracted declarations, rule results, measurements, OCR metadata, and evidence images. It does not persist reports or issue official compliance certificates.
