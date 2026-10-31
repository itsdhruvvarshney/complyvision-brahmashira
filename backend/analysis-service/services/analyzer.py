"""HTTP-independent orchestration of the existing package-analysis pipeline."""

from __future__ import annotations

import threading
import tempfile
from pathlib import Path
from typing import Any, Callable

from batch_measure import process_image
from extract_fields import extract_fields
from reporting.report import build_package_report
from services.declaration_extractor import add_enhanced_report_fields, enhance_extracted_fields
from services.evidence import build_evidence_images, scrub_local_paths
from services.mrp_extractor import correct_mrp
from services.ocr_ensemble import run_ocr_ensemble


class PackageAnalysisError(RuntimeError):
    """Raised when a technical failure prevents report generation."""


CORE_FIELDS = (
    "manufacturer",
    "product",
    "net_quantity",
    "manufacture_date",
    "mrp",
    "consumer_care",
)


def _default_ocr_factory() -> Any:
    from paddleocr import PaddleOCR

    # Preserve PaddleOCR's orientation handling because phone-captured packages
    # are frequently sideways. Perspective unwarping remains disabled.
    return PaddleOCR(
        lang="en",
        use_doc_orientation_classify=True,
        use_doc_unwarping=False,
        use_textline_orientation=True,
        enable_mkldnn=False,
    )


def _confidence(value: Any) -> float:
    if not isinstance(value, dict):
        return -1.0
    raw = value.get("confidence", value.get("extraction_confidence", value.get("ocr_confidence")))
    return float(raw) if isinstance(raw, (int, float)) else -1.0


def _has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, dict):
        if value.get("present") is False:
            return False
        normalized = value.get("normalized_value", value.get("value", value.get("raw")))
        if isinstance(normalized, dict):
            return any(item not in (None, "", [], {}) for item in normalized.values())
        return normalized not in (None, "", [], {})
    return value not in (None, "", [], {})


def _needs_ocr_verification(fields: dict[str, Any]) -> bool:
    """Run the expensive contrast OCR only when primary extraction needs help."""
    missing_or_weak = 0
    for name in CORE_FIELDS:
        value = fields.get(name)
        consumer_phone_missing = (
            name == "consumer_care"
            and isinstance(value, dict)
            and not value.get("phone")
        )
        if not _has_value(value) or _confidence(value) < 0.82 or consumer_phone_missing:
            missing_or_weak += 1
    return missing_or_weak > 0


def _merge_consumer_care(
    current: dict[str, Any], candidate: dict[str, Any],
) -> dict[str, Any]:
    """Fill missing contact details without replacing prior OCR evidence."""
    merged = dict(current)
    added = False
    for key in ("phone", "email"):
        if not merged.get(key) and candidate.get(key):
            merged[key] = candidate[key]
            added = True
    if not added:
        return merged

    source_lines = list(current.get("source_lines") or [])
    source_lines.extend(candidate.get("source_lines") or [])
    if source_lines:
        merged["source_lines"] = list(dict.fromkeys(source_lines))

    confidences = [
        value for value in (_confidence(current), _confidence(candidate))
        if value >= 0
    ]
    if confidences:
        merged["confidence"] = round(min(confidences), 3)
    return merged


def _merge_field_candidates(primary: dict[str, Any], ensemble: dict[str, Any]) -> dict[str, Any]:
    """Keep the strongest evidence per field without discarding non-OCR fields."""
    merged = dict(primary)
    for name, candidate in ensemble.items():
        if name == "ocr_evidence" or not isinstance(candidate, dict):
            continue
        current = merged.get(name)
        if name == "consumer_care" and isinstance(current, dict):
            merged[name] = _merge_consumer_care(current, candidate)
            continue
        if current and (not isinstance(current, dict) or _confidence(current) >= 0.82):
            continue
        if not isinstance(current, dict) or _confidence(candidate) > _confidence(current):
            merged[name] = candidate
    if ensemble.get("ocr_evidence"):
        merged["ocr_evidence"] = ensemble["ocr_evidence"]
    return merged


class PackageAnalyzer:
    """Reuse one OCR model and the existing single-image processing function."""

    def __init__(
        self,
        *,
        ocr_factory: Callable[[], Any] = _default_ocr_factory,
        image_processor: Callable[..., dict[str, Any]] = process_image,
        report_builder: Callable[..., dict[str, Any]] = build_package_report,
        evidence_builder: Callable[..., list[dict[str, Any]]] = build_evidence_images,
    ) -> None:
        self._ocr_factory = ocr_factory
        self._image_processor = image_processor
        self._report_builder = report_builder
        self._evidence_builder = evidence_builder
        self._ocr: Any | None = None
        self._initialization_lock = threading.Lock()
        self._inference_lock = threading.Lock()

    @property
    def ocr_initialized(self) -> bool:
        return self._ocr is not None

    def warm_up(self) -> None:
        """Load OCR models before the first user-facing inspection request."""
        self._get_ocr()

    def _get_ocr(self) -> Any:
        if self._ocr is None:
            with self._initialization_lock:
                if self._ocr is None:
                    self._ocr = self._ocr_factory()
        return self._ocr

    def analyze_package(
        self, image_path: str | Path, *, display_filename: str = "uploaded_image.jpg",
    ) -> dict[str, Any]:
        """Return the canonical report; request-local artifacts remain private."""
        path = Path(image_path)
        if not path.is_file():
            raise PackageAnalysisError("Input image is unavailable")
        try:
            with tempfile.TemporaryDirectory(prefix="sih26034_evidence_") as directory:
                evidence_root = Path(directory)
                glyph_debug_path = evidence_root / "glyph.jpg"
                with self._inference_lock:
                    ocr = self._get_ocr()
                    batch_result = self._image_processor(
                        path,
                        ocr,
                        debug_path=glyph_debug_path,
                    )
                    analysis_path = Path(batch_result.get("analysis_image_path") or path)

                    primary_fields = enhance_extracted_fields(
                        batch_result.get("extracted_fields") or {}
                    )
                    primary_fields = correct_mrp(primary_fields)
                    batch_result["extracted_fields"] = primary_fields

                    if _needs_ocr_verification(primary_fields):
                        primary_items = primary_fields.get("ocr_evidence") or []
                        ensemble_items, ensemble_meta = run_ocr_ensemble(
                            ocr,
                            str(analysis_path),
                            primary_items=primary_items,
                        )
                    else:
                        ensemble_items = []
                        ensemble_meta = {
                            "passes": 1,
                            "raw_items": len(primary_fields.get("ocr_evidence") or []),
                            "consensus_items": 0,
                            "errors": [],
                            "skipped": True,
                            "reason": "primary_declarations_sufficient",
                        }

                if batch_result.get("failure_stage") == "unexpected_exception":
                    raise PackageAnalysisError("The analysis pipeline failed unexpectedly")
                evidence_images = self._evidence_builder(analysis_path, batch_result, evidence_root)
            safe_result = scrub_local_paths(batch_result)
            safe_result["evidence_images"] = evidence_images
            safe_result["image"] = Path(display_filename).name
            safe_result.setdefault("ocr", {})["ensemble"] = ensemble_meta

            if ensemble_items:
                ensemble_fields = enhance_extracted_fields(extract_fields(ensemble_items))
                ensemble_fields = correct_mrp(ensemble_fields)
                safe_result["extracted_fields"] = _merge_field_candidates(
                    safe_result.get("extracted_fields") or {}, ensemble_fields
                )

            safe_result["extracted_fields"] = enhance_extracted_fields(
                safe_result.get("extracted_fields") or {}
            )
            safe_result["extracted_fields"] = correct_mrp(safe_result["extracted_fields"])

            extracted_fields = dict(safe_result.get("extracted_fields") or {})
            extracted_fields["font_height_measurement"] = safe_result.get("glyph_measurement")
            if safe_result.get("principal_display_panel_area_cm2") is not None:
                extracted_fields["principal_display_panel_area_cm2"] = safe_result[
                    "principal_display_panel_area_cm2"
                ]
            if safe_result.get("package_surface_formed") is not None:
                extracted_fields["package_surface_formed"] = safe_result[
                    "package_surface_formed"
                ]
            safe_result["extracted_fields"] = extracted_fields

            report = self._report_builder(safe_result)
            report = add_enhanced_report_fields(report, safe_result["extracted_fields"])
            return report
        except PackageAnalysisError:
            raise
        except Exception as exc:
            raise PackageAnalysisError("The analysis pipeline could not produce a report") from exc
