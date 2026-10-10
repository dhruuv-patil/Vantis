"""Vantis AI service — FastAPI entry point.

M0 exposed only health. M4 adds workflow generation (`/ai/generate-workflow`)
and agent execution (`/ai/agent/run`), both backed by a pluggable LLM provider
(Google Gemini by default; see `app.providers`) and bring-your-own-key: with no
key configured they report "not configured" rather than fabricating output.
"""

import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routes import ai as ai_routes

logger = logging.getLogger("flowops.ai")
logger.setLevel(logging.INFO)
if not logger.handlers:
    _handler = logging.StreamHandler()
    _handler.setFormatter(logging.Formatter("%(levelname)s:     %(message)s"))
    logger.addHandler(_handler)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    logger.info("AI provider: %s", settings.ai_provider)
    yield


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="Vantis AI Service",
        version=settings.version,
        description="AI workflow generation and agents for Vantis "
        "(bring-your-own-key).",
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health", tags=["health"])
    def health() -> dict:
        current_settings = get_settings()
        return {
            "status": "ok",
            "service": current_settings.service_name,
            "version": current_settings.version,
            "ai_provider": current_settings.ai_provider,
            "provider": current_settings.ai_provider,
            # Honest signal to the backend/UI without leaking the key.
            "ai_configured": current_settings.ai_configured,
            "configured": current_settings.ai_configured,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    app.include_router(ai_routes.router)

    return app


app = create_app()
