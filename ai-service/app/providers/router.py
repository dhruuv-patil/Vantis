"""Provider router with fallback support (OmniRoute primary + Gemini fallback).

Ensures both providers return normalized strings to the workflow generator,
and ensures provider failure fails over safely without bypassing any validation.
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings
from app.providers.base import AgentResult, LLMProvider, ProviderError
from app.providers.gemini import GeminiProvider
from app.providers.omniroute import OmniRouteProvider

logger = logging.getLogger("flowops.ai")


class ProviderRouter:
    """Dispatches to the primary provider and gracefully falls back to secondary when available."""

    def __init__(self, primary: LLMProvider | None = None, fallback: LLMProvider | None = None) -> None:
        self.settings = get_settings()
        name = self.settings.ai_provider.strip().lower()

        if primary is not None:
            self.primary = primary
            if fallback is not None:
                self.fallback = fallback
            else:
                pname = getattr(self.primary, "name", "").lower()
                if pname == "omniroute":
                    self.fallback = GeminiProvider() if GeminiProvider().is_configured() else None
                elif pname == "gemini":
                    self.fallback = OmniRouteProvider() if OmniRouteProvider().is_configured() else None
                else:
                    self.fallback = None
        else:
            if name == "omniroute":
                self.primary = OmniRouteProvider()
                self.fallback = GeminiProvider() if GeminiProvider().is_configured() else None
            elif name == "gemini":
                self.primary = GeminiProvider()
                self.fallback = OmniRouteProvider() if OmniRouteProvider().is_configured() else None
            else:
                self.primary = OmniRouteProvider()
                self.fallback = None

    @property
    def name(self) -> str:
        return f"router({self.primary.name})"

    def is_configured(self) -> bool:
        return self.primary.is_configured() or (self.fallback is not None and self.fallback.is_configured())

    def default_model(self) -> str:
        if self.primary.is_configured():
            return self.primary.default_model()
        if self.fallback and self.fallback.is_configured():
            return self.fallback.default_model()
        return self.primary.default_model()

    def generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        correlation_id: str | None = None,
    ) -> tuple[str, str]:
        """Generate JSON text, attempting primary provider first, then fallback.

        Returns (json_text, provider_name_used).
        Raises ProviderError if no provider could fulfill the request.
        """
        cid_tag = f"[{correlation_id}] " if correlation_id else ""

        # 1. Try primary provider if configured
        if self.primary.is_configured():
            try:
                content = self.primary.generate_json(system_prompt, user_prompt, model)
                return content, self.primary.name
            except ProviderError as exc:
                logger.warning(
                    "%sPrimary provider %s failed: %s",
                    cid_tag,
                    self.primary.name,
                    str(exc),
                )
                if not (self.fallback and self.fallback.is_configured()):
                    raise

        # 2. Try fallback provider if configured
        if self.fallback and self.fallback.is_configured():
            logger.info(
                "%sFalling back to secondary provider %s",
                cid_tag,
                self.fallback.name,
            )
            try:
                content = self.fallback.generate_json(system_prompt, user_prompt, model)
                return content, self.fallback.name
            except ProviderError as exc:
                logger.warning(
                    "%sFallback provider %s failed: %s",
                    cid_tag,
                    self.fallback.name,
                    str(exc),
                )
                raise ProviderError(
                    f"Both primary ({self.primary.name}) and fallback ({self.fallback.name}) AI providers failed."
                ) from exc

        raise ProviderError(f"AI provider ({self.primary.name}) is not configured.")

    def run_agent(
        self,
        instructions: str,
        model: str | None,
        user_input: str,
        enabled_tools: list[str],
    ) -> AgentResult:
        if self.primary.is_configured():
            try:
                return self.primary.run_agent(instructions, model, user_input, enabled_tools)
            except ProviderError as exc:
                logger.warning("Primary provider %s failed for agent run: %s", self.primary.name, str(exc))
                if not (self.fallback and self.fallback.is_configured()):
                    raise

        if self.fallback and self.fallback.is_configured():
            logger.info("Falling back to %s for agent run", self.fallback.name)
            return self.fallback.run_agent(instructions, model, user_input, enabled_tools)

        raise ProviderError("No AI provider is configured for agent execution.")
