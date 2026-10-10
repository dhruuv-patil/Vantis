"""Provider registry — selects the active LLM provider from settings.

To add a provider: implement :class:`app.providers.base.LLMProvider` in a new
module and map its name here. Everything upstream (routes, health) is
provider-agnostic, so nothing else changes.
"""

from functools import lru_cache

from app.config import get_settings
from app.providers.base import AgentResult, LLMProvider, ProviderError, ToolInvocation
from app.providers.gemini import GeminiProvider
from app.providers.omniroute import OmniRouteProvider

__all__ = [
    "AgentResult",
    "LLMProvider",
    "ProviderError",
    "ToolInvocation",
    "get_provider",
]


@lru_cache
def get_provider() -> LLMProvider:
    """The active provider, chosen by AI_PROVIDER."""
    name = get_settings().ai_provider.strip().lower()

    if name == "omniroute":
        return OmniRouteProvider()

    if name == "gemini":
        return GeminiProvider()

    raise ProviderError(f"Unsupported AI provider: {name!r}. Supported providers are 'omniroute' and 'gemini'.")