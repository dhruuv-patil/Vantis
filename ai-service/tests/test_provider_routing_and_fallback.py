"""Tests for provider abstraction and failover between OmniRoute and Gemini."""

import pytest
from unittest.mock import MagicMock

from app.providers.base import LLMProvider, ProviderError
from app.providers.router import ProviderRouter


class DummyProvider:
    def __init__(self, name: str, configured: bool = True, fail: bool = False, response: str = ""):
        self.name = name
        self._configured = configured
        self._fail = fail
        self._response = response
        self.calls = 0

    def is_configured(self) -> bool:
        return self._configured

    def default_model(self) -> str:
        return f"{self.name}-default-model"

    def generate_json(self, system_prompt: str, user_prompt: str, model: str | None = None) -> str:
        self.calls += 1
        if self._fail:
            raise ProviderError(f"{self.name} connection timed out.")
        return self._response

    def run_agent(self, instructions: str, model: str | None, user_input: str, enabled_tools: list[str]):
        if self._fail:
            raise ProviderError(f"{self.name} agent failed.")
        from app.providers.base import AgentResult
        return AgentResult(output="done", model=self.default_model())


def test_primary_provider_succeeds_without_fallback():
    valid_wf = '{"nodes": [{"id": "t", "type": "manual_trigger"}], "edges": []}'
    primary = DummyProvider("omniroute", configured=True, fail=False, response=valid_wf)
    fallback = DummyProvider("gemini", configured=True, fail=False, response="unused")

    router = ProviderRouter(primary=primary, fallback=fallback)
    content, used = router.generate_json("sys", "user")

    assert content == valid_wf
    assert used == "omniroute"
    assert primary.calls == 1
    assert fallback.calls == 0


def test_primary_failure_triggers_fallback():
    valid_wf = '{"nodes": [{"id": "t", "type": "manual_trigger"}], "edges": []}'
    primary = DummyProvider("omniroute", configured=True, fail=True)
    fallback = DummyProvider("gemini", configured=True, fail=False, response=valid_wf)

    router = ProviderRouter(primary=primary, fallback=fallback)
    content, used = router.generate_json("sys", "user")

    assert content == valid_wf
    assert used == "gemini"
    assert primary.calls == 1
    assert fallback.calls == 1


def test_both_providers_fail():
    primary = DummyProvider("omniroute", configured=True, fail=True)
    fallback = DummyProvider("gemini", configured=True, fail=True)

    router = ProviderRouter(primary=primary, fallback=fallback)
    with pytest.raises(ProviderError, match="Both primary .* and fallback .* AI providers failed"):
        router.generate_json("sys", "user")


def test_neither_provider_configured():
    primary = DummyProvider("omniroute", configured=False)
    fallback = DummyProvider("gemini", configured=False)

    router = ProviderRouter(primary=primary, fallback=fallback)
    assert router.is_configured() is False
    with pytest.raises(ProviderError, match="is not configured"):
        router.generate_json("sys", "user")
