import base64
import json

import cv2
import httpx
import numpy as np
import pytest

from app.ai import assist
from app.ai.gemini import GeminiClient


def jpeg_b64(w=64, h=64) -> str:
    ok, buf = cv2.imencode(".jpg", np.full((h, w, 3), 200, np.uint8))
    assert ok
    return base64.b64encode(buf.tobytes()).decode()


def obs(box, **kw):
    return {"object": True, "box": box, "side": "front", "target_visible": True, **kw}


def test_keys_are_split_between_brain_and_vision():
    assert assist.split_keys(["a", "b", "c", "d", "e"], [], []) == (["a", "c", "e"], ["b", "d"])
    assert assist.split_keys(["a"], [], []) == (["a"], ["a"])
    assert assist.split_keys(["a", "b"], ["x"], []) == (["x"], ["a", "b"])


@pytest.mark.parametrize("box,cue", [
    ([300, 650, 700, 950], "left"),      # object on the right of the frame -> move it left
    ([300, 50, 700, 350], "right"),
    ([20, 300, 380, 700], "down"),       # object high in the frame -> move it down
    ([620, 300, 980, 700], "up"),
    ([450, 450, 550, 550], "closer"),    # small and centred
    ([0, 0, 1000, 1000], "farther"),     # cut on every edge
    ([200, 200, 800, 800], "ready"),
])
def test_positioning_cues(box, cue):
    g = assist.guidance(obs(box), "front", "en")
    assert g["cue"] == cue
    assert g["ready"] is (cue == "ready")
    assert g["say"] == assist.CUES[cue][1]


def test_side_and_quality_cues():
    good = [200, 200, 800, 800]
    assert assist.guidance(obs(good, side="front"), "back", "hi")["cue"] == "show_back"
    assert assist.guidance(obs(good, side="back"), "front", "hi")["cue"] == "show_front"
    assert assist.guidance(obs(good, blurry=True), "front", "hi")["cue"] == "steady"
    assert assist.guidance(obs(good, target_visible=False), "expiry", "hi")["cue"] == "turn_slowly"
    assert assist.guidance({"object": False, "dark": True}, "front", "hi")["cue"] == "dark"
    assert assist.guidance({"object": False}, "front", "hi")["say"] == assist.CUES["no_object"][0]
    assert assist.guidance({"object": False}, "scene", "hi")["ready"]
    # text targets need the object to fill more of the frame
    assert assist.guidance(obs([300, 300, 700, 700]), "front", "en")["cue"] == "ready"
    assert assist.guidance(obs([300, 300, 700, 700]), "ingredients", "en")["cue"] == "closer"


def test_a_readable_close_up_is_not_pushed_away():
    full = [0, 0, 1000, 1000]
    assert assist.guidance(obs(full, side="back", text_readable=True), "back", "en")["cue"] == "ready"
    assert assist.guidance(obs(full, side="back", text_readable=True, blurry=True), "back", "en")["cue"] == "farther"


def test_garbage_boxes_are_ignored():
    assert assist.guidance(obs("nope"), "front", "en")["cue"] == "no_object"
    assert assist.guidance(obs([5, 5, 5, 5]), "front", "en")["cue"] == "no_object"


def test_decode_image_rejects_non_images():
    assert assist.decode_image(jpeg_b64())[:3] == b"\xff\xd8\xff"
    assert assist.decode_image("data:image/jpeg;base64," + jpeg_b64())[:3] == b"\xff\xd8\xff"
    with pytest.raises(ValueError):
        assist.decode_image(base64.b64encode(b"hello world, not an image").decode())
    with pytest.raises(ValueError):
        assist.decode_image("%%%")


def test_normalise_turn_defaults_and_clamps():
    out = assist.normalise_turn({"speak": "Hi", "action": "dance", "speech_rate": 9}, "hi", "m")
    assert out["action"] == "listen" and out["speech_rate"] == 1.6 and out["memory"] == "m" and out["lang"] == "hi"
    assert assist.normalise_turn({"action": "guide"}, "en", "")["target"] == "any_side"
    assert assist.normalise_turn({"action": "look"}, "en", "")["target"] == "scene"


class Resp:
    def __init__(self, status, payload=None, text=""):
        self.status_code = status
        self._payload = payload or {}
        self.text = text

    def json(self):
        return self._payload


def ok(data):
    return Resp(200, {"candidates": [{"content": {"parts": [{"text": json.dumps(data)}]}}]})


def test_thinking_config_is_dropped_when_a_model_rejects_it(monkeypatch):
    bodies = []

    def fake_post(url, headers, json, timeout):
        bodies.append(json)
        if "thinkingConfig" in json["generationConfig"]:
            return Resp(400, text="Unknown name thinkingLevel at generation_config.thinking_config")
        return ok({"object": False})

    monkeypatch.setattr(httpx, "post", fake_post)
    client = GeminiClient(["k1"], "m1", 5)
    assert client.generate([{"text": "x"}], thinking="minimal")["available"]
    assert client.generate([{"text": "x"}], thinking="minimal")["available"]
    assert len(bodies) == 3                       # rejected once, then never sent again for that model
    assert client.pool.status()["rejected"] == 0  # the key is fine


def test_turn_reads_photos_with_vision_then_answers_with_brain(monkeypatch):
    calls = []

    def fake_post(url, headers, json, timeout):
        key = headers["x-goog-api-key"]
        calls.append(key)
        text = json["contents"][0]["parts"][0]["text"]
        if "ComplyVision Saathi" in text:
            assert any("PHOTO READING" in p.get("text", "") for p in json["contents"][0]["parts"])
            return ok({"speak": "Yeh Parle-G biscuit hai.", "lang": "hi", "action": "listen", "memory": "Parle-G seen"})
        return ok({"object_type": "packaged_product", "product": {"name": "Parle-G"}, "answer": "Parle-G", "confidence": 0.9})

    monkeypatch.setattr(httpx, "post", fake_post)
    a = assist.Assistant(GeminiClient(["brain"], "m", 5), GeminiClient(["vision"], "m", 5))
    out = a.turn(now={"type": "event", "event": "capture"}, history=[], memory="", lang="hi", script="devanagari",
                 today="2026-11-01", captures=[(b"\xff\xd8\xffimg", "front")], goal="identify")
    assert out["available"] and out["speak"].startswith("Yeh Parle-G") and out["memory"] == "Parle-G seen"
    assert out["reading"]["product"]["name"] == "Parle-G"
    assert calls == ["vision", "brain"]


def test_turn_falls_back_to_the_reading_when_the_brain_is_down(monkeypatch):
    def fake_post(url, headers, json, timeout):
        if headers["x-goog-api-key"] == "brain":
            return Resp(500, text="boom")
        return ok({"answer": "It is a 500 rupee note.", "confidence": 0.95})

    monkeypatch.setattr(httpx, "post", fake_post)
    a = assist.Assistant(GeminiClient(["brain"], "m", 5), GeminiClient(["vision"], "m", 5))
    out = a.turn(now={"type": "event"}, history=[], memory="", lang="en", script="latin", today="",
                 captures=[(b"\xff\xd8\xffimg", "front")])
    assert out["available"] and out["speak"] == "It is a 500 rupee note." and out["action"] == "listen"


def test_endpoints(monkeypatch):
    from fastapi.testclient import TestClient

    from app import main

    def fake_post(url, headers, json, timeout):
        text = json["contents"][0]["parts"][0]["text"]
        if "ONE live frame" in text:
            return ok({"object": True, "box": [100, 600, 500, 990], "side": "front", "target_visible": True})
        return ok({"speak": "Namaste", "action": "listen", "memory": ""})

    monkeypatch.setattr(httpx, "post", fake_post)
    monkeypatch.setattr(main, "assistant", assist.Assistant(GeminiClient(["b"], "m", 5), GeminiClient(["v"], "m", 5)))
    client = TestClient(main.app)

    r = client.post("/assist/frame", json={"image": jpeg_b64(), "target": "front", "lang": "en"})
    assert r.status_code == 200 and r.json()["cue"] == "left"

    r = client.post("/assist/turn", json={"now": {"type": "event", "event": "session_start"}, "frame": jpeg_b64()})
    assert r.status_code == 200 and r.json()["speak"] == "Namaste" and r.json()["action"] == "listen"

    r = client.post("/assist/frame", json={"image": base64.b64encode(b"x" * 200).decode()})
    assert r.status_code == 422
