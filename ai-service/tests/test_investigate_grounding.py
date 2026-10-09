"""Tests for evidence-grounded anomaly investigation, citations, and confidence capping."""

import json
from fastapi.testclient import TestClient

import app.routes.ai as ai_routes
from app.main import app

client = TestClient(app)


def test_investigate_not_configured(monkeypatch):
    monkeypatch.setattr(ai_routes, "is_configured", lambda: False)
    payload = {
        "anomalyId": "anom-1",
        "type": "LATENCY_SPIKE",
        "severity": "HIGH",
        "metric": "durationMs",
        "expected": "120ms",
        "actual": "4500ms",
    }
    resp = client.post("/ai/investigate-anomaly", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["configured"] is False
    assert body["fallback"] is True
    assert body["evidenceCitations"] == []
    assert body["verificationChecklist"] == []


def test_investigate_grounded_response(monkeypatch):
    monkeypatch.setattr(ai_routes, "is_configured", lambda: True)

    fake_provider_response = {
        "summary": "Http request node experienced a 35x latency spike due to downstream timeout.",
        "likelyCauses": [
            {
                "cause": "Downstream endpoint slow response on HttpRequest node",
                "category": "external-dependency",
                "confidence": 0.85,
                "uncertainty": "Provider response headers not captured in trace",
                "citations": ["nodeMetrics[1].durationMs", "baseline.median"],
            }
        ],
        "evidence": [
            {
                "type": "metric",
                "description": "HttpRequest duration rose to 4500ms vs median 120ms",
                "source": "nodeMetrics",
                "inference": "Node accounts for 95% of execution latency",
            }
        ],
        "impact": "Single workflow execution delayed by 4.3 seconds",
        "recommendedActions": [
            {
                "action": "Inspect target API endpoint status",
                "rationale": "High latency isolated to HttpRequest node",
                "risk": "low",
                "effort": "low",
            }
        ],
        "confidence": 0.85,
        "model": "gemini-1.5-pro",
        "evidenceCitations": ["nodeMetrics[1].durationMs", "baseline.median"],
        "suggestedFix": "Increase timeout threshold or add retry with backoff on HttpRequest node",
        "verificationChecklist": [
            "Verify next 3 executions have duration under 200ms",
            "Confirm downstream endpoint health status",
        ],
        "fallback": False,
    }

    class MockProvider:
        def default_model(self):
            return "test-model"

        def generate_json(self, system_prompt, user_prompt, model):
            return json.dumps(fake_provider_response)

    monkeypatch.setattr(ai_routes, "get_provider", lambda: MockProvider())

    payload = {
        "anomalyId": "anom-2",
        "type": "LATENCY_SPIKE",
        "severity": "HIGH",
        "platformType": "N8N",
        "metric": "durationMs",
        "expected": "120ms",
        "actual": "4500ms",
        "baseline": {"median": 120, "mad": 15},
        "nodeMetrics": [{"nodeId": "n1", "durationMs": 4500}],
        "recoveryStatus": {"healthyCount": 0, "requiredCount": 3},
    }

    resp = client.post("/ai/investigate-anomaly", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["configured"] is True
    assert body["confidence"] == 0.85
    assert len(body["likelyCauses"]) == 1
    assert body["likelyCauses"][0]["confidence"] == 0.85
    assert body["likelyCauses"][0]["citations"] == ["nodeMetrics[1].durationMs", "baseline.median"]
    assert "baseline.median" in body["evidenceCitations"]
    assert "nodeMetrics[1].durationMs" in body["evidenceCitations"]
    assert body["suggestedFix"] is not None
    assert len(body["verificationChecklist"]) == 2
    assert body["fallback"] is False


def test_investigate_ungrounded_cause_caps_confidence(monkeypatch):
    monkeypatch.setattr(ai_routes, "is_configured", lambda: True)

    fake_provider_response = {
        "summary": "Speculative hypothesis with no citations.",
        "likelyCauses": [
            {
                "cause": "Possible database deadlocks",
                "category": "infrastructure",
                "confidence": 0.90,  # High confidence claim without citations
                "uncertainty": "No DB logs available",
                "citations": [],  # Empty citations
            }
        ],
        "evidence": [],
        "impact": "Unknown",
        "recommendedActions": [],
        "confidence": 0.85,
        "evidenceCitations": [],
        "suggestedFix": "Restart DB",
        "verificationChecklist": [],
    }

    class MockProvider:
        def default_model(self):
            return "test-model"

        def generate_json(self, system_prompt, user_prompt, model):
            return json.dumps(fake_provider_response)

    monkeypatch.setattr(ai_routes, "get_provider", lambda: MockProvider())

    payload = {
        "anomalyId": "anom-3",
        "type": "LATENCY_SPIKE",
        "severity": "HIGH",
    }

    resp = client.post("/ai/investigate-anomaly", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    # Confidence for ungrounded cause must be capped below 0.4
    assert body["likelyCauses"][0]["confidence"] < 0.4
    # Overall confidence with no citations must also be capped below 0.4
    assert body["confidence"] < 0.4
