"""Shared PaddleOCR engine.

One engine instance is created per process (model loading takes seconds) and reused by every request.
Paddle predictors are not thread-safe, so calls are serialised with a lock while the rest of the
pipeline (OpenCV, rules, Gemini) runs in parallel across the worker pool.

Supports both PaddleOCR 2.x (`ocr.ocr(img, cls=True)`) and 3.x (`ocr.predict(img)`).
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass, field

import numpy as np

log = logging.getLogger("analyzer.ocr")


class OcrUnavailable(RuntimeError):
    pass


@dataclass
class OcrLine:
    text: str
    confidence: float
    box: tuple[int, int, int, int]  # x, y, w, h in processed-image pixels
    polygon: list[list[int]] = field(default_factory=list)
    verified: bool = False
    original_text: str | None = None
    original_confidence: float | None = None

    @property
    def cx(self) -> float:
        return self.box[0] + self.box[2] / 2

    @property
    def cy(self) -> float:
        return self.box[1] + self.box[3] / 2

    def to_json(self) -> dict:
        out = {"text": self.text, "confidence": round(self.confidence, 4), "box": list(self.box)}
        if self.verified:
            out["verified"] = True
            out["original_text"] = self.original_text
            out["original_confidence"] = round(self.original_confidence or 0.0, 4)
        return out


def _box_from_poly(poly) -> tuple[tuple[int, int, int, int], list[list[int]]]:
    pts = np.asarray(poly, dtype=np.float32).reshape(-1, 2)
    x0, y0 = pts.min(axis=0)
    x1, y1 = pts.max(axis=0)
    return (int(x0), int(y0), int(max(1, x1 - x0)), int(max(1, y1 - y0))), pts.round().astype(int).tolist()


class PaddleEngine:
    """Thin adapter over PaddleOCR that returns [OcrLine] sorted in reading order."""

    def __init__(self, lang: str = "en", mkldnn: bool = True, det_limit: int = 1600,
                 det_model: str | None = None, rec_model: str | None = None, verify_model: str | None = None):
        try:
            import paddleocr  # noqa: F401
            from paddleocr import PaddleOCR
        except ImportError as exc:  # pragma: no cover - depends on deployment
            raise OcrUnavailable(
                "PaddleOCR is not installed. Install the analyzer requirements (paddlepaddle + paddleocr).") from exc

        self.version = getattr(paddleocr, "__version__", "unknown")
        self.major = int(str(self.version).split(".")[0]) if str(self.version)[:1].isdigit() else 2
        self.lang = lang
        self._lock = threading.Lock()
        self._recognizer = None
        self.mkldnn = mkldnn
        self.rec_model = rec_model
        self.verify_model = verify_model
        self.models = {"detector": det_model or "default", "recogniser": rec_model or "default",
                       "verifier": verify_model or rec_model or "default"}

        log.info("Loading PaddleOCR %s (lang=%s)", self.version, lang)
        if self.major >= 3:
            self._ocr = PaddleOCR(
                lang=lang,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,  # OpenCV already rectified the image
                use_textline_orientation=True,
                enable_mkldnn=mkldnn,
                text_det_limit_side_len=det_limit,
                text_det_limit_type="max",
                **({"text_detection_model_name": det_model} if det_model else {}),
                **({"text_recognition_model_name": rec_model} if rec_model else {}),
            )
        else:  # pragma: no cover - 2.x API
            self._ocr = PaddleOCR(lang=lang, use_angle_cls=True, show_log=False)

    @property
    def name(self) -> str:
        return f"PaddleOCR {self.version}"

    def read(self, img: np.ndarray) -> list[OcrLine]:
        with self._lock:
            if self.major >= 3:
                results = self._ocr.predict(img)
            else:  # pragma: no cover
                results = self._ocr.ocr(img, cls=True)
        lines: list[OcrLine] = []
        if self.major >= 3:
            for res in results or []:
                data = res.json.get("res", res) if hasattr(res, "json") else res
                texts = data.get("rec_texts", []) or []
                scores = data.get("rec_scores", []) or []
                polys = data.get("rec_polys", None)
                if polys is None or len(polys) == 0:
                    polys = data.get("dt_polys", []) or []
                for text, score, poly in zip(texts, scores, polys):
                    if not str(text).strip():
                        continue
                    box, pts = _box_from_poly(poly)
                    lines.append(OcrLine(str(text).strip(), float(score), box, pts))
        else:  # pragma: no cover
            for page in results or []:
                for poly, (text, score) in page or []:
                    if not str(text).strip():
                        continue
                    box, pts = _box_from_poly(poly)
                    lines.append(OcrLine(str(text).strip(), float(score), box, pts))
        return sort_reading_order(lines)

    @property
    def verifier_is_stronger(self) -> bool:
        """True when verification re-reads with a more accurate model than the main pass."""
        return bool(self.verify_model) and self.verify_model != self.rec_model

    def recognize_batch(self, crops: list[np.ndarray]) -> list[tuple[str, float]]:
        """Recognition only (no detection) on tight crops, in one batched call - used for verification."""
        if not crops:
            return []
        with self._lock:
            if self.major >= 3:
                if self._recognizer is None:
                    from paddleocr import TextRecognition

                    model = self.verify_model or self.rec_model
                    self._recognizer = TextRecognition(enable_mkldnn=self.mkldnn, **({"model_name": model} if model else {}))
                out = []
                for res in self._recognizer.predict(crops, batch_size=8) or []:
                    data = res.json.get("res", res) if hasattr(res, "json") else res
                    out.append((str(data.get("rec_text", "")).strip(), float(data.get("rec_score", 0.0))))
                return out + [("", 0.0)] * (len(crops) - len(out))
            results = []  # pragma: no cover - 2.x API
            for crop in crops:
                res = self._ocr.ocr(crop, det=False, cls=True)
                results.append((str(res[0][0][0]).strip(), float(res[0][0][1])) if res and res[0] else ("", 0.0))
            return results

    def recognize(self, crop: np.ndarray) -> tuple[str, float]:
        return self.recognize_batch([crop])[0]


def sort_reading_order(lines: list[OcrLine]) -> list[OcrLine]:
    """Top-to-bottom, then left-to-right, grouping boxes whose vertical centres are close."""
    if not lines:
        return lines
    median_h = float(np.median([ln.box[3] for ln in lines]))
    ordered = sorted(lines, key=lambda ln: ln.cy)
    rows: list[list[OcrLine]] = []
    for ln in ordered:
        if rows and abs(rows[-1][-1].cy - ln.cy) <= median_h * 0.55:
            rows[-1].append(ln)
        else:
            rows.append([ln])
    return [ln for row in rows for ln in sorted(row, key=lambda x: x.box[0])]


_engine = None
_engine_error: str | None = None
_engine_lock = threading.Lock()


def get_engine(lang: str = "en", mkldnn: bool = True, det_limit: int = 1600,
               det_model: str | None = None, rec_model: str | None = None, verify_model: str | None = None):
    """Process-wide shared engine (created on first use or at start-up warm-up)."""
    global _engine, _engine_error
    if _engine is not None:
        return _engine
    with _engine_lock:
        if _engine is None:
            try:
                _engine = PaddleEngine(lang, mkldnn, det_limit, det_model, rec_model, verify_model)
                _engine_error = None
            except OcrUnavailable as exc:
                _engine_error = str(exc)
                raise
            except Exception as exc:  # pragma: no cover - model download / runtime failures
                _engine_error = f"PaddleOCR failed to start: {exc}"
                raise OcrUnavailable(_engine_error) from exc
    return _engine


def engine_status() -> dict:
    if _engine is not None:
        return {"ready": True, "engine": _engine.name}
    return {"ready": False, "engine": "PaddleOCR", "error": _engine_error}
