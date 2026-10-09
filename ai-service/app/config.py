"""AI service configuration.

The AI service is isolated from the Spring Boot core. It exposes a
bring-your-own-key interface: when no provider key is configured the AI
endpoints report "not configured" rather than returning fabricated output.

The active LLM provider is pluggable (see :mod:`app.providers`); Google Gemini
is the default. The provider key is read from the environment here and never
logged, never returned to the backend/frontend, and never persisted.
"""

from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    service_name: str = "vantis-ai"
    version: str = "0.1.0"

    # Which LLM backs the AI endpoints. Kept configurable so another provider can
    # be added later (implement app.providers.base.LLMProvider and register it in
    # app.providers.get_provider) without touching the routes.
    ai_provider: str = "omniroute"

    # Google Gemini (bring-your-own-key). Get a key at https://aistudio.google.com/apikey.
    # The default is a current Gemini Flash model suited to the free API tier.
    gemini_api_key: str = ""
    gemini_model: str = "gemini-3.6-flash"

    # OmniRoute (bring-your-own-key).
    omniroute_api_key: str = ""
    omniroute_base_url: str = "http://host.docker.internal:20128/v1"
    omniroute_model: str = "auto/best-chat"

    @field_validator("ai_provider")
    @classmethod
    def validate_provider(cls, v: str) -> str:
        provider = (v or "").strip().lower()
        if not provider:
            raise ValueError("AI_PROVIDER is required and cannot be empty.")
        if provider not in ("omniroute", "gemini"):
            raise ValueError(
                f"Invalid AI_PROVIDER: {v!r}. Supported providers are 'omniroute' and 'gemini'."
            )
        return provider

    # Per-request timeout (seconds) for provider calls.
    provider_timeout_seconds: float = 60.0
    # Upper bound on tool-calling rounds in /ai/agent/run, so a misbehaving model
    # can never loop indefinitely.
    agent_max_iterations: int = 5

    # Shared secret the Spring backend presents when calling this service.
    ai_service_token: str = ""

    # Explicit opt-in to run /ai/* with no service token (local development only).
    # Without a token AND without this flag every /ai/* call is refused.
    ai_allow_unauthenticated: bool = False

    # Allowed callers (the backend). Comma-separated origins.
    cors_origins: str = "http://localhost:8080"

    
    @property
    def ai_configured(self) -> bool:
        """True when the active provider has credentials."""
        provider = self.ai_provider.strip().lower()

        if provider == "gemini":
            return bool(self.gemini_api_key.strip())

        if provider == "omniroute":
            return bool(self.omniroute_base_url.strip() and self.omniroute_api_key.strip())

        return False

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

@lru_cache
def get_settings() -> Settings:
    return Settings()
