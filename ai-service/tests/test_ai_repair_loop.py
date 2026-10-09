"""Tests for the controlled AI repair loop."""

import pytest
import json

from app.workflow_generator import WorkflowGenerator, WorkflowGenerationError


class MockRepairingProvider:
    def __init__(self, responses: list[str]):
        self.responses = list(responses)
        self.history: list[str] = []

    def is_configured(self) -> bool:
        return True

    def default_model(self) -> str:
        return "mock-model"

    def generate_json(self, system_prompt: str, user_prompt: str, model: str | None = None) -> str:
        self.history.append(user_prompt)
        if not self.responses:
            raise RuntimeError("Exhausted mock responses")
        return self.responses.pop(0)


def test_repair_loop_succeeds_on_first_retry():
    # Response 1: invalid because it has an unknown node type
    resp1 = json.dumps({
        "nodes": [
            {"id": "t", "type": "manual_trigger"},
            {"id": "bad", "type": "custom_python_step"},
        ],
        "edges": [{"source": "t", "target": "bad"}],
    })

    # Response 2: fixed to use registered http_request node
    resp2 = json.dumps({
        "nodes": [
            {"id": "t", "type": "manual_trigger"},
            {"id": "good", "type": "http_request", "config": {"method": "GET", "url": "https://api.example.com"}},
        ],
        "edges": [{"source": "t", "target": "good"}],
    })

    provider = MockRepairingProvider([resp1, resp2])
    generator = WorkflowGenerator(provider)

    result = generator.generate("Call API on click")

    assert len(provider.history) == 2
    # Verify machine-readable validation feedback was given to the model
    assert "UNKNOWN_NODE_TYPE" in provider.history[1]
    assert len(result["nodes"]) == 2
    assert result["nodes"][1]["type"] == "http_request"


def test_repair_loop_fails_after_max_attempts():
    # Both responses are invalid (missing required branch on condition)
    resp_invalid = json.dumps({
        "nodes": [
            {"id": "t", "type": "manual_trigger"},
            {"id": "c", "type": "condition", "config": {"expression": "true"}},
            {"id": "ok", "type": "notification", "config": {"channel": "slack", "message": "done"}},
        ],
        "edges": [
            {"source": "t", "target": "c"},
            {"source": "c", "target": "ok", "sourceHandle": "true"},
        ],
    })

    # Provide 3 invalid responses (initial + attempt 1 + attempt 2)
    provider = MockRepairingProvider([resp_invalid, resp_invalid, resp_invalid])
    generator = WorkflowGenerator(provider)

    with pytest.raises(WorkflowGenerationError) as exc_info:
        generator.generate("Branching request")

    assert exc_info.value.code == "AI_GENERATION_FAILED"
    # Initial + 2 repairs = 3 generations total
    assert len(provider.history) == 3


def test_repair_does_not_loop_infinitely():
    resp_malformed = "this is not json at all"
    provider = MockRepairingProvider([resp_malformed, resp_malformed, resp_malformed])
    generator = WorkflowGenerator(provider)

    with pytest.raises(WorkflowGenerationError):
        generator.generate("do something")

    # Should have stopped after repair attempts
    assert len(provider.history) <= 3
