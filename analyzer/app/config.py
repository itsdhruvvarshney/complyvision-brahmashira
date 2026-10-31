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
    request_timeout_s: float = field(default_factory=lambda: _float("REQUEST_TIMEOUT_S", 120.0))

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

    # Gemini (pool of keys, rotated round-robin with cool-down on quota / server errors)
    gemini_keys: list[str] = field(default_factory=lambda: _list("GEMINI_API_KEYS"))
    gemini_model: str = field(default_factory=lambda: os.getenv("GEMINI_MODEL", "gemini-2.5-flash"))
    gemini_timeout_s: float = field(default_factory=lambda: _float("GEMINI_TIMEOUT_S", 30.0))
    gemini_enabled: bool = field(default_factory=lambda: _bool("GEMINI_ENABLED", True))

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024


settings = Settings()
