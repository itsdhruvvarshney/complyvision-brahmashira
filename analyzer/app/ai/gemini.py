"""Gemini client with a pool of API keys.

Key handling
  * A random healthy key is picked for every request (spreads load across the pool).
  * 429 (quota / rate limit): the key rests - a daily-quota message parks it for an hour, a per-minute limit
    for the server's retry delay (default 60 s) - and the request moves to another key.
  * 400 / 401 / 403 with an invalid, expired or disabled key: the key is REJECTED for the life of the process.
  * 5xx / network errors: the key rests briefly and the model is tried on the next key.
  * 404 / 503 for a model: the next model in GEMINI_MODEL(S) is used.
Keys are never logged or returned; results only carry the pool slot number.

The client exposes one generic JSON call (`generate`) and the task helpers built on it: label enrichment,
rule re-verification, consumer product intelligence, formal compliance review, Q&A and product comparison.
Prompts live in `prompts.py`; this module only talks to the API.
"""

from __future__ import annotations

import base64
import json
import logging
import random
import re
import threading
import time

import httpx

from . import prompts

log = logging.getLogger("analyzer.gemini")

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
_REJECT = float("inf")


class KeyPool:
    def __init__(self, keys: list[str]):
        self._keys = list(dict.fromkeys(k for k in keys if k))
        self._cool_until = [0.0] * len(self._keys)
        self._reason = [""] * len(self._keys)
        self._lock = threading.Lock()

    def __len__(self) -> int:
        return len(self._keys)

    def acquire(self, exclude: set[int] | None = None) -> tuple[int, str] | None:
        """A random key that is neither resting, rejected nor already tried for this request."""
        with self._lock:
            now = time.monotonic()
            ready = [i for i in range(len(self._keys)) if self._cool_until[i] <= now and i not in (exclude or ())]
            if not ready:
                return None
            i = random.choice(ready)
            return i, self._keys[i]

    def cool_down(self, slot: int, seconds: float, reason: str = "") -> None:
        with self._lock:
            if self._cool_until[slot] == _REJECT:
                return  # a rejected key stays rejected
            self._cool_until[slot] = max(self._cool_until[slot], time.monotonic() + seconds)
            self._reason[slot] = reason

    def reject(self, slot: int, reason: str = "rejected") -> None:
        """The key is invalid, expired or disabled: never use it again until the service restarts."""
        with self._lock:
            self._cool_until[slot] = _REJECT
            self._reason[slot] = reason

    def status(self) -> dict:
        now = time.monotonic()
        with self._lock:
            rejected = sum(1 for t in self._cool_until if t == _REJECT)
            resting = sum(1 for t in self._cool_until if now < t < _REJECT)
            return {"keys": len(self._keys), "available": sum(1 for t in self._cool_until if t <= now),
                    "resting": resting, "rejected": rejected}


class GeminiClient:
    def __init__(self, keys: list[str], model: str, timeout_s: float, enabled: bool = True):
        self.pool = KeyPool(keys)
        # "gemini-3.5-flash,gemini-3-flash-preview": tried in order when a model is unavailable
        self.models = [m.strip() for m in model.split(",") if m.strip()] or ["gemini-3.5-flash"]
        self.model = self.models[0]
        self.timeout_s = timeout_s
        self.enabled = enabled and len(self.pool) > 0
        self._model_cool = {m: 0.0 for m in self.models}
        # Models that answered HTTP 400 to a thinkingConfig: later calls to them skip it
        self._no_thinking: set[str] = set()

    def status(self) -> dict:
        return {"enabled": self.enabled, "model": self.model, "models": self.models, **self.pool.status()}

    # ------------------------------------------------------------------ transport
    def generate(self, parts: list[dict], max_tokens: int = 4096, temperature: float = 0.2,
                 timeout_s: float | None = None, attempts: int = 8, budget_s: float | None = None,
                 thinking: str | None = None) -> dict:
        """Calls Gemini with key and model fail-over. Returns the parsed JSON object plus
        `available`, `model`, `key_slot`, `latency_ms`; or {"available": False, "reason": ...}.
        `thinking` ("minimal" / "low" / ...) trades reasoning depth for latency; a model that rejects it
        is retried without it."""
        if not self.enabled:
            return {"available": False, "reason": "Gemini is not configured (set GEMINI_API_KEYS)."}

        body = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": temperature, "responseMimeType": "application/json", "maxOutputTokens": max_tokens},
        }

        def body_for(model: str) -> dict:
            if not thinking or model in self._no_thinking:
                return body
            return {**body, "generationConfig": {**body["generationConfig"], "thinkingConfig": {"thinkingLevel": thinking}}}
        errors: list[str] = []
        tried: set[int] = set()
        give_up_at = time.monotonic() + budget_s if budget_s else None
        for _ in range(min(attempts, max(1, len(self.pool)))):
            if give_up_at is not None and give_up_at - time.monotonic() < 3:
                errors.append("time budget used up")
                break
            lease = self.pool.acquire(tried)
            if lease is None:
                errors.append("no healthy key left")
                break
            slot, key = lease
            tried.add(slot)
            now = time.monotonic()
            models = [m for m in self.models if self._model_cool[m] <= now] or self.models
            for model in models:
                started = time.monotonic()
                try:
                    resp = self._post(model, key, body_for(model), timeout_s, give_up_at)
                    if (resp.status_code == 400 and thinking and model not in self._no_thinking
                            and re.search(r"thinking", _safe_text(resp), re.I)):
                        self._no_thinking.add(model)
                        resp = self._post(model, key, body, timeout_s, give_up_at)
                except httpx.HTTPError as exc:
                    self.pool.cool_down(slot, 10, "network")
                    errors.append(f"slot {slot}: {exc.__class__.__name__}")
                    break

                if resp.status_code == 200:
                    try:
                        data = parse_json_response(resp.json())
                    except ValueError as exc:
                        errors.append(f"slot {slot}: unreadable response ({exc})")
                        continue
                    data.update({"available": True, "model": model, "key_slot": slot,
                                 "latency_ms": int((time.monotonic() - started) * 1000)})
                    return data

                code = resp.status_code
                text = _safe_text(resp)
                if code == 404 or (code == 503 and len(self.models) > 1):
                    self._model_cool[model] = time.monotonic() + (3600 if code == 404 else 30)
                    errors.append(f"{model}: HTTP {code}")
                    continue  # same key, next model
                if code == 429:
                    daily = bool(re.search(r"per day|daily|RequestsPerDay|quota", text, re.I)) and not re.search(r"per minute|PerMinute", text, re.I)
                    self.pool.cool_down(slot, 3600 if daily else _retry_delay(text, 60), "quota")
                elif code in (400, 401, 403):
                    if re.search(r"API key|API_KEY|expired|invalid|disabled|permission|not valid|denied", text, re.I):
                        self.pool.reject(slot, f"HTTP {code}")
                    else:
                        errors.append(f"slot {slot}: HTTP {code} (request refused)")
                        return {"available": False, "reason": "Gemini refused the request: " + "; ".join(errors)}
                else:
                    self.pool.cool_down(slot, 15, f"http {code}")
                errors.append(f"slot {slot}: HTTP {code}")
                log.warning("Gemini call failed on key slot %s: HTTP %s", slot, code)
                break  # next key
        return {"available": False, "reason": "Gemini unavailable: " + "; ".join(errors)}

    def _post(self, model: str, key: str, body: dict, timeout_s: float | None, give_up_at: float | None):
        timeout = timeout_s or self.timeout_s
        if give_up_at is not None:
            timeout = min(timeout, max(3.0, give_up_at - time.monotonic()))
        return httpx.post(ENDPOINT.format(model=model),
                          headers={"x-goog-api-key": key, "Content-Type": "application/json"}, json=body, timeout=timeout)

    # ------------------------------------------------------------------ tasks
    def enrich(self, images, ocr_text: str, declarations: dict, context: dict, labels: list[str] | None = None,
               budget_s: float | None = None) -> dict:
        """Reads the label(s) and returns structured facts (see prompts.ENRICH). `images` is one JPEG or a list."""
        imgs = [images] if isinstance(images, (bytes, bytearray)) else list(images)
        parser_view = {k: v for k, v in declarations.items() if not k.startswith("_")}
        parts = [{"text": prompts.ENRICH},
                 {"text": "CASE CONTEXT:\n" + json.dumps(context, ensure_ascii=False)},
                 {"text": "OCR TEXT:\n" + ocr_text[:7000]},
                 {"text": "PARSER DECLARATIONS:\n" + json.dumps(parser_view, ensure_ascii=False, default=str)[:6000]}]
        parts += image_parts(imgs, labels)
        out = self.generate(parts, max_tokens=3072, budget_s=budget_s)
        return _normalise_enrich(out) if out.get("available") else out

    def verify_rules(self, images, ocr_text: str, rules: list[dict], labels: list[str] | None = None, mode: str = "consumer",
                     budget_s: float | None = None) -> dict:
        """Second look at rules the deterministic engine failed or could not decide."""
        parts = [{"text": prompts.VERIFY}, {"text": f"MODE: {mode}"},
                 {"text": "OCR TEXT (images are numbered from 0 in the order given):\n" + ocr_text[:7000]},
                 {"text": "RULES TO RE-CHECK:\n" + json.dumps(rules, ensure_ascii=False)[:9000]}]
        parts += image_parts(list(images), labels)
        return self.generate(parts, max_tokens=4096, temperature=0.1, budget_s=budget_s)

    def intelligence(self, images, ocr_text: str, enrich: dict, labels: list[str] | None = None, budget_s: float | None = None) -> dict:
        """Consumer product intelligence: ingredients, health, allergens, nutrition, expiry, claims."""
        parts = [{"text": prompts.INTELLIGENCE},
                 {"text": "OCR TEXT:\n" + ocr_text[:7000]},
                 {"text": "LABEL FACTS ALREADY READ:\n" + json.dumps({k: v for k, v in enrich.items() if k in (
                     "product_name", "brand", "category", "ingredients_summary", "allergens", "nutrition_highlights", "claims",
                     "mfg_date", "best_before", "batch_no")}, ensure_ascii=False)[:3000]}]
        parts += image_parts(list(images), labels)
        return self.generate(parts, max_tokens=8192, temperature=0.2, timeout_s=max(self.timeout_s, 60), budget_s=budget_s)

    def compliance_review(self, images, ocr_text: str, rules: list[dict], enrich: dict, authority: str | None,
                          labels: list[str] | None = None, mode: str = "org", budget_s: float | None = None) -> dict:
        """Formal pre-verification / inspection review: consistency, missing items, rule mapping, recommendations."""
        parts = [{"text": prompts.COMPLIANCE_REVIEW}, {"text": f"MODE: {mode}   REGULATOR: {authority or 'Legal Metrology'}"},
                 {"text": "OCR TEXT:\n" + ocr_text[:7000]},
                 {"text": "RULE ENGINE RESULTS (final authority):\n" + json.dumps(rules, ensure_ascii=False)[:11000]},
                 {"text": "LABEL FACTS ALREADY READ:\n" + json.dumps({k: enrich.get(k) for k in (
                     "product_name", "brand", "category", "net_quantity", "mrp", "mfg_date", "best_before", "batch_no",
                     "manufacturer", "country_of_origin", "customer_care", "certifications") if k in enrich}, ensure_ascii=False)[:3000]}]
        parts += image_parts(list(images), labels)
        return self.generate(parts, max_tokens=6144, temperature=0.15, timeout_s=max(self.timeout_s, 60), budget_s=budget_s)

    def ask(self, question: str, product: dict, history: list[dict]) -> dict:
        parts = [{"text": prompts.ASK},
                 {"text": "PRODUCT CONTEXT:\n" + json.dumps(product, ensure_ascii=False, default=str)[:14000]},
                 {"text": "CONVERSATION SO FAR:\n" + json.dumps(history[-8:], ensure_ascii=False)[:4000]},
                 {"text": "QUESTION:\n" + question[:800]}]
        return self.generate(parts, max_tokens=1500, temperature=0.3)

    def compare(self, a: dict, b: dict) -> dict:
        parts = [{"text": prompts.COMPARE},
                 {"text": "PRODUCT A:\n" + json.dumps(a, ensure_ascii=False, default=str)[:9000]},
                 {"text": "PRODUCT B:\n" + json.dumps(b, ensure_ascii=False, default=str)[:9000]}]
        return self.generate(parts, max_tokens=4096, temperature=0.2, timeout_s=max(self.timeout_s, 60))


# ---------------------------------------------------------------------- helpers
def image_parts(images: list[bytes], labels: list[str] | None = None) -> list[dict]:
    parts: list[dict] = []
    for i, img in enumerate(images):
        label = labels[i] if labels and i < len(labels) and labels[i] else f"photo {i + 1}"
        parts.append({"text": f"IMAGE {i} ({label}):"})
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(img).decode()}})
    return parts


def _safe_text(resp) -> str:
    try:
        return str(resp.text)[:600]
    except Exception:
        return ""


def _retry_delay(text: str, default: float) -> float:
    m = re.search(r"retry in ([0-9.]+)s|retryDelay\"?:\s*\"?([0-9.]+)s", text or "")
    if m:
        try:
            return min(900.0, float(m.group(1) or m.group(2)) + 2)
        except ValueError:
            pass
    return default


def parse_json_response(payload: dict) -> dict:
    candidates = payload.get("candidates") or []
    if not candidates:
        raise ValueError("no candidates")
    parts = (candidates[0].get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("not an object")
    return data


_parse = parse_json_response  # kept for older callers / tests


def _normalise_enrich(data: dict) -> dict:
    for key in ("allergens", "nutrition_highlights", "claims", "certifications", "consumer_advisory",
                "regulatory_observations", "suspected_issues", "ocr_corrections"):
        if not isinstance(data.get(key), list):
            data[key] = []
    if not isinstance(data.get("declarations_seen"), dict):
        data["declarations_seen"] = {}
    if data.get("veg_mark") not in ("veg", "non_veg"):
        data["veg_mark"] = None
    return data
