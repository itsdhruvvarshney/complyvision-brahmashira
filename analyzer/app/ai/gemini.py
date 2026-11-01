"""Gemini enrichment with a pool of API keys.

Keys are used round-robin. A key that hits its quota (429) cools down for a minute, an invalid key
(400/401/403) is parked for an hour, and server errors (5xx) cool down briefly; the request is retried
on the next healthy key. Keys are never logged or returned - reports only carry the pool slot number.
"""

from __future__ import annotations

import base64
import json
import logging
import re
import threading
import time

import httpx

log = logging.getLogger("analyzer.gemini")

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class KeyPool:
    def __init__(self, keys: list[str]):
        self._keys = list(dict.fromkeys(k for k in keys if k))
        self._cool_until = [0.0] * len(self._keys)
        self._next = 0
        self._lock = threading.Lock()

    def __len__(self) -> int:
        return len(self._keys)

    def acquire(self) -> tuple[int, str] | None:
        with self._lock:
            now = time.monotonic()
            for step in range(len(self._keys)):
                i = (self._next + step) % len(self._keys)
                if self._cool_until[i] <= now:
                    self._next = (i + 1) % len(self._keys)
                    return i, self._keys[i]
            return None

    def cool_down(self, slot: int, seconds: float) -> None:
        with self._lock:
            self._cool_until[slot] = time.monotonic() + seconds

    def status(self) -> dict:
        now = time.monotonic()
        with self._lock:
            return {"keys": len(self._keys), "available": sum(1 for t in self._cool_until if t <= now)}


PROMPT = """You are assisting a Legal Metrology inspector in India who is verifying a packaged commodity
against the Legal Metrology (Packaged Commodities) Rules, 2011.

You are given a photo of the package label (already straightened and contrast-corrected), the OCR
text read from it, and the declarations a deterministic parser extracted. Read the label yourself
and return ONLY a JSON object with these keys:

{
  "product_name": string|null, "brand": string|null, "generic_name": string|null,
  "category": string|null, "is_food": boolean, "is_imported": boolean,
  "country_of_origin": string|null, "net_quantity": string|null, "mrp": string|null,
  "unit_sale_price": string|null, "mfg_date": string|null, "best_before": string|null,
  "batch_no": string|null, "manufacturer": string|null, "customer_care": string|null,
  "declarations_seen": {"manufacturer": bool, "country_of_origin": bool, "generic_name": bool,
     "net_quantity": bool, "mfg_date": bool, "mrp": bool, "tax_inclusive": bool,
     "unit_sale_price": bool, "customer_care": bool, "best_before": bool, "batch_no": bool,
     "fssai_licence": bool, "ingredients": bool, "nutrition": bool},
  "veg_mark": "veg"|"non_veg"|null, "tobacco_warning_coverage_pct": number|null,
  "ingredients_summary": string|null, "allergens": [string], "nutrition_highlights": [string],
  "claims": [string], "certifications": [string], "storage_instructions": string|null,
  "consumer_advisory": [string], "regulatory_observations": [string],
  "ocr_corrections": [{"field": string, "ocr": string, "label": string}],
  "suspected_issues": [string], "summary": string, "language": string, "confidence": number
}

Rules: "declarations_seen.X" is true only if you can actually see that declaration on the label.
Do not guess values you cannot read - use null. Keep every list item under 160 characters.
"category" is the plain commodity type (e.g. "edible oil", "toothpaste", "LED bulb", "cigarettes",
"paracetamol tablets"). "veg_mark" is set only if you can see the green (vegetarian) or brown
(non-vegetarian) square-and-dot symbol. "tobacco_warning_coverage_pct" is your estimate of the share
of the visible front panel covered by the pictorial health warning, for tobacco packs only.
"regulatory_observations" should point out Legal Metrology concerns you notice (missing or unclear
declarations, non-standard unit symbols, price formatting); "consumer_advisory" is plain-language
guidance for the shopper. "summary" is two sentences at most.
"""


class GeminiClient:
    def __init__(self, keys: list[str], model: str, timeout_s: float, enabled: bool = True):
        self.pool = KeyPool(keys)
        self.model = model
        self.timeout_s = timeout_s
        self.enabled = enabled and len(self.pool) > 0

    def status(self) -> dict:
        return {"enabled": self.enabled, "model": self.model, **self.pool.status()}

    def enrich(self, image_jpeg: bytes, ocr_text: str, declarations: dict, context: dict) -> dict:
        if not self.enabled:
            return {"available": False, "reason": "Gemini is not configured (set GEMINI_API_KEYS)."}

        parser_view = {k: v for k, v in declarations.items() if not k.startswith("_")}
        body = {
            "contents": [{
                "role": "user",
                "parts": [
                    {"text": PROMPT},
                    {"text": "CASE CONTEXT:\n" + json.dumps(context, ensure_ascii=False)},
                    {"text": "OCR TEXT:\n" + ocr_text[:6000]},
                    {"text": "PARSER DECLARATIONS:\n" + json.dumps(parser_view, ensure_ascii=False, default=str)[:6000]},
                    {"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(image_jpeg).decode()}},
                ],
            }],
            "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json", "maxOutputTokens": 2048},
        }

        errors: list[str] = []
        attempts = min(len(self.pool), 4)
        for _ in range(attempts):
            lease = self.pool.acquire()
            if lease is None:
                errors.append("all keys cooling down")
                break
            slot, key = lease
            started = time.monotonic()
            try:
                resp = httpx.post(
                    ENDPOINT.format(model=self.model),
                    headers={"x-goog-api-key": key, "Content-Type": "application/json"},
                    json=body,
                    timeout=self.timeout_s,
                )
            except httpx.HTTPError as exc:
                self.pool.cool_down(slot, 10)
                errors.append(f"slot {slot}: {exc.__class__.__name__}")
                continue

            if resp.status_code == 200:
                try:
                    data = _parse(resp.json())
                except ValueError as exc:
                    errors.append(f"slot {slot}: unreadable response ({exc})")
                    continue
                data.update({"available": True, "model": self.model, "key_slot": slot,
                             "latency_ms": int((time.monotonic() - started) * 1000)})
                return data

            if resp.status_code == 429:
                self.pool.cool_down(slot, 60)
            elif resp.status_code in (400, 401, 403):
                self.pool.cool_down(slot, 3600)
            else:
                self.pool.cool_down(slot, 15)
            errors.append(f"slot {slot}: HTTP {resp.status_code}")
            log.warning("Gemini call failed on key slot %s: HTTP %s", slot, resp.status_code)

        return {"available": False, "reason": "Gemini enrichment unavailable: " + "; ".join(errors)}


def _parse(payload: dict) -> dict:
    candidates = payload.get("candidates") or []
    if not candidates:
        raise ValueError("no candidates")
    parts = (candidates[0].get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts).strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("not an object")
    # Defensive normalisation of list fields
    for key in ("allergens", "nutrition_highlights", "claims", "certifications", "consumer_advisory",
                "regulatory_observations", "suspected_issues", "ocr_corrections"):
        if not isinstance(data.get(key), list):
            data[key] = []
    if not isinstance(data.get("declarations_seen"), dict):
        data["declarations_seen"] = {}
    if data.get("veg_mark") not in ("veg", "non_veg"):
        data["veg_mark"] = None
    return data
