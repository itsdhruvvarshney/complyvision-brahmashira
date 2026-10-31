import io

import numpy as np
import pytest
from PIL import Image

from app.validation import ImageValidationError, sniff_mime, validate_image

from .conftest import encode

LIMIT = 10 * 1024 * 1024


def _img(w=800, h=600):
    return np.full((h, w, 3), 200, np.uint8)


def test_accepts_jpeg_png_webp():
    for ext, mime in ((".jpg", "image/jpeg"), (".png", "image/png"), (".webp", "image/webp")):
        data = encode(_img(), ext)
        out = validate_image(data, f"pack{ext}", mime, LIMIT)
        assert out.mime == mime
        assert (out.width, out.height) == (800, 600)
        assert out.warnings == []


def test_detects_real_type_regardless_of_name():
    data = encode(_img(), ".png")
    out = validate_image(data, "photo.jpg", "image/jpeg", LIMIT)
    assert out.mime == "image/png"
    codes = {w["code"] for w in out.warnings}
    assert {"MIME_MISMATCH", "EXTENSION_MISMATCH"} <= codes


@pytest.mark.parametrize("payload,code", [
    (b"GIF89a" + b"\x00" * 64, "unsupported_type"),
    (b"%PDF-1.7\n" + b"\x00" * 64, "unsupported_type"),
    (b"\x00\x00\x00\x18ftypheic" + b"\x00" * 64, "unsupported_type"),
    (b"", "empty_file"),
    (b"\xff\xd8\xff\xe0" + b"garbage" * 20, "corrupt_image"),
])
def test_rejects_bad_files(payload, code):
    with pytest.raises(ImageValidationError) as err:
        validate_image(payload, "x", None, LIMIT)
    assert err.value.code == code


def test_heic_message_names_the_format():
    with pytest.raises(ImageValidationError) as err:
        validate_image(b"\x00\x00\x00\x18ftypheic" + b"\x00" * 64, "x.heic", "image/heic", LIMIT)
    assert "HEIC" in err.value.message


def test_rejects_non_image_declared_type():
    with pytest.raises(ImageValidationError) as err:
        validate_image(encode(_img()), "x.jpg", "application/pdf", LIMIT)
    assert err.value.code == "mime_mismatch"


def test_size_limits():
    with pytest.raises(ImageValidationError) as err:
        validate_image(encode(_img()), "x.jpg", None, 1000)
    assert err.value.code == "too_large" and err.value.status == 413
    with pytest.raises(ImageValidationError) as err:
        validate_image(encode(_img(200, 150)), "x.jpg", None, LIMIT)
    assert err.value.code == "too_small"


def test_exif_orientation_is_applied():
    pil = Image.fromarray(_img(900, 400))
    exif = pil.getexif()
    exif[0x0112] = 6  # rotate 90 CW on display
    buf = io.BytesIO()
    pil.save(buf, "JPEG", exif=exif)
    out = validate_image(buf.getvalue(), "x.jpg", "image/jpeg", LIMIT)
    assert out.exif_rotated and (out.width, out.height) == (400, 900)


def test_sniff():
    assert sniff_mime(b"\x89PNG\r\n\x1a\n....") == "image/png"
    assert sniff_mime(b"RIFF\x00\x00\x00\x00WEBPVP8 ") == "image/webp"
    assert sniff_mime(b"hello") is None
