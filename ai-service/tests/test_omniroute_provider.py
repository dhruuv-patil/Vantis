"""OmniRoute provider tests: provider selection, error handling, timeouts,
and workflow generation — all without touching the network.
"""

from types import SimpleNamespace
from unittest.mock import MagicMock

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import (
    APIConnectionError,
    APITimeoutError,
    AuthenticationError,
    InternalServerError,
    NotFoundError,
    RateLimitError,
)
from pydantic import ValidationError

from app.config import Settings
from app.main import app
from app.providers import get_provider
from app.providers.base import ProviderError
from app.providers.gemini import GeminiProvider
from app.providers.omniroute import OmniRouteProvider

client = TestClient(app)


def _mock_openai_response(content: str):
    message = SimpleNamespace(content=content, tool_calls=None)
    choice = SimpleNamespace(message=message)
    return SimpleNamespace(choices=[choice])


def test_omniroute_selected(monkeypatch):
    monkeypatch.setattr(
        "app.providers.get_settings",
        lambda: Settings(ai_provider="omniroute"),
    )
    get_provider.cache_clear()
    provider = get_provider()
    assert isinstance(provider, OmniRouteProvider)
    assert provider.name == "omniroute"


def test_gemini_selected(monkeypatch):
    monkeypatch.setattr(
        "app.providers.get_settings",
        lambda: Settings(ai_provider="gemini"),
    )
    get_provider.cache_clear()
    provider = get_provider()
    assert isinstance(provider, GeminiProvider)
    assert provider.name == "gemini"


def test_invalid_provider_in_settings():
    with pytest.raises(ValidationError):
        Settings(ai_provider="unsupported_provider")


def test_missing_provider_in_settings():
    with pytest.raises(ValidationError):
        Settings(ai_provider="")


def test_omniroute_not_configured(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    assert provider.is_configured() is False
    with pytest.raises(ProviderError, match="not configured"):
        provider.generate_json("sys", "user")


def test_omniroute_valid_workflow_response(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    valid_json = '{"nodes": [{"id": "trigger", "type": "webhook_trigger"}], "edges": [], "notes": "ok"}'
    mock_client.chat.completions.create.return_value = _mock_openai_response(valid_json)
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    result = provider.generate_json("system", "prompt")
    assert result == valid_json


def test_omniroute_markdown_fenced_response(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    raw = '```json\n{"nodes": [], "edges": []}\n```'
    mock_client.chat.completions.create.return_value = _mock_openai_response(raw)
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    result = provider.generate_json("system", "prompt")
    assert result == '{"nodes": [], "edges": []}'


def test_omniroute_malformed_workflow_response(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    mock_client.chat.completions.create.return_value = _mock_openai_response("not-json")
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="invalid JSON"):
        provider.generate_json("system", "prompt")


def test_omniroute_empty_response(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    mock_client.chat.completions.create.return_value = _mock_openai_response("")
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="empty response"):
        provider.generate_json("system", "prompt")


def test_omniroute_timeout(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    req = httpx.Request("POST", "http://host.docker.internal:20128/v1/chat/completions")
    mock_client.chat.completions.create.side_effect = APITimeoutError(request=req)
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="timed out"):
        provider.generate_json("system", "prompt")


def test_omniroute_authentication_error(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    resp = httpx.Response(401, request=httpx.Request("POST", "http://test"))
    mock_client.chat.completions.create.side_effect = AuthenticationError(
        message="invalid key sk-1234567890",
        response=resp,
        body={"error": "unauthorized"},
    )
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="authentication failed"):
        provider.generate_json("system", "prompt")


def test_omniroute_connection_error(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    req = httpx.Request("POST", "http://host.docker.internal:20128/v1/chat/completions")
    mock_client.chat.completions.create.side_effect = APIConnectionError(request=req)
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="could not be reached"):
        provider.generate_json("system", "prompt")


def test_omniroute_model_not_found(monkeypatch):
    provider = OmniRouteProvider()
    monkeypatch.setattr(
        provider,
        "settings",
        Settings(
            ai_provider="omniroute",
            omniroute_api_key="test-key",
            omniroute_base_url="http://host.docker.internal:20128/v1",
        ),
    )
    mock_client = MagicMock()
    resp = httpx.Response(404, request=httpx.Request("POST", "http://test"))
    mock_client.chat.completions.create.side_effect = NotFoundError(
        message="Model not found",
        response=resp,
        body={"error": "model_not_found"},
    )
    monkeypatch.setattr(provider, "_client", lambda: mock_client)

    with pytest.raises(ProviderError, match="model not found"):
        provider.generate_json("system", "prompt")


def test_endpoint_generate_workflow_with_omniroute(monkeypatch):
    provider = OmniRouteProvider()
    valid_json = '{"nodes": [{"id": "trigger", "type": "webhook_trigger", "label": "Webhook", "config": {}}], "edges": [], "notes": "ok"}'
    monkeypatch.setattr(provider, "is_configured", lambda: True)
    monkeypatch.setattr(provider, "generate_json", lambda sys, user, model=None: valid_json)
    monkeypatch.setattr("app.routes.ai.get_provider", lambda: provider)
    monkeypatch.setattr("app.routes.ai.is_configured", lambda: True)

    resp = client.post("/ai/generate-workflow", json={"prompt": "build webhook workflow"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["configured"] is True
    assert len(data["graph"]["nodes"]) == 1
    assert data["graph"]["nodes"][0]["type"] == "webhook_trigger"
    assert data["notes"] == "ok"
