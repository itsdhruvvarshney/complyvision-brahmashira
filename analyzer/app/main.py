"""ComplyVision Package Analyzer - FastAPI service.

    GET  /health   liveness + OCR / Gemini readiness
    GET  /rules    the rule catalog the engine evaluates
    POST /analyze  multipart image (+ optional case context) -> canonical JSON report
"""

from __future__ import annotations

import asyncio
import functools
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .ai.gemini import GeminiClient
from .config import ANALYZER_VERSION, settings
from .ocr.engine import OcrUnavailable, engine_status, get_engine
from .pipeline import analyze
from .rules.engine import load_rules
from .validation import ImageValidationError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("analyzer")

RULES = load_rules()
gemini = GeminiClient(settings.gemini_keys, settings.gemini_model, settings.gemini_timeout_s, settings.gemini_enabled)
executor = ThreadPoolExecutor(max_workers=settings.workers, thread_name_prefix="analyze")
_started = time.time()


def _engine():
    det, rec, ver = settings.ocr_models
    return get_engine(settings.ocr_lang, settings.ocr_mkldnn, settings.ocr_det_limit, det, rec, ver)


def _warm_up() -> None:
    try:
        engine = _engine()
        engine.recognize(np.full((48, 160, 3), 255, np.uint8))  # loads the verification model as well
        log.info("OCR engine ready (%s)", engine.models)
    except OcrUnavailable as exc:
        log.error("OCR engine unavailable: %s", exc)


@asynccontextmanager
async def lifespan(_: FastAPI):
    if settings.warm_up_ocr:
        threading.Thread(target=_warm_up, name="ocr-warmup", daemon=True).start()
    yield
    executor.shutdown(wait=False, cancel_futures=True)


app = FastAPI(title="ComplyVision Package Analyzer", version=ANALYZER_VERSION, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-API-Key"],
    max_age=600,
)


def _check_key(api_key: str | None) -> None:
    if settings.api_keys and api_key not in settings.api_keys:
        raise HTTPException(status_code=401, detail={"code": "unauthorized", "message": "Missing or invalid X-API-Key."})


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "version": ANALYZER_VERSION,
        "uptime_s": int(time.time() - _started),
        "ocr": engine_status(),
        "gemini": gemini.status(),
        "limits": {"max_upload_mb": settings.max_upload_mb, "types": ["image/jpeg", "image/png", "image/webp"]},
        "rules": len(RULES),
    }


@app.get("/rules")
def rules() -> dict:
    return {"rules": [{"id": r.id, "title": r.title, "reference": r.reference, "category": r.category,
                       "severity": r.severity, "description": r.description} for r in RULES]}


@app.post("/analyze")
async def analyze_endpoint(
    request: Request,
    image: UploadFile = File(..., description="Package photo (JPG, JPEG, PNG or WEBP, up to the configured limit)"),
    case_code: str | None = Form(None),
    product_name: str | None = Form(None),
    brand: str | None = Form(None),
    category: str | None = Form(None),
    declared_mrp: str | None = Form(None),
    declared_quantity: str | None = Form(None),
    marker_mm: str | None = Form(None),
    source: str | None = Form(None),
    x_api_key: str | None = Header(None),
):
    _check_key(x_api_key)

    # Read at most limit + 1 bytes so oversized uploads are rejected without buffering them fully
    data = await image.read(settings.max_upload_bytes + 1)
    await image.close()

    try:
        engine = _engine()
    except OcrUnavailable as exc:
        raise HTTPException(status_code=503, detail={"code": "ocr_unavailable", "message": str(exc)})

    context = {"case_code": case_code, "product_name": product_name, "brand": brand, "category": category,
               "declared_mrp": declared_mrp, "declared_quantity": declared_quantity, "marker_mm": marker_mm,
               "source": source}
    loop = asyncio.get_running_loop()
    try:
        deadline = time.monotonic() + settings.request_timeout_s
        report = await asyncio.wait_for(
            loop.run_in_executor(executor, functools.partial(
                analyze, data, image.filename, image.content_type, context, engine, gemini, settings, RULES,
                deadline=deadline)),
            timeout=settings.request_timeout_s,
        )
    except ImageValidationError as exc:
        return JSONResponse(status_code=exc.status, content={"code": exc.code, "message": exc.message})
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail={"code": "timeout", "message": "Analysis took too long. Please retry."})
    except Exception:
        log.exception("Analysis failed")
        raise HTTPException(status_code=500, detail={"code": "analysis_failed", "message": "The image could not be analysed."})

    log.info("Analysed %s: %s in %d ms", case_code or "-", report["summary"]["verdict"], report["duration_ms"])
    return report
