import json

import httpx

from app.ai import gemini as g


class Resp:
    def __init__(self, status, payload=None):
        self.status_code = status
        self._payload = payload or {}

    def json(self):
        return self._payload


def ok_payload(data):
    return {"candidates": [{"content": {"parts": [{"text": "```json\n" + json.dumps(data) + "\n```"}]}}]}


def test_pool_rotates_and_skips_cooling_keys():
    pool = g.KeyPool(["a", "b", "c", "a"])
    assert len(pool) == 3
    assert [pool.acquire()[1] for _ in range(4)] == ["a", "b", "c", "a"]
    pool.cool_down(1, 60)
    assert [pool.acquire()[1] for _ in range(3)] == ["c", "a", "c"]


def test_falls_over_to_next_key_on_quota(monkeypatch):
    calls = []

    def fake_post(url, headers, json, timeout):
        calls.append(headers["x-goog-api-key"])
        if headers["x-goog-api-key"] == "k1":
            return Resp(429)
        return Resp(200, ok_payload({"summary": "Rice pack", "declarations_seen": {"mrp": True}}))

    monkeypatch.setattr(httpx, "post", fake_post)
    client = g.GeminiClient(["k1", "k2"], "gemini-test", 5)
    out = client.enrich(b"jpg", "MRP 10", {}, {})
    assert out["available"] and out["key_slot"] == 1 and out["summary"] == "Rice pack"
    assert out["allergens"] == [] and calls == ["k1", "k2"]
    assert "k1" not in json.dumps(out) and "k2" not in json.dumps(out)
    # k1 is cooling down, so the next call goes straight to k2
    calls.clear()
    client.enrich(b"jpg", "", {}, {})
    assert calls == ["k2"]


def test_unconfigured_or_all_failing(monkeypatch):
    assert not g.GeminiClient([], "m", 5).enrich(b"", "", {}, {})["available"]
    monkeypatch.setattr(httpx, "post", lambda *a, **k: Resp(503))
    out = g.GeminiClient(["x"], "m", 5).enrich(b"", "", {}, {})
    assert not out["available"] and "HTTP 503" in out["reason"]
