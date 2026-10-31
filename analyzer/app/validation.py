"""Upload validation: size limits, MIME sniffing by magic bytes and a full decode check.

The client-declared content type and file extension are never trusted on their own. The real type is
read from the file signature, then Pillow verifies the structure before any pixel is decoded.
"""

from __future__ import annotations

import hashlib
import io
import os
import re
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

ALLOWED_TYPES: dict[str, tuple[str, ...]] = {
    "image/jpeg": (".jpg", ".jpeg", ".jfif"),
    "image/png": (".png",),
    "image/webp": (".webp",),
}

# Signatures of common formats we reject, so the error message can say what was actually sent
_KNOWN_REJECTED = (
    (b"GIF87a", "GIF"), (b"GIF89a", "GIF"), (b"%PDF", "PDF"), (b"BM", "BMP"),
    (b"II*\x00", "TIFF"), (b"MM\x00*", "TIFF"), (b"PK\x03\x04", "ZIP archive"),
)

MIN_SIDE_PX = 320
MAX_PIXELS = 40_000_000  # decompression-bomb guard (~40 MP)


class ImageValidationError(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


@dataclass
class ValidatedImage:
    bgr: np.ndarray
    mime: str
    width: int
    height: int
    size_bytes: int
    sha256: str
    filename: str
    exif_rotated: bool
    warnings: list[dict] = field(default_factory=list)

    def describe(self) -> dict:
        return {
            "filename": self.filename,
            "mime": self.mime,
            "bytes": self.size_bytes,
            "width": self.width,
            "height": self.height,
            "sha256": self.sha256,
            "exif_rotated": self.exif_rotated,
        }


def sniff_mime(data: bytes) -> str | None:
    """Detects the real image type from its first bytes."""
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _rejected_kind(data: bytes) -> str | None:
    for signature, name in _KNOWN_REJECTED:
        if data.startswith(signature):
            return name
    if len(data) >= 12 and data[4:8] == b"ftyp":
        brand = data[8:12]
        if brand in (b"heic", b"heix", b"hevc", b"heif", b"mif1", b"msf1"):
            return "HEIC/HEIF"
        if brand in (b"avif", b"avis"):
            return "AVIF"
        return "video"
    return None


def safe_filename(name: str | None) -> str:
    base = os.path.basename((name or "").replace("\\", "/"))
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", base).strip("._")
    return base[:80] or "upload"


def validate_image(data: bytes, filename: str | None, declared_mime: str | None, max_bytes: int) -> ValidatedImage:
    warnings: list[dict] = []

    if not data:
        raise ImageValidationError("empty_file", "The uploaded file is empty.")
    if len(data) > max_bytes:
        raise ImageValidationError(
            "too_large", f"Image is {len(data) / 1048576:.1f} MB; the limit is {max_bytes // 1048576} MB.", 413)

    mime = sniff_mime(data)
    if mime is None:
        kind = _rejected_kind(data)
        raise ImageValidationError(
            "unsupported_type",
            f"{kind} files are not accepted. Upload a JPG, JPEG, PNG or WEBP image." if kind
            else "This file is not a supported image. Upload a JPG, JPEG, PNG or WEBP image.",
            415,
        )

    declared = (declared_mime or "").split(";")[0].strip().lower()
    if declared and declared not in ("application/octet-stream", "binary/octet-stream"):
        if not declared.startswith("image/"):
            raise ImageValidationError(
                "mime_mismatch", f"The file was sent as '{declared}' but contains a {mime} image.", 415)
        if declared != mime and not (declared == "image/jpg" and mime == "image/jpeg"):
            warnings.append({"code": "MIME_MISMATCH", "severity": "info",
                             "message": f"Declared as {declared}; content is {mime}. The detected type was used."})

    clean_name = safe_filename(filename)
    ext = os.path.splitext(clean_name)[1].lower()
    if ext and ext not in ALLOWED_TYPES[mime]:
        warnings.append({"code": "EXTENSION_MISMATCH", "severity": "info",
                         "message": f"File extension '{ext}' does not match its {mime} content."})

    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    try:
        with Image.open(io.BytesIO(data)) as probe:
            probe.verify()  # structural integrity (truncated / corrupt files fail here)
        with Image.open(io.BytesIO(data)) as img:
            img.load()
            orientation = img.getexif().get(0x0112, 1) if hasattr(img, "getexif") else 1
            upright = ImageOps.exif_transpose(img)
            rgb = upright.convert("RGB")
    except Image.DecompressionBombError:
        raise ImageValidationError("too_many_pixels", "The image resolution is too large to process.", 413)
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as exc:
        raise ImageValidationError("corrupt_image", f"The image could not be decoded ({exc.__class__.__name__}).")

    width, height = rgb.size
    if min(width, height) < MIN_SIDE_PX:
        raise ImageValidationError(
            "too_small", f"Image is {width}x{height}px; at least {MIN_SIDE_PX}px on the short side is required.")

    bgr = np.ascontiguousarray(np.asarray(rgb)[:, :, ::-1])
    return ValidatedImage(
        bgr=bgr,
        mime=mime,
        width=width,
        height=height,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
        filename=clean_name,
        exif_rotated=orientation not in (None, 1),
        warnings=warnings,
    )
