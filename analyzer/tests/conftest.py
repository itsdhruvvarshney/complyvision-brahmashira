"""Test helpers: a synthetic package label and an OCR stand-in, so the whole pipeline (OpenCV,
calibration, extraction, rules, evidence) can be exercised without PaddleOCR installed."""

from __future__ import annotations

import os
import sys

import cv2
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.ocr.engine import OcrLine, sort_reading_order  # noqa: E402

LABEL_LINES = [
    ("PREMIUM BASMATI RICE", 1.6),
    ("Net Qty: 1 kg", 2.6),
    ("MRP Rs. 145.00 (Incl. of all taxes)", 1.1),
    ("Unit Sale Price Rs. 145 per kg", 1.0),
    ("Mfg Date: 08/2026  Best Before 12 months from mfg", 0.9),
    ("Batch No: RH2608-114", 0.9),
    ("Mfd by: Royal Harvest Foods Pvt Ltd, Plot 12, Industrial Area, Karnal 132001", 0.75),
    ("Customer Care: 1800 120 4455  care@royalharvest.in", 0.8),
]


def make_label(lines=LABEL_LINES, marker_px: int = 160, with_marker: bool = True) -> tuple[np.ndarray, list[str]]:
    canvas = np.full((1250, 1700, 3), 247, np.uint8)
    y = 90
    texts = []
    for text, scale in lines:
        thick = max(2, int(scale * 2.2))
        (tw, th), base = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
        cv2.putText(canvas, text, (70, y + th), cv2.FONT_HERSHEY_SIMPLEX, scale, (25, 25, 30), thick, cv2.LINE_AA)
        texts.append(text)
        y += th + base + 62
    if with_marker:
        dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
        marker = cv2.aruco.generateImageMarker(dictionary, 0, marker_px)
        pad = 24
        block = np.full((marker_px + 2 * pad, marker_px + 2 * pad), 255, np.uint8)
        block[pad:pad + marker_px, pad:pad + marker_px] = marker
        y0, x0 = canvas.shape[0] - block.shape[0] - 40, canvas.shape[1] - block.shape[1] - 40
        canvas[y0:y0 + block.shape[0], x0:x0 + block.shape[1]] = cv2.cvtColor(block, cv2.COLOR_GRAY2BGR)
    return canvas, texts


class FakeEngine:
    """Finds text-line blobs in whatever image it is given and assigns the known label texts in
    reading order - mimicking PaddleOCR's detection + recognition on the synthetic label."""

    name = "FakeOCR (tests)"

    def __init__(self, texts: list[str], confidence: float = 0.96):
        self.texts = texts
        self.confidence = confidence
        self.recognize_calls = 0

    def read(self, img: np.ndarray) -> list[OcrLine]:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        _, ink = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(25, img.shape[1] // 25), 5))
        merged = cv2.morphologyEx(ink, cv2.MORPH_CLOSE, kernel)
        contours, _ = cv2.findContours(merged, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        boxes = []
        for c in contours:
            x, y, w, h = cv2.boundingRect(c)
            if w / max(h, 1) > 2.5 and w > img.shape[1] * 0.1 and h > 8:
                boxes.append((x, y, w, h))
        boxes.sort(key=lambda b: b[1])
        lines = [OcrLine(t, self.confidence, b) for t, b in zip(self.texts, boxes)]
        return sort_reading_order(lines)

    def recognize(self, crop: np.ndarray) -> tuple[str, float]:
        self.recognize_calls += 1
        return "", 0.0


@pytest.fixture
def label():
    return make_label()


def encode(img: np.ndarray, ext: str = ".jpg") -> bytes:
    ok, buf = cv2.imencode(ext, img)
    assert ok
    return buf.tobytes()
