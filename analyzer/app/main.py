"""ComplyVision Package Analyzer - FastAPI service.

    GET  /health       liveness + OCR / Gemini readiness
    GET  /categories   the product taxonomy (category -> laws, quantity measure, rule count)
    GET  /rules        the rule catalog; ?category=<id>&package_type=<id> returns the rules that apply
    POST /analyze      multipart image (+ optional case context) -> canonical JSON report
    POST /ask, /compare     consumer Q&A and product comparison (Gemini)
    POST /assist/frame      Accessibility Mode: one live camera frame -> spoken positioning cue
    POST /assist/turn       Accessibility Mode: one conversation step (reads captured photos when sent)
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

from .ai.assist import Assistant, decode_image, split_keys
from .ai.gemini import GeminiClient
from .config import ANALYZER_VERSION, settings
from .ocr.engine import OcrUnavailable, engine_status, get_engine
from .pipeline import analyze
from .rules.catalog import load_taxonomy, select_rules
from .rules.engine import applicability, load_rules
from .validation import ImageValidationError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("analyzer")

RULES = load_rules()
TAXONOMY = load_taxonomy()
gemini = GeminiClient(settings.gemini_keys, settings.gemini_model, settings.gemini_timeout_s, settings.gemini_enabled)
_brain_keys, _vision_keys = split_keys(settings.gemini_keys, settings.assist_brain_keys, settings.assist_vision_keys)
assistant = Assistant(
    GeminiClient(_brain_keys, settings.assist_model, settings.gemini_timeout_s, settings.gemini_enabled),
    GeminiClient(_vision_keys, settings.assist_model, settings.gemini_timeout_s, settings.gemini_enabled),
    settings.assist_frame_thinking, settings.assist_turn_thinking)
MAX_EXTRA_PHOTOS = 5
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
        "assist": assistant.status(),
        "limits": {"max_upload_mb": settings.max_upload_mb, "types": ["image/jpeg", "image/png", "image/webp"]},
        "rules": len(RULES),
        "categories": len(TAXONOMY.categories),
        "taxonomy_version": TAXONOMY.version,
    }


def _rule_json(r, cat=None) -> dict:
    out = {"id": r.id, "title": r.title, "reference": r.reference, "category": r.category, "section": r.section,
           "severity": r.severity, "scope": r.scope, "applies_to": " ".join(r.applies_to), "description": r.description}
    if cat is not None:
        out["applies"] = applicability(r, cat, TAXONOMY.tag_glossary)
    return out


@app.get("/categories")
def categories() -> dict:
    items = []
    for c in TAXONOMY.categories:
        chosen, _ = select_rules(RULES, c, "retail")
        items.append({**c.summary(), "group_name": TAXONOMY.groups.get(c.group, c.group), "tags": sorted(c.tags),
                      "laws": list(c.laws), "quantity": {"dims": list(c.dims), "ref": c.quantity_ref},
                      "standards": c.standards or None, "rules": len(chosen)})
    return {"version": TAXONOMY.version, "groups": [{"id": k, "name": v} for k, v in TAXONOMY.groups.items()],
            "package_types": [{k: p[k] for k in ("id", "name", "ref") if k in p} for p in TAXONOMY.package_types],
            "categories": items}


@app.get("/rules")
def rules(category: str | None = None, package_type: str = "retail") -> dict:
    if category is None:
        return {"rules": [_rule_json(r) for r in RULES]}
    cat = TAXONOMY.get(category)
    if cat is None:
        raise HTTPException(status_code=404, detail={"code": "unknown_category", "message": f"No category '{category}'."})
    chosen, skipped = select_rules(RULES, cat, package_type)
    return {"category": cat.summary(), "laws": list(cat.laws), "package_type": package_type,
            "rules": [_rule_json(r, cat) for r in chosen], "not_relevant": [r.id for r in skipped]}


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
    category_id: str | None = Form(None, description="Inspector's category choice; omit to auto-detect"),
    package_type: str | None = Form(None, description="retail / wholesale / institutional / export; omit to read from the label"),
    pdp_area_cm2: str | None = Form(None, description="Measured principal display panel area for the Rule 7 font-size check"),
    authority: str | None = Form(None, description="fssai or bis: scope the check to one regulator"),
    mode: str | None = Form(None, description="consumer | inspector | org"),
    photo_labels: str | None = Form(None, description="comma separated labels for image, extra... e.g. front,back,side"),
    extra: list[UploadFile] = File(default=[], description="More photos of the same product (up to 5)"),
    x_api_key: str | None = Header(None),
):
    _check_key(x_api_key)

    # Read at most limit + 1 bytes so oversized uploads are rejected without buffering them fully
    data = await image.read(settings.max_upload_bytes + 1)
    await image.close()
    if len(extra) > MAX_EXTRA_PHOTOS:
        raise HTTPException(status_code=422, detail={"code": "too_many_photos", "message": f"Send at most {MAX_EXTRA_PHOTOS + 1} photos of one product."})
    extras = []
    for up in extra:
        extras.append((await up.read(settings.max_upload_bytes + 1), up.filename, up.content_type))
        await up.close()

    try:
        engine = _engine()
    except OcrUnavailable as exc:
        raise HTTPException(status_code=503, detail={"code": "ocr_unavailable", "message": str(exc)})

    context = {"case_code": case_code, "product_name": product_name, "brand": brand, "category": category,
               "declared_mrp": declared_mrp, "declared_quantity": declared_quantity, "marker_mm": marker_mm,
               "source": source, "category_id": category_id, "package_type": package_type, "pdp_area_cm2": pdp_area_cm2,
               "authority": authority, "mode": mode, "photo_labels": photo_labels}
    loop = asyncio.get_running_loop()
    try:
        deadline = time.monotonic() + settings.request_timeout_s
        report = await asyncio.wait_for(
            loop.run_in_executor(executor, functools.partial(
                analyze, data, image.filename, image.content_type, context, engine, gemini, settings, RULES,
                deadline=deadline, extra_images=extras or None)),
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


# ---------------------------------------------------------------------------------------------
# Consumer helpers: Ask ComplyVision and product comparison (Gemini, keys stay on this server)
# ---------------------------------------------------------------------------------------------
from pydantic import BaseModel, Field  # noqa: E402


class AskBody(BaseModel):
    question: str = Field(min_length=2, max_length=800)
    product: dict
    history: list[dict] = Field(default_factory=list, max_length=12)


class CompareBody(BaseModel):
    a: dict
    b: dict


def _gemini_json(call, *args):
    out = call(*args)
    if not out.get("available"):
        raise HTTPException(status_code=503, detail={"code": "ai_unavailable", "message": "The AI assistant is busy right now. Please try again in a moment."})
    for k in ("available", "model", "key_slot", "latency_ms"):
        out.pop(k, None)
    return out


@app.post("/ask")
async def ask(body: AskBody, x_api_key: str | None = Header(None)):
    _check_key(x_api_key)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(executor, lambda: _gemini_json(gemini.ask, body.question, body.product, body.history))


@app.post("/compare")
async def compare(body: CompareBody, x_api_key: str | None = Header(None)):
    _check_key(x_api_key)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(executor, lambda: _gemini_json(gemini.compare, body.a, body.b))


# ---------------------------------------------------------------------------------------------
# Accessibility Mode (blind users): live positioning cues and the voice conversation
# ---------------------------------------------------------------------------------------------
MAX_ASSIST_IMAGE_B64 = 6 * 1024 * 1024


class AssistFrameBody(BaseModel):
    image: str = Field(min_length=100, max_length=MAX_ASSIST_IMAGE_B64)
    target: str = "any_side"
    goal: str = Field(default="", max_length=300)
    lang: str = "hi"


class AssistCapture(BaseModel):
    image: str = Field(min_length=100, max_length=MAX_ASSIST_IMAGE_B64)
    label: str = Field(default="photo", max_length=40)


class AssistTurnBody(BaseModel):
    now: dict
    history: list[dict] = Field(default_factory=list, max_length=80)
    memory: str = Field(default="", max_length=4000)
    lang: str = "hi"
    script: str = "devanagari"
    today: str = Field(default="", max_length=40)
    goal: str = Field(default="", max_length=300)
    frame: str | None = Field(default=None, max_length=MAX_ASSIST_IMAGE_B64)
    captures: list[AssistCapture] = Field(default_factory=list, max_length=4)


def _image_or_422(data: str | None) -> bytes | None:
    try:
        return decode_image(data)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail={"code": "bad_image", "message": str(exc)})


def _assist_ready() -> None:
    if not assistant.enabled:
        raise HTTPException(status_code=503, detail={"code": "ai_unavailable", "message": "The assistant is not configured (set GEMINI_API_KEYS)."})


@app.post("/assist/frame")
async def assist_frame(body: AssistFrameBody, x_api_key: str | None = Header(None)):
    _check_key(x_api_key)
    _assist_ready()
    image = _image_or_422(body.image)
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(executor, lambda: assistant.frame(image, body.target, body.goal, body.lang))


@app.post("/assist/turn")
async def assist_turn(body: AssistTurnBody, x_api_key: str | None = Header(None)):
    _check_key(x_api_key)
    _assist_ready()
    frame = _image_or_422(body.frame)
    captures = [(_image_or_422(c.image), c.label) for c in body.captures]
    loop = asyncio.get_running_loop()
    out = await loop.run_in_executor(executor, lambda: assistant.turn(
        now=body.now, history=body.history, memory=body.memory, lang=body.lang, script=body.script,
        today=body.today, frame=frame, captures=captures or None, goal=body.goal))
    if not out.get("available"):
        raise HTTPException(status_code=503, detail={"code": "ai_unavailable", "message": "The assistant is busy right now. Please try again in a moment."})
    out.pop("available", None)
    return out
