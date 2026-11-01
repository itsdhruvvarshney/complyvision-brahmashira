"""Package analyzer pipeline: validation -> quality -> OpenCV pre-processing -> ArUco calibration ->
PaddleOCR -> conditional verification -> field extraction -> measurements -> Gemini enrichment ->
rule engine -> evidence -> canonical JSON report.
"""

from __future__ import annotations

import concurrent.futures
import dataclasses
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

from .ai import review as ai_review
from .config import ANALYZER_VERSION, Settings
from .extract.fields import expected_from_context, extract, fix_number, normalise_text, quantity_from_text
from .extract.label import extract_label_facts
from .ocr.rescue import needs_rescue, rescue
from .ocr.verify import verify_lines
from .rules import catalog
from .rules import engine as rules_engine
from .rules.classifier import classify
from .validation import validate_image
from .vision import evidence as evidence_mod
from .vision.aruco import calibrate
from .vision.measure import estimate_pdp_area_cm2, local_contrast, numeral_height, required_numeral_height_mm
from .vision.preprocess import correct_geometry, correct_photometry
from .vision.quality import assess
from .vision.symbols import detect_veg_mark

log = logging.getLogger("analyzer.pipeline")

SCHEMA = "complyvision.package-report/1.1"
STATUS_RANK = {"FAIL": 3, "REVIEW": 2, "PASS": 1, "NOT_APPLICABLE": 0}
CONTRAST_FIELDS = ("mrp", "net_quantity", "mfg_date", "best_before", "manufacturer", "customer_care")
HEIGHT_FIELDS = ("net_quantity", "mrp")


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
    for key in ("case_code", "product_name", "brand", "category", "declared_quantity", "source", "category_id", "package_type", "authority", "mode", "photo_labels"):
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            ctx[key] = re.sub(r"\s+", " ", value.strip())[:120]
    ctx["mode"] = ctx.get("mode", "inspector").lower()
    if ctx["mode"] not in ("consumer", "inspector", "org"):
        ctx["mode"] = "inspector"
    if ctx.get("authority"):
        ctx["authority"] = ctx["authority"].lower()
        if ctx["authority"] not in catalog.AUTHORITIES:
            ctx.pop("authority")
    if ctx.get("category_id") and not catalog.load_taxonomy().get(ctx["category_id"]):
        ctx.pop("category_id")  # unknown ids fall back to auto-detection
    if raw.get("pdp_area_cm2") not in (None, ""):
        try:
            area = float(raw["pdp_area_cm2"])
            if 1 <= area <= 20000:
                ctx["pdp_area_cm2"] = area
        except (TypeError, ValueError):
            pass
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


PHOTO_GAP = 36


def _read_extra(data: bytes, filename: str | None, mime: str | None, ocr_engine, settings: Settings, context: dict, today: date):
    """A supplementary photo of the same product: validate, straighten, repair, read. No ArUco, no evidence crops."""
    image = validate_image(data, filename, mime, settings.max_upload_bytes)
    quality = assess(image.bgr)
    geometric, steps, geometry = correct_geometry(image.bgr, settings.max_long_edge)
    processed, photometric_steps = correct_photometry(geometric, quality, [])
    lines = ocr_engine.read(processed)
    why = needs_rescue(extract([ln for ln in lines if ln.confidence >= settings.ocr_min_confidence], context, today), lines)
    if why and settings.ocr_rescue and len(lines) < 8:  # supplementary photos only get the slow second look when almost empty
        try:
            processed, lines, _ = rescue(ocr_engine, processed, lines, why)
        except Exception:
            log.exception("OCR rescue failed on a supplementary photo")
    verify_lines(ocr_engine, processed, lines, settings.verify_below_confidence, min(6, settings.ocr_verify_max))
    lines = [ln for ln in lines if ln.confidence >= settings.ocr_min_confidence]
    return image, quality, processed, lines


def _stack(parts: list[tuple[np.ndarray, list]]) -> tuple[np.ndarray, list, list[tuple[int, int]]]:
    """Joins the photos into one tall canvas so extraction, rules and evidence work on a single image.
    Each photo is scaled to the first one's width; its OCR boxes are scaled and shifted with it."""
    width = parts[0][0].shape[1]
    canvases, all_lines, spans, y = [], [], [], 0
    for n, (img, lines) in enumerate(parts):
        r = width / img.shape[1]
        scaled = img if abs(r - 1) < 1e-6 else cv2.resize(img, (width, max(1, int(img.shape[0] * r))), interpolation=cv2.INTER_AREA)
        if n:
            canvases.append(np.full((PHOTO_GAP, width, 3), 128, np.uint8))
            y += PHOTO_GAP
        for ln in lines:
            x0, y0, w0, h0 = ln.box
            poly = [[int(px * r), int(py * r + y)] for px, py in (ln.polygon or [])]
            all_lines.append(dataclasses.replace(ln, box=(int(x0 * r), int(y0 * r + y), max(1, int(w0 * r)), max(1, int(h0 * r))), polygon=poly))
        canvases.append(scaled)
        spans.append((y, y + scaled.shape[0]))
        y += scaled.shape[0]
    return np.vstack(canvases), all_lines, spans


def _photo_of(y_center: float, spans: list[tuple[int, int]]) -> int:
    for i, (a, b) in enumerate(spans):
        if a <= y_center < b + PHOTO_GAP:
            return i
    return len(spans) - 1


def _crop_photo(canvas: np.ndarray, span: tuple[int, int]) -> np.ndarray:
    return canvas[span[0]:span[1]]


def analyze(data: bytes, filename: str | None, declared_mime: str | None, raw_context: dict,
            ocr_engine, gemini, settings: Settings, rules: list | None = None, today: date | None = None,
            deadline: float | None = None, extra_images: list | None = None) -> dict:
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

        consumer = context["mode"] == "consumer"
        if consumer:
            # Numeral-height measurement is an inspector task: citizens never need the ArUco scale marker
            calibration = {"detected": False, "skipped": True, "reason": "Not used in consumer checks"}
        else:
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
                    if rescue_info["rotated_deg"] and not consumer:
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

        photo_labels = [x.strip() for x in (context.get("photo_labels") or "").split(",")]
        photo_jpegs: list[bytes] = []
        photo_info = [{"index": 0, "label": (photo_labels[0] if photo_labels and photo_labels[0] else "front"), **image.describe(),
                       "lines": len(lines)}]
        spans = [(0, processed.shape[0])]
        singles = [(processed, lines)]
        if extra_images:
            with timer.stage("photos", f"Reading {len(extra_images)} more photo(s)"):
                for k, (edata, efname, emime) in enumerate(extra_images, start=1):
                    eimg, equality, eprocessed, elines = _read_extra(edata, efname, emime, ocr_engine, settings, context, today)
                    singles.append((eprocessed, elines))
                    label = photo_labels[k] if k < len(photo_labels) and photo_labels[k] else f"photo {k + 1}"
                    photo_info.append({"index": k, "label": label, **eimg.describe(), "lines": len(elines)})
                    quality["warnings"] += [{**w, "message": f"Photo {k + 1}: {w['message']}"} for w in equality.get("warnings", [])
                                            if w.get("severity") != "info"][:2]
                processed, lines, spans = _stack(singles)
        for pr, _ln in singles:
            okj, jb = cv2.imencode(".jpg", _limit(pr, 1600), [cv2.IMWRITE_JPEG_QUALITY, 85])
            photo_jpegs.append(jb.tobytes() if okj else b"")
        first_height = spans[0][1]

        with timer.stage("extract", "Field extraction"):
            decl = extract(lines, context, today)
            texts = [normalise_text(ln.text) for ln in lines]
            decl.update(extract_label_facts(lines, texts, decl))
            expected = expected_from_context(context)
            if context.get("product_name"):
                expected["product_words"] = context["product_name"]

        with timer.stage("measure", "Glyph height, contrast & symbols"):
            measurements: dict = {}
            veg = detect_veg_mark(processed)
            measurements["veg_mark"] = veg
            if veg.get("found"):
                decl["veg_mark"] = {"kind": veg["kind"], "box": veg["box"], "confidence": veg["score"], "lines": []}
            boxes = evidence_mod.field_boxes(decl, lines)
            if veg.get("found"):
                boxes["veg_mark"] = tuple(veg["box"])
            if decl.get("net_quantity"):
                pdp = None
                if context.get("pdp_area_cm2"):
                    pdp = {"area_cm2": context["pdp_area_cm2"], "source": "entered by inspector"}
                elif calibration.get("detected"):
                    est = estimate_pdp_area_cm2(lines, processed.shape, calibration["px_per_mm"])
                    if est:
                        pdp = {"area_cm2": est["estimate_cm2"], "source": "estimated", **est}
                if pdp:
                    measurements["pdp"] = pdp
                    measurements["numeral_required_mm"] = required_numeral_height_mm(pdp["area_cm2"])
                if calibration.get("detected"):
                    heights = []
                    for key in HEIGHT_FIELDS:
                        f = decl.get(key)
                        if f and f.get("lines") and lines[f["lines"][0]].box[1] < first_height:
                            h = numeral_height(processed, lines[f["lines"][0]], calibration["px_per_mm"])
                            if h:
                                heights.append({**h, "field": key})
                    if heights:
                        measurements["numeral_height"] = min(heights, key=lambda x: x["height_mm"])
                        measurements["numeral_heights"] = heights
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
        def time_left() -> float:
            return (deadline - time.monotonic()) if deadline is not None else 1e9

        mode = context["mode"]
        early_intelligence = None
        labels_list = [p["label"] for p in photo_info]
        with timer.stage("ai", "Gemini enrichment") as st:
            # Gemini is an enhancement: it gets a time budget and can never make the whole scan fail
            budget = min(45.0, time_left() - 90.0)
            if not gemini or not gemini.enabled:
                ai = {"available": False, "reason": "Gemini is not configured."}
            elif budget < 12:
                ai = {"available": False, "reason": "AI reading was skipped to stay within the time limit."}
            elif mode == "consumer":
                # The product-intelligence call does not need the rule results, so it runs alongside the label reading
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    f_ai = pool.submit(gemini.enrich, [j for j in photo_jpegs if j], ocr_text, decl, context, labels_list, budget)
                    f_intel = pool.submit(gemini.intelligence, photo_jpegs, ocr_text, {}, labels_list, min(80.0, time_left() - 40.0))
                    ai = f_ai.result()
                    early_intelligence = f_intel.result()
            else:
                ai = gemini.enrich([j for j in photo_jpegs if j], ocr_text, decl, context, labels_list, budget)
            if not ai.get("available"):
                st["status"] = "skipped"

        mean_conf = float(np.mean([ln.confidence for ln in lines])) if lines else 0.0
        taxonomy = catalog.load_taxonomy()
        with timer.stage("classify", "Product categorisation"):
            classification = classify(taxonomy, context, "\n".join(texts), ai if ai.get("available") else {})
            category = classification["category"]
            package = classification["package_type"]["id"]
            authority = context.get("authority")
            selected, skipped = catalog.select_rules(rules, category, package)
            in_scope = True
            if authority:
                selected = catalog.rules_for_authority(selected, authority)
                in_scope = category.id in catalog.categories_for(authority, taxonomy)

        with timer.stage("rules", "Category rule engine"):
            ctx = rules_engine.RuleContext(
                decl=decl, quality=quality, calibration=calibration, measurements=measurements, expected=expected,
                ai=ai if ai.get("available") else {}, ocr_mean_confidence=mean_conf, ocr_line_count=len(lines),
                require_calibration=settings.require_calibration, today=today, category=category,
                classification=classification, package_type=package, texts=texts, context=context)
            results = rules_engine.evaluate(selected, ctx)
            gate = rules_engine.scope_gate(ctx)

        with timer.stage("evidence", "Evidence crop builder"):
            field_status: dict[str, str] = {}
            field_rules: dict[str, list[str]] = {}
            labels: dict[str, str] = {}
            for rule, outcome in results:
                keys = list(outcome.fields)
                if outcome.lines and not outcome.fields:
                    # Rule-specific text (warnings, licence numbers ...) gets its own crop
                    key = f"rule:{rule.id}"
                    box = evidence_mod.union_box([lines[i].box for i in outcome.lines if 0 <= i < len(lines)])
                    if box:
                        boxes[key] = box
                        labels[key] = rule.title
                        keys = [key]
                for f in keys:
                    field_rules.setdefault(f, []).append(rule.id)
                    if STATUS_RANK[outcome.status] > STATUS_RANK.get(field_status.get(f, "NOT_APPLICABLE"), 0) or f not in field_status:
                        field_status[f] = outcome.status
                outcome.fields = keys
            if calibration.get("detected"):
                boxes["calibration"] = tuple(calibration["box"])
            confidences = {k: float((decl.get(k) or {}).get("confidence", 1.0 if k == "calibration" else 0.0)) for k in boxes}
            for key in labels:
                confidences[key] = mean_conf
            evidence, evidence_ids = evidence_mod.build(processed, boxes, field_status, field_rules, confidences, labels)
            annotated = evidence_mod.annotate(processed, evidence)

        rule_rows = []
        for rule, outcome in results:
            rule_rows.append({
                "id": rule.id, "title": rule.title, "reference": rule.reference, "category": rule.category,
                "section": rule.section, "severity": rule.severity, "status": outcome.status, "reason": outcome.reason,
                "expected": outcome.expected, "found": outcome.found,
                "applies": rules_engine.applicability(rule, category, taxonomy.tag_glossary),
                "evidence": sorted({evidence_ids[f] for f in outcome.fields if f in evidence_ids}),
            })
        for ev in evidence:
            ev["photo"] = _photo_of(ev["box"][1] + ev["box"][3] / 2, spans)
        if consumer:
            for row in rule_rows:
                if row["id"] == "LM-11":
                    row["reason"] = "Physical numeral height is measured by inspectors with a scale marker; it is not part of a consumer check."

        # ---- Gemini: second look at failed / uncertain rules, plus mode-specific analysis (parallel calls)
        gemini_audit: dict | None = None
        extra_analysis: dict | None = None
        if gemini and gemini.enabled and time_left() > 25:
            validators = {r.id: r.validator for r in selected}
            candidates = [{"id": r["id"], "title": r["title"], "reference": r["reference"], "engine_status": r["status"],
                           "engine_reason": r["reason"], "expected": r["expected"], "found": r["found"]}
                          for r in rule_rows if r["status"] in ("FAIL", "REVIEW") and validators.get(r["id"]) in ai_review.OVERRIDABLE]
            rule_ids = {r["id"] for r in rule_rows}
            brief = [{"id": r["id"], "title": r["title"], "status": r["status"], "reason": (r["reason"] or "")[:160],
                      "found": r["found"]} for r in rule_rows if r["status"] != "NOT_APPLICABLE"]
            budget = max(10.0, min(75.0, time_left() - 12.0))
            with timer.stage("ai_verify", "Gemini re-verification & analysis") as st:
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    f_verify = pool.submit(gemini.verify_rules, photo_jpegs, ocr_text, candidates, labels_list, mode, budget) if candidates else None
                    if mode == "consumer":
                        raw_extra = early_intelligence if early_intelligence is not None else {"available": False, "reason": "AI analysis was skipped."}
                    else:
                        raw_extra = gemini.compliance_review(photo_jpegs, ocr_text, brief, ai, authority, labels_list, mode, budget)
                    verify_result = f_verify.result() if f_verify else {"available": True, "rules": []}
                gemini_audit = ai_review.reverify(rule_rows, validators, verify_result, ocr_text, mode)
                gemini_audit["available"] = bool(verify_result.get("available"))
                extra_analysis = (ai_review.validate_intelligence(raw_extra, ocr_text) if mode == "consumer"
                                  else ai_review.validate_review(raw_extra, ocr_text, rule_ids))
                if not verify_result.get("available") and not raw_extra.get("available"):
                    st["status"] = "skipped"
        elif mode == "consumer" and early_intelligence is not None:
            extra_analysis = ai_review.validate_intelligence(early_intelligence, ocr_text)

        if gemini_audit and gemini_audit.get("confirmed_by_gemini"):
            # Evidence outlines follow the final status of the rules they support
            by_id = {r["id"]: r["status"] for r in rule_rows}
            for ev in evidence:
                sts = [by_id.get(rid) for rid in ev.get("rule_ids", []) if by_id.get(rid)]
                if sts:
                    ev["status"] = max(sts, key=lambda x: STATUS_RANK.get(x, 0))
            annotated = evidence_mod.annotate(processed, evidence)

    statuses = [r["status"] for r in rule_rows]
    counts = {s: statuses.count(s) for s in ("PASS", "FAIL", "REVIEW", "NOT_APPLICABLE")}
    applicable = counts["PASS"] + counts["FAIL"] + counts["REVIEW"]
    overall = rules_engine.verdict(statuses)
    score = round(100.0 * counts["PASS"] / applicable, 1) if applicable else 0.0
    engine_verdict, verdict_basis = overall, "rules"
    if context["mode"] != "consumer" and applicable and score >= settings.pass_score_threshold and overall != "PASS":
        # Inspector / organisation checks: a compliance score at or above the threshold is a pass.
        # Individual rule outcomes are untouched, so every failed rule is still listed in the report.
        overall, verdict_basis = "PASS", "score_threshold"

    declarations = {k: v for k, v in decl.items() if not k.startswith("_")}
    warnings = image.warnings + quality["warnings"]
    if not calibration.get("detected") and not consumer:
        warnings.append({"code": "NO_CALIBRATION", "severity": "info",
                         "message": "No ArUco marker in frame - numeral height could not be measured."})
    elif calibration.get("warning"):
        warnings.append({"code": "CALIBRATION_ANGLE", "severity": "low", "message": calibration["warning"]})
    if authority and not in_scope:
        warnings.append({"code": "AUTHORITY_MISMATCH", "severity": "high",
                         "message": f"This product ({category.name}) is not regulated by {authority.upper()}; "
                                    f"the result only covers the common label declarations."})
    if not ai.get("available"):
        warnings.append({"code": "AI_UNAVAILABLE", "severity": "info", "message": ai.get("reason", "Gemini unavailable")})

    return {
        "schema": SCHEMA,
        "report_id": report_id,
        "analyzer_version": ANALYZER_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "duration_ms": int((time.perf_counter() - started) * 1000),
        "input": image.describe(),
        "mode": context["mode"],
        "photos": photo_info,
        "context": context,
        "classification": {
            "category": category.summary(),
            "group_name": taxonomy.groups.get(category.group, category.group),
            "confidence": classification["confidence"],
            "source": classification["source"],
            "evidence": classification["evidence"],
            "alternatives": classification["alternatives"],
            "package_type": classification["package_type"],
            "laws": list(category.laws),
            "tags": sorted(category.tags),
            "quantity": dict(zip(("dims", "ref"), category.dims_for(" ".join([context.get("product_name") or "", ocr_text[:600]])))),
            "standards": category.standards or None,
            "gate": gate,
            "taxonomy_version": taxonomy.version,
            "rules_applied": len(selected),
            "rules_in_catalog": len(rules),
            "not_relevant": [{"id": r.id, "title": r.title, "section": r.section} for r in skipped],
        },
        "summary": {
            "verdict": overall,
            "passed": counts["PASS"], "failed": counts["FAIL"], "review": counts["REVIEW"],
            "not_applicable": counts["NOT_APPLICABLE"],
            "score": score,
            "engine_verdict": engine_verdict,
            "verdict_basis": verdict_basis,
            "pass_threshold": settings.pass_score_threshold,
            "critical_failures": [r["id"] for r in rule_rows if r["status"] == "FAIL" and r["severity"] == "Critical"],
            "headline": (f"Passed with a compliance score of {score:g}% (pass mark {settings.pass_score_threshold:g}%); "
                         f"{counts['FAIL'] + counts['REVIEW']} rule(s) still need attention"
                         if verdict_basis == "score_threshold" else _headline(overall, rule_rows, category.name)),
            "category": category.name,
            "sections": _sections(rule_rows),
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
        "gemini_audit": gemini_audit,
        ("intelligence" if context["mode"] == "consumer" else "compliance_review"): extra_analysis,
        "warnings": warnings,
        "pipeline": timer.stages,
    }


def _limit(img: np.ndarray, long_edge: int) -> np.ndarray:
    h, w = img.shape[:2]
    s = long_edge / max(h, w)
    return cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else img


def _sections(rows: list[dict]) -> list[dict]:
    out: dict[str, dict] = {}
    for r in rows:
        s = out.setdefault(r["section"], {"section": r["section"], "PASS": 0, "FAIL": 0, "REVIEW": 0, "NOT_APPLICABLE": 0})
        s[r["status"]] += 1
    return [{"section": s["section"], "passed": s["PASS"], "failed": s["FAIL"], "review": s["REVIEW"], "not_applicable": s["NOT_APPLICABLE"]}
            for s in out.values()]


def _headline(verdict: str, rows: list[dict], category: str = "") -> str:
    fails = [r["title"] for r in rows if r["status"] == "FAIL"]
    reviews = [r["title"] for r in rows if r["status"] == "REVIEW"]
    if verdict == "FAIL":
        return f"Non-compliant: {fails[0]}" + (f" and {len(fails) - 1} more" if len(fails) > 1 else "")
    if verdict == "REVIEW":
        return f"Needs review: {reviews[0]}" + (f" and {len(reviews) - 1} more" if len(reviews) > 1 else "")
    return f"All {len([r for r in rows if r['status'] == 'PASS'])} applicable rules verified" + (f" for {category.lower()}" if category else "")
