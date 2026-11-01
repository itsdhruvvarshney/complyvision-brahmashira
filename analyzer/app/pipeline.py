"""Package analyzer pipeline: validation -> quality -> OpenCV pre-processing -> ArUco calibration ->
PaddleOCR -> conditional verification -> field extraction -> measurements -> Gemini enrichment ->
rule engine -> evidence -> canonical JSON report.
"""

from __future__ import annotations

import logging
import os
import re
import shutil
import tempfile
import time
import uuid
from contextlib import contextmanager
from datetime import date, datetime, timezone

import cv2
import numpy as np

from .config import ANALYZER_VERSION, Settings
from .extract.fields import expected_from_context, extract, fix_number, quantity_from_text
from .ocr.rescue import needs_rescue, rescue
from .ocr.verify import verify_lines
from .rules import engine as rules_engine
from .validation import validate_image
from .vision import evidence as evidence_mod
from .vision.aruco import calibrate
from .vision.measure import local_contrast, numeral_height, required_numeral_height_mm
from .vision.preprocess import correct_geometry, correct_photometry
from .vision.quality import assess

log = logging.getLogger("analyzer.pipeline")

SCHEMA = "complyvision.package-report/1.0"
STATUS_RANK = {"FAIL": 3, "REVIEW": 2, "PASS": 1, "NOT_APPLICABLE": 0}
CONTRAST_FIELDS = ("mrp", "net_quantity", "mfg_date", "best_before", "manufacturer", "customer_care")


class AnalysisAbandoned(Exception):
    """The request's time budget ran out; the caller has already given up waiting."""


class StageTimer:
    def __init__(self, deadline: float | None = None):
        self.stages: list[dict] = []
        self.deadline = deadline

    @contextmanager
    def stage(self, name: str, label: str):
        # Stop between stages once the client has been told it timed out, so a stale request does
        # not hold the shared OCR engine while the next scan waits behind it
        if self.deadline is not None and time.monotonic() > self.deadline:
            raise AnalysisAbandoned(name)
        started = time.perf_counter()
        entry = {"stage": name, "label": label, "status": "ok"}
        try:
            yield entry
        except Exception:
            entry["status"] = "error"
            raise
        finally:
            entry["ms"] = int((time.perf_counter() - started) * 1000)
            self.stages.append(entry)


@contextmanager
def request_workspace(settings: Settings):
    """Per-request scratch directory, always removed afterwards unless KEEP_WORKSPACE is set."""
    os.makedirs(settings.workspace_root, exist_ok=True)
    path = tempfile.mkdtemp(prefix="req-", dir=settings.workspace_root)
    try:
        yield path
    finally:
        if not settings.keep_workspace:
            shutil.rmtree(path, ignore_errors=True)


def _clean_context(raw: dict) -> dict:
    ctx = {}
    for key in ("case_code", "product_name", "brand", "category", "declared_quantity", "source"):
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            ctx[key] = re.sub(r"\s+", " ", value.strip())[:120]
    if raw.get("declared_mrp") not in (None, ""):
        mrp = fix_number(str(raw["declared_mrp"]))
        if mrp is not None and mrp > 0:
            ctx["declared_mrp"] = mrp
    if raw.get("marker_mm") not in (None, ""):
        try:
            mm = float(raw["marker_mm"])
            if 5 <= mm <= 200:
                ctx["marker_mm"] = mm
        except (TypeError, ValueError):
            pass
    return ctx


def _ai_cross_check(decl: dict, ai: dict) -> list[dict]:
    if not ai.get("available"):
        return []
    checks = []

    def add(field_name: str, parser, ai_value, agree):
        if parser is None and ai_value in (None, ""):
            return
        checks.append({"field": field_name, "parser": parser, "ai": ai_value, "agree": agree})

    mrp = (decl.get("mrp") or {}).get("value")
    ai_mrp = fix_number(re.sub(r"[^0-9.,]", "", str(ai.get("mrp") or ""))) if ai.get("mrp") else None
    add("mrp", mrp, ai.get("mrp"), None if mrp is None or ai_mrp is None else abs(mrp - ai_mrp) < 0.01)

    q = decl.get("net_quantity")
    ai_q = quantity_from_text(str(ai.get("net_quantity") or ""))
    add("net_quantity", f"{q['value']:g} {q['unit']}" if q else None, ai.get("net_quantity"),
        None if not q or not ai_q else (q["base_unit"] == ai_q["base_unit"] and abs(q["base_value"] - ai_q["base_value"]) < 0.5))

    mfg = (decl.get("mfg_date") or {}).get("iso")
    add("mfg_date", mfg, ai.get("mfg_date"), None)
    bb = (decl.get("best_before") or {}).get("iso")
    add("best_before", bb, ai.get("best_before"), None)
    batch = (decl.get("batch_no") or {}).get("value")
    add("batch_no", batch, ai.get("batch_no"),
        None if not batch or not ai.get("batch_no") else re.sub(r"\W", "", batch).upper() == re.sub(r"\W", "", str(ai["batch_no"])).upper())
    return checks


def analyze(data: bytes, filename: str | None, declared_mime: str | None, raw_context: dict,
            ocr_engine, gemini, settings: Settings, rules: list | None = None, today: date | None = None,
            deadline: float | None = None) -> dict:
    timer = StageTimer(deadline)
    started = time.perf_counter()
    report_id = str(uuid.uuid4())
    context = _clean_context(raw_context or {})
    rules = rules or rules_engine.load_rules()
    today = today or date.today()

    with request_workspace(settings) as workspace:
        with timer.stage("validate", "MIME & integrity validation"):
            image = validate_image(data, filename, declared_mime, settings.max_upload_bytes)
            if settings.keep_workspace:
                with open(os.path.join(workspace, "original" + {"image/jpeg": ".jpg", "image/png": ".png"}.get(image.mime, ".webp")), "wb") as fh:
                    fh.write(data)

        with timer.stage("quality", "Image quality assessment"):
            quality = assess(image.bgr)

        with timer.stage("preprocess", "Perspective & tilt correction"):
            geometric, steps, geometry = correct_geometry(image.bgr, settings.max_long_edge)

        with timer.stage("calibration", "ArUco scale calibration"):
            calibration = calibrate(geometric, context.get("marker_mm", settings.aruco_marker_mm))

        with timer.stage("enhance", "Glare, exposure & contrast correction"):
            protect = [calibration["box"]] if calibration.get("detected") else []
            processed, photometric_steps = correct_photometry(geometric, quality, protect)
            steps += photometric_steps
            if settings.keep_workspace:
                cv2.imwrite(os.path.join(workspace, "processed.jpg"), processed)
        quality["skew_deg"] = geometry["skew_deg"]
        if abs(geometry["skew_deg"]) >= 8:
            quality["warnings"].append({"code": "TILT", "severity": "low",
                                        "message": f"Package was tilted by {geometry['skew_deg']:.0f} deg; it was straightened."})

        with timer.stage("ocr", "PaddleOCR text recognition"):
            lines = ocr_engine.read(processed)

        rescue_info = None
        with timer.stage("rescue", "Adaptive re-read (orientation & tiles)") as st:
            why = needs_rescue(extract([ln for ln in lines if ln.confidence >= settings.ocr_min_confidence], context, today), lines)
            if why and settings.ocr_rescue:
                try:
                    processed, lines, rescue_info = rescue(ocr_engine, processed, lines, why)
                    if rescue_info["rotated_deg"]:
                        calibration = calibrate(processed, context.get("marker_mm", settings.aruco_marker_mm))
                        quality["warnings"].append({"code": "ORIENTATION", "severity": "low",
                                                    "message": f"Photo was turned {rescue_info['rotated_deg']} deg; it was rotated upright."})
                except Exception:  # a failed second look never breaks the first result
                    log.exception("OCR rescue failed")
                    st["status"] = "skipped"
            else:
                st["status"] = "skipped"

        with timer.stage("verify", "Conditional OCR verification"):
            verification = verify_lines(ocr_engine, processed, lines, settings.verify_below_confidence, settings.ocr_verify_max)
            dropped = [ln for ln in lines if ln.confidence < settings.ocr_min_confidence]
            lines = [ln for ln in lines if ln.confidence >= settings.ocr_min_confidence]
            verification["dropped_low_confidence"] = len(dropped)
            if rescue_info:
                verification["rescue"] = rescue_info

        with timer.stage("extract", "Field extraction"):
            decl = extract(lines, context, today)
            expected = expected_from_context(context)
            if context.get("product_name"):
                expected["product_words"] = context["product_name"]

        with timer.stage("measure", "Glyph height & local contrast"):
            boxes = evidence_mod.field_boxes(decl, lines)
            measurements: dict = {}
            q = decl.get("net_quantity")
            if q and q.get("base_unit") in ("g", "ml"):
                measurements["numeral_required_mm"] = required_numeral_height_mm(q["base_value"])
                if calibration.get("detected") and q.get("lines"):
                    measurements["numeral_height"] = numeral_height(processed, lines[q["lines"][0]], calibration["px_per_mm"])
            contrast = {}
            for key in CONTRAST_FIELDS:
                field_value = decl.get(key)
                if field_value and field_value.get("lines"):
                    c = local_contrast(processed, lines[field_value["lines"][0]].box)
                    if c:
                        contrast[key] = c
            worst = min(contrast.items(), key=lambda kv: kv[1]["ratio"]) if contrast else None
            measurements["contrast"] = {"fields": contrast, "worst": {"field": worst[0], **worst[1]} if worst else None}

        ocr_text = "\n".join(ln.text for ln in lines)
        with timer.stage("ai", "Gemini enrichment") as st:
            ok, jpg = cv2.imencode(".jpg", _limit(processed, 1600), [cv2.IMWRITE_JPEG_QUALITY, 85])
            ai = gemini.enrich(jpg.tobytes() if ok else b"", ocr_text, decl, context) if gemini else \
                {"available": False, "reason": "Gemini is not configured."}
            if not ai.get("available"):
                st["status"] = "skipped"

        mean_conf = float(np.mean([ln.confidence for ln in lines])) if lines else 0.0
        with timer.stage("rules", "Compliance rule engine"):
            ctx = rules_engine.RuleContext(
                decl=decl, quality=quality, calibration=calibration, measurements=measurements, expected=expected,
                ai=ai if ai.get("available") else {}, ocr_mean_confidence=mean_conf, ocr_line_count=len(lines),
                require_calibration=settings.require_calibration, today=today)
            results = rules_engine.evaluate(rules, ctx)

        with timer.stage("evidence", "Evidence crop builder"):
            field_status: dict[str, str] = {}
            field_rules: dict[str, list[str]] = {}
            for rule, outcome in results:
                for f in outcome.fields:
                    field_rules.setdefault(f, []).append(rule.id)
                    if STATUS_RANK[outcome.status] > STATUS_RANK.get(field_status.get(f, "NOT_APPLICABLE"), 0) or f not in field_status:
                        field_status[f] = outcome.status
            if calibration.get("detected"):
                boxes["calibration"] = tuple(calibration["box"])
            confidences = {k: float((decl.get(k) or {}).get("confidence", 1.0 if k == "calibration" else 0.0)) for k in boxes}
            evidence, evidence_ids = evidence_mod.build(processed, boxes, field_status, field_rules, confidences)
            annotated = evidence_mod.annotate(processed, evidence)

        rule_rows = []
        for rule, outcome in results:
            rule_rows.append({
                "id": rule.id, "title": rule.title, "reference": rule.reference, "category": rule.category,
                "severity": rule.severity, "status": outcome.status, "reason": outcome.reason,
                "expected": outcome.expected, "found": outcome.found,
                "evidence": sorted({evidence_ids[f] for f in outcome.fields if f in evidence_ids}),
            })

    statuses = [r["status"] for r in rule_rows]
    counts = {s: statuses.count(s) for s in ("PASS", "FAIL", "REVIEW", "NOT_APPLICABLE")}
    applicable = counts["PASS"] + counts["FAIL"] + counts["REVIEW"]
    overall = rules_engine.verdict(statuses)

    declarations = {k: v for k, v in decl.items() if not k.startswith("_")}
    warnings = image.warnings + quality["warnings"]
    if not calibration.get("detected"):
        warnings.append({"code": "NO_CALIBRATION", "severity": "info",
                         "message": "No ArUco marker in frame - numeral height could not be measured."})
    elif calibration.get("warning"):
        warnings.append({"code": "CALIBRATION_ANGLE", "severity": "low", "message": calibration["warning"]})
    if not ai.get("available"):
        warnings.append({"code": "AI_UNAVAILABLE", "severity": "info", "message": ai.get("reason", "Gemini unavailable")})

    return {
        "schema": SCHEMA,
        "report_id": report_id,
        "analyzer_version": ANALYZER_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "duration_ms": int((time.perf_counter() - started) * 1000),
        "input": image.describe(),
        "context": context,
        "summary": {
            "verdict": overall,
            "passed": counts["PASS"], "failed": counts["FAIL"], "review": counts["REVIEW"],
            "not_applicable": counts["NOT_APPLICABLE"],
            "score": round(100.0 * counts["PASS"] / applicable, 1) if applicable else 0.0,
            "critical_failures": [r["id"] for r in rule_rows if r["status"] == "FAIL" and r["severity"] == "Critical"],
            "headline": _headline(overall, rule_rows),
        },
        "quality": quality,
        "preprocessing": steps,
        "calibration": calibration,
        "ocr": {
            "engine": getattr(ocr_engine, "name", "OCR"),
            "lines": len(lines),
            "mean_confidence": round(mean_conf, 4),
            "verification": verification,
            "text": ocr_text,
            "items": [ln.to_json() for ln in lines],
        },
        "declarations": declarations,
        "measurements": measurements,
        "rules": rule_rows,
        "evidence": evidence,
        "annotated_image": annotated,
        "ai_insights": ai,
        "ai_cross_check": _ai_cross_check(decl, ai),
        "warnings": warnings,
        "pipeline": timer.stages,
    }


def _limit(img: np.ndarray, long_edge: int) -> np.ndarray:
    h, w = img.shape[:2]
    s = long_edge / max(h, w)
    return cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else img


def _headline(verdict: str, rows: list[dict]) -> str:
    fails = [r["title"] for r in rows if r["status"] == "FAIL"]
    reviews = [r["title"] for r in rows if r["status"] == "REVIEW"]
    if verdict == "FAIL":
        return f"Non-compliant: {fails[0]}" + (f" and {len(fails) - 1} more" if len(fails) > 1 else "")
    if verdict == "REVIEW":
        return f"Needs review: {reviews[0]}" + (f" and {len(reviews) - 1} more" if len(reviews) > 1 else "")
    return "All applicable Legal Metrology declarations verified"
