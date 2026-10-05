"""Gemini provider: not-configured honesty, tool-spec sanitisation, and the
manual tool-calling loop mapped onto ``AgentResult`` — all without touching the
network.

The loop tests need the ``google-genai`` types, so they are skipped where the
SDK isn't installed; the not-configured and tool-spec tests run everywhere.
"""

from types import SimpleNamespace

import pytest

from app.config import Settings
from app.providers.base import ProviderError
from app.providers.gemini import GeminiProvider
from app.tools import function_specs


def test_function_specs_drops_additional_properties():
    specs = function_specs(["get_current_time", "calculate"])
    assert {s["name"] for s in specs} == {"get_current_time", "calculate"}
    for spec in specs:
        # Gemini's function-declaration schema rejects additionalProperties.
        assert "additionalProperties" not in spec["parameters"]
        assert spec["parameters"]["type"] == "object"
        assert "properties" in spec["parameters"]
    # required is preserved where the tool declares it.
    calc = next(s for s in specs if s["name"] == "calculate")
    assert calc["parameters"]["required"] == ["expression"]


def test_function_specs_skips_unknown_tools():
    assert function_specs(["nope"]) == []


def test_is_configured_reads_settings(monkeypatch):
    provider = GeminiProvider()
    monkeypatch.setattr(
        "app.providers.gemini.get_settings", lambda: Settings(gemini_api_key="")
    )
    assert provider.is_configured() is False
    monkeypatch.setattr(
        "app.providers.gemini.get_settings", lambda: Settings(gemini_api_key="secret")
    )
    assert provider.is_configured() is True


def _fake_client(responses):
    """A stand-in google-genai client whose chat API yields queued responses."""
    queue = list(responses)

    class FakeChat:
        def send_message(self, **kwargs):
            return queue.pop(0)

    def create(**kwargs):
        return FakeChat()

    return SimpleNamespace(
        chats=SimpleNamespace(create=create),
    )
    """A stand-in google-genai client whose generate_content yields queued responses."""
    queue = list(responses)

    def generate_content(**kwargs):
        return queue.pop(0)

    return SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))


def test_run_agent_executes_tools_and_maps_result(monkeypatch):
    pytest.importorskip("google.genai")

    provider = GeminiProvider()
    # Turn 1: the model asks to call get_current_time. Turn 2: it answers with text.
    call = SimpleNamespace(name="get_current_time", args={})
    turn1 = SimpleNamespace(
        function_calls=[call],
        text=None,
        candidates=[SimpleNamespace(content=SimpleNamespace(role="model", parts=[]))],
    )
    turn2 = SimpleNamespace(function_calls=[], text="The time is now.", candidates=[])
    monkeypatch.setattr(provider, "_build_client", lambda: _fake_client([turn1, turn2]))

    result = provider.run_agent(
        "You are helpful.", "gemini-2.5-flash", "what time?", ["get_current_time"]
    )

    assert result.output == "The time is now."
    assert result.model == "gemini-2.5-flash"
    assert [c.name for c in result.tool_calls] == ["get_current_time"]
    # The tool actually executed (a real ISO timestamp), rather than being fabricated.
    assert "T" in result.tool_calls[0].result


def test_run_agent_wraps_provider_failure(monkeypatch):
    pytest.importorskip("google.genai")

    provider = GeminiProvider()

    def failing_generate(**kwargs):
        raise RuntimeError("network down")

    fake = SimpleNamespace(
        chats=SimpleNamespace(
            create=lambda **kwargs: SimpleNamespace(
                send_message=failing_generate
            )
        )
   )
    monkeypatch.setattr(provider, "_build_client", lambda: fake)

    # A transport failure becomes ProviderError (-> 502), never a fake completion.
    with pytest.raises(ProviderError):
        provider.run_agent("You are helpful.", None, "hi", [])
