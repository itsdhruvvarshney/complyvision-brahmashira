"""Runtime configuration, read once from environment variables (or a local .env file)."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass, field

try:  # optional: python-dotenv makes local development easier
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # pragma: no cover
    pass

ANALYZER_VERSION = "1.0.0"


def _list(name: str, default: str = "") -> list[str]:
    raw = os.getenv(name, default)
    return [item.strip() for item in raw.replace("\n", ",").split(",") if item.strip()]


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Settings:
    # HTTP surface
    allowed_origins: list[str] = field(default_factory=lambda: _list(
        "ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000"))
    api_keys: frozenset[str] = field(default_factory=lambda: frozenset(_list("ANALYZER_API_KEYS")))
    max_upload_mb: int = field(default_factory=lambda: _int("MAX_UPLOAD_MB", 10))
    workers: int = field(default_factory=lambda: max(1, _int("ANALYZER_WORKERS", 2)))
    request_timeout_s: float = field(default_factory=lambda: _float("REQUEST_TIMEOUT_S", 300.0))

    # Request workspace
    workspace_root: str = field(default_factory=lambda: os.getenv(
        "WORKSPACE_ROOT", os.path.join(tempfile.gettempdir(), "complyvision-analyzer")))
    keep_workspace: bool = field(default_factory=lambda: _bool("KEEP_WORKSPACE", False))

    # Computer vision
    max_long_edge: int = field(default_factory=lambda: _int("MAX_LONG_EDGE", 2400))
    aruco_marker_mm: float = field(default_factory=lambda: _float("ARUCO_MARKER_MM", 20.0))
    require_calibration: bool = field(default_factory=lambda: _bool("REQUIRE_CALIBRATION", False))

    # OCR
    ocr_lang: str = field(default_factory=lambda: os.getenv("OCR_LANG", "en"))
    ocr_min_confidence: float = field(default_factory=lambda: _float("OCR_MIN_CONFIDENCE", 0.45))
    verify_below_confidence: float = field(default_factory=lambda: _float("OCR_VERIFY_BELOW", 0.90))
    warm_up_ocr: bool = field(default_factory=lambda: _bool("WARM_UP_OCR", True))
    # Paddle 3.x's oneDNN CPU backend fails on Windows ("ConvertPirAttribute2RuntimeAttribute"); off there by default
    ocr_mkldnn: bool = field(default_factory=lambda: _bool("OCR_MKLDNN", os.name != "nt"))
    # Longest side the text detector sees. Phone photos are 3000-4000 px; detecting at full size is
    # several times slower on CPU without improving label-sized text.
    ocr_det_limit: int = field(default_factory=lambda: _int("OCR_DET_LIMIT", 1600))
    # "fast": mobile detector + recognizer for the full label, then price / quantity / date lines are
    # re-read with the medium recognizer (about 4x faster on CPU). "accurate": medium models throughout.
    # Defaults to fast where Paddle's oneDNN acceleration is off (Windows).
    ocr_profile: str = field(default_factory=lambda: os.getenv("OCR_PROFILE", "accurate" if _bool("OCR_MKLDNN", os.name != "nt") else "fast"))
    # Explicit model names override the profile
    ocr_det_model: str | None = field(default_factory=lambda: os.getenv("OCR_DET_MODEL") or None)
    ocr_rec_model: str | None = field(default_factory=lambda: os.getenv("OCR_REC_MODEL") or None)
    ocr_verify_model: str | None = field(default_factory=lambda: os.getenv("OCR_VERIFY_MODEL") or None)
    # Re-read as orientations / native-resolution tiles when a mandatory declaration was not found
    ocr_rescue: bool = field(default_factory=lambda: _bool("OCR_RESCUE", True))
    # Most lines re-read per package (critical lines first)
    ocr_verify_max: int = field(default_factory=lambda: _int("OCR_VERIFY_MAX", 10))

    @property
    def ocr_models(self) -> tuple[str | None, str | None, str | None]:
        """(detector, recogniser, verifier) model names; None = PaddleOCR's default."""
        if self.ocr_profile == "fast":
            det, rec, ver = "PP-OCRv5_mobile_det", "en_PP-OCRv5_mobile_rec", "PP-OCRv6_medium_rec"
        else:
            det, rec, ver = None, None, None
        return self.ocr_det_model or det, self.ocr_rec_model or rec, self.ocr_verify_model or ver

    # Inspector (Legal Metrology) and organisation (BIS / Pre-FSSAI) checks pass at or above this compliance score (%)
    pass_score_threshold: float = field(default_factory=lambda: _float("PASS_SCORE_THRESHOLD", 75.0))

    # Gemini (pool of keys, rotated round-robin with cool-down on quota / server errors)
    gemini_keys: list[str] = field(default_factory=lambda: _list("GEMINI_API_KEYS"))
    gemini_model: str = field(default_factory=lambda: os.getenv("GEMINI_MODEL", "gemini-3.5-flash,gemini-3-flash-preview"))
    gemini_timeout_s: float = field(default_factory=lambda: _float("GEMINI_TIMEOUT_S", 45.0))
    gemini_enabled: bool = field(default_factory=lambda: _bool("GEMINI_ENABLED", True))

    # Accessibility assistant (blind users). Two pools so the live camera loop never starves the conversation:
    # the "brain" keeps the whole conversation and decides what to say / do next, the "vision" pool reads
    # camera frames and captured photos. Unset = GEMINI_API_KEYS split in two (alternate keys).
    assist_brain_keys: list[str] = field(default_factory=lambda: _list("GEMINI_ASSIST_BRAIN_KEYS"))
    assist_vision_keys: list[str] = field(default_factory=lambda: _list("GEMINI_ASSIST_VISION_KEYS"))
    assist_model: str = field(default_factory=lambda: os.getenv("GEMINI_ASSIST_MODEL", "") or os.getenv(
        "GEMINI_MODEL", "gemini-3.5-flash,gemini-3-flash-preview"))
    # Reasoning depth per task ("" = model default). Lower is faster; the frame loop must answer in ~1-2 s.
    assist_frame_thinking: str = field(default_factory=lambda: os.getenv("GEMINI_ASSIST_FRAME_THINKING", "minimal"))
    assist_turn_thinking: str = field(default_factory=lambda: os.getenv("GEMINI_ASSIST_TURN_THINKING", "low"))

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024


settings = Settings()
