"""Accessibility assistant: a voice-and-camera helper for blind and low-vision users.

Two Gemini roles, each with its own half of the key pool so the live camera loop never starves the conversation:
  * vision - reads live camera frames (where is the object, which side, is it sharp) and captured photos
             (product, medicine, money, text);
  * brain  - holds the whole conversation, decides what to say and what the app does next.

The positional cue the user hears ("move a little left", "bring it closer") is computed here from the frame's
bounding box rather than written by the model, so it is consistent from one frame to the next.
"""

from __future__ import annotations

import base64
import binascii
import json

from . import prompts
from .gemini import GeminiClient

ACTIONS = ("listen", "guide", "look", "compliance", "torch_on", "torch_off", "exit")
TARGETS = ("front", "back", "expiry", "price", "ingredients", "any_side", "text", "document", "currency",
           "medicine", "barcode", "scene")
# Targets whose text must be read: the object has to fill more of the frame
TEXT_TARGETS = {"back", "expiry", "price", "ingredients", "text", "document", "medicine", "barcode"}
MAX_HISTORY = 40


def split_keys(keys: list[str], brain: list[str], vision: list[str]) -> tuple[list[str], list[str]]:
    """Explicit pools win; otherwise alternate keys of the main pool go to each role (one key is shared)."""
    keys = list(dict.fromkeys(k for k in keys if k))
    if not brain and not vision:
        if len(keys) < 2:
            return keys, keys
        return keys[0::2], keys[1::2]
    return (brain or keys), (vision or keys)


def decode_image(data: str | None) -> bytes | None:
    """base64 (optionally a data: URL) -> JPEG/PNG bytes; ValueError when it is not an image."""
    if not data:
        return None
    if data.startswith("data:"):
        data = data.split(",", 1)[-1]
    try:
        raw = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("image is not valid base64") from exc
    if not (raw[:3] == b"\xff\xd8\xff" or raw[:8] == b"\x89PNG\r\n\x1a\n" or raw[8:12] == b"WEBP"):
        raise ValueError("image must be JPEG, PNG or WEBP")
    return raw


def _mime(img: bytes) -> str:
    if img[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if img[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def _image_part(img: bytes) -> dict:
    return {"inline_data": {"mime_type": _mime(img), "data": base64.b64encode(img).decode()}}


# ---------------------------------------------------------------------------------------------- guidance
CUES = {
    "no_object": ("कैमरे में अभी कुछ नहीं दिख रहा। चीज़ को फ़ोन के ठीक सामने, लगभग एक फुट दूर रखिए।",
                  "I can't see it yet. Hold it straight in front of the phone, about one foot away."),
    "dark": ("बहुत अंधेरा है। रोशनी की तरफ़ जाइए।", "It is too dark. Please move towards some light."),
    "hand": ("आपकी उंगलियाँ लेबल ढक रही हैं। किनारों से पकड़िए।", "Your fingers are covering the label. Hold it by the edges."),
    "left": ("थोड़ा बाएँ कीजिए।", "Move it a little to the left."),
    "right": ("थोड़ा दाएँ कीजिए।", "Move it a little to the right."),
    "up": ("थोड़ा ऊपर कीजिए।", "Move it a little up."),
    "down": ("थोड़ा नीचे कीजिए।", "Move it a little down."),
    "closer": ("थोड़ा पास लाइए।", "Bring it a little closer."),
    "farther": ("थोड़ा दूर कीजिए।", "Move it a little farther away."),
    "show_back": ("अब इसे घुमाइए, पीछे वाला हिस्सा कैमरे की तरफ़ कीजिए।", "Now turn it around so the back faces the camera."),
    "show_front": ("इसे घुमाइए, सामने वाला हिस्सा कैमरे की तरफ़ कीजिए।", "Turn it around so the front faces the camera."),
    "turn_slowly": ("धीरे-धीरे घुमाइए। तारीख़ अक्सर ऊपर, नीचे या पीछे छपी होती है।",
                    "Turn it slowly. Dates are often printed on the top, bottom or back."),
    "find_text": ("धीरे-धीरे घुमाइए, ताकि लिखा हुआ हिस्सा कैमरे के सामने आए।", "Turn it slowly so the printed text faces the camera."),
    "upside_down": ("यह उल्टा है, सीधा कीजिए।", "It is upside down. Please turn it the right way up."),
    "steady": ("हिलाइए मत, स्थिर रखिए।", "Hold it still."),
    "glare": ("चमक आ रही है, थोड़ा तिरछा कीजिए।", "There is glare. Tilt it slightly."),
    "ready": ("बिल्कुल सही, ऐसे ही रोकिए।", "Perfect, hold it right there."),
}


def cue_text(cue: str, lang: str) -> str:
    hi, en = CUES[cue]
    return hi if lang == "hi" else en


def _box(raw) -> tuple[float, float, float, float] | None:
    """[ymin, xmin, ymax, xmax] on a 0-1000 scale -> (x0, y0, x1, y1) on 0-1, or None when unusable."""
    if not isinstance(raw, (list, tuple)) or len(raw) != 4:
        return None
    try:
        y0, x0, y1, x1 = (min(1000.0, max(0.0, float(v))) / 1000.0 for v in raw)
    except (TypeError, ValueError):
        return None
    x0, x1 = sorted((x0, x1))
    y0, y1 = sorted((y0, y1))
    if x1 - x0 < 0.01 or y1 - y0 < 0.01:
        return None
    return x0, y0, x1, y1


def guidance(obs: dict, target: str, lang: str) -> dict:
    """Turns one frame observation into the next spoken cue. `ready` = good enough to take the photo.

    Direction words describe moving the OBJECT (the user holds it in the other hand): an object that appears on
    the right of the frame must move left. The frame is upright (the app rotates it before sending)."""
    target = target if target in TARGETS else "any_side"
    box = _box(obs.get("box"))
    out = {"ready": False, "cue": "no_object", "score": 0.0, "box": list(box) if box else None,
           "label": obs.get("label"), "side": obs.get("side") or "unknown"}

    if target == "scene":
        out.update(ready=True, cue="ready", score=1.0)
    elif not obs.get("object") or box is None:
        out["cue"] = "dark" if obs.get("dark") else "no_object"
    else:
        x0, y0, x1, y1 = box
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        dx, dy = cx - 0.5, cy - 0.5
        fill = (x1 - x0) * (y1 - y0)
        lo = 0.22 if target in TEXT_TARGETS else 0.12
        cut_x = x0 < 0.02 and x1 > 0.98
        cut_y = y0 < 0.02 and y1 > 0.98
        tol = 0.16 if fill > 0.2 else 0.2
        side = obs.get("side") or "unknown"
        wants_back = target in ("back", "ingredients")

        if obs.get("dark"):
            cue = "dark"
        elif fill < lo * 0.45 and abs(dx) <= 0.3 and abs(dy) <= 0.3:
            cue = "closer"
        elif abs(dx) > tol or abs(dy) > tol:
            if abs(dx) >= abs(dy):
                cue = "left" if dx > 0 else "right"
            else:
                cue = "up" if dy > 0 else "down"
        elif ((cut_x and cut_y) or fill > 0.92) and not (obs.get("text_readable") and not obs.get("blurry")):
            cue = "farther"   # filling the frame is fine as long as the print is sharp and readable
        elif fill < lo:
            cue = "closer"
        elif obs.get("hand_blocking"):
            cue = "hand"
        elif wants_back and side == "front":
            cue = "show_back"
        elif target == "front" and side == "back":
            cue = "show_front"
        elif obs.get("upside_down") and target in TEXT_TARGETS | {"front"}:
            cue = "upside_down"
        elif obs.get("blurry"):
            cue = "steady"
        elif obs.get("glare"):
            cue = "glare"
        elif not obs.get("target_visible", True):
            if wants_back:
                cue = "show_back"
            elif target in ("expiry", "price", "barcode"):
                cue = "turn_slowly"
            elif target in ("front", "any_side", "currency", "scene"):
                cue = "ready"   # the object itself is what we need
            else:
                cue = "find_text"
        else:
            cue = "ready"

        centred = max(0.0, 1 - (abs(dx) + abs(dy)) / 0.6)
        sized = min(1.0, fill / lo) if fill < lo else (1.0 if fill <= 0.92 else 0.6)
        out.update(cue=cue, ready=cue == "ready", score=round(1.0 if cue == "ready" else 0.85 * centred * sized, 3))
    out["say"] = cue_text(out["cue"], lang)
    return out


# ---------------------------------------------------------------------------------------------- service
class Assistant:
    def __init__(self, brain: GeminiClient, vision: GeminiClient, frame_thinking: str = "", turn_thinking: str = ""):
        self.brain = brain
        self.vision = vision
        self.frame_thinking = frame_thinking or None
        self.turn_thinking = turn_thinking or None

    @property
    def enabled(self) -> bool:
        return self.brain.enabled and self.vision.enabled

    def status(self) -> dict:
        return {"enabled": self.enabled, "brain": self.brain.pool.status(), "vision": self.vision.pool.status()}

    def frame(self, image: bytes, target: str, goal: str, lang: str) -> dict:
        """One live-guidance step: where is the object and what should the user do next."""
        lang = "en" if lang == "en" else "hi"
        target = target if target in TARGETS else "any_side"
        prompt = prompts.ASSIST_FRAME.replace("<<TARGET>>", target).replace("<<GOAL>>", (goal or "identify the item")[:200])
        obs = self.vision.generate([{"text": prompt}, _image_part(image)], max_tokens=400, temperature=0.0,
                                   timeout_s=12, attempts=3, thinking=self.frame_thinking)
        if not obs.get("available"):
            return {"available": False, "ready": False, "cue": "", "say": "", "score": 0.0}
        out = guidance(obs, target, lang)
        out.update(available=True, latency_ms=obs.get("latency_ms"),
                   obs={k: obs.get(k) for k in ("object", "label", "side", "upside_down", "target_visible",
                                                "text_readable", "blurry", "glare", "dark", "hand_blocking")})
        return out

    def read(self, images: list[tuple[bytes, str]], goal: str, today: str) -> dict:
        """Reads the captured photos (all sides of the same item) for the brain."""
        prompt = prompts.ASSIST_READ.replace("<<GOAL>>", (goal or "identify the item")[:300]).replace("<<TODAY>>", today or "")
        parts: list[dict] = [{"text": prompt}]
        for i, (img, label) in enumerate(images):
            parts.append({"text": f"IMAGE {i} ({label or 'photo'}):"})
            parts.append(_image_part(img))
        out = self.vision.generate(parts, max_tokens=3072, temperature=0.1, timeout_s=40, attempts=4,
                                   thinking=self.turn_thinking)
        if out.get("available"):
            for k in ("model", "key_slot", "latency_ms"):
                out.pop(k, None)
        return out

    def turn(self, *, now: dict, history: list[dict], memory: str, lang: str, script: str, today: str,
             frame: bytes | None = None, captures: list[tuple[bytes, str]] | None = None, goal: str = "") -> dict:
        """One conversational step. With `captures` the photos are read first (vision pool), then the brain
        (brain pool) decides what to say with the full conversation, the reading and the live view."""
        lang = "en" if lang == "en" else "hi"
        reading = None
        if captures:
            reading = self.read(captures, goal, today)
            if not reading.get("available"):
                reading = {"available": False, "note": "the photos could not be read right now"}
            else:
                reading.pop("available", None)

        prompt = prompts.ASSIST_BRAIN.replace("<<SCRIPT>>", "Latin (Roman Hinglish)" if script == "latin" else "Devanagari")
        convo = [{"role": h.get("role"), "text": str(h.get("text", ""))[:600]} for h in history[-MAX_HISTORY:]
                 if isinstance(h, dict) and h.get("text")]
        parts: list[dict] = [
            {"text": prompt},
            {"text": f"TODAY: {today}\nLANGUAGE: {lang}"},
            {"text": "MEMORY:\n" + (memory or "(empty)")[:1500]},
            {"text": "CONVERSATION:\n" + json.dumps(convo, ensure_ascii=False)[:12000]},
            {"text": "NOW:\n" + json.dumps(now, ensure_ascii=False, default=str)[:4000]},
        ]
        if reading is not None:
            parts.append({"text": "PHOTO READING:\n" + json.dumps(reading, ensure_ascii=False, default=str)[:7000]})
        if frame:
            parts += [{"text": "CAMERA (live view right now):"}, _image_part(frame)]
        out = self.brain.generate(parts, max_tokens=1200, temperature=0.4, timeout_s=30, attempts=4,
                                  thinking=self.turn_thinking)
        if not out.get("available"):
            if reading and reading.get("answer"):
                # The brain is busy, but the photos were read: still tell the user what we found
                return {"available": True, "speak": str(reading["answer"])[:500], "lang": lang, "action": "listen",
                        "target": None, "goal": None, "new_object": False, "speech_rate": None, "memory": memory,
                        "reading": reading, "degraded": True}
            return {"available": False, "reading": reading}
        return {"available": True, **normalise_turn(out, lang, memory), "reading": reading}


def normalise_turn(out: dict, lang: str, memory: str) -> dict:
    action = out.get("action") if out.get("action") in ACTIONS else "listen"
    target = out.get("target") if out.get("target") in TARGETS else None
    if action == "guide" and target is None:
        target = "any_side"
    if action == "look" and target is None:
        target = "scene"
    speak = str(out.get("speak") or "").strip()
    rate = out.get("speech_rate")
    try:
        rate = None if rate is None else min(1.6, max(0.6, float(rate)))
    except (TypeError, ValueError):
        rate = None
    return {
        "speak": speak[:900],
        "lang": out.get("lang") if out.get("lang") in ("hi", "en") else lang,
        "action": action,
        "target": target,
        "goal": (str(out.get("goal"))[:300] if out.get("goal") else None),
        "new_object": bool(out.get("new_object")),
        "speech_rate": rate,
        "memory": str(out.get("memory") or memory or "")[:900],
    }
