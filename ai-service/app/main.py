"""Vantis AI service — FastAPI entry point.

M0 exposed only health. M4 adds workflow generation (`/ai/generate-workflow`)
and agent execution (`/ai/agent/run`), both backed by a pluggable LLM provider
(Google Gemini by default; see `app.providers`) and bring-your-own-key: with no
key configured they report "not configured" rather than fabricating output.
"""

from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routes import ai as ai_routes


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(
        title="Vantis AI Service",
        version=settings.version,
        description="AI workflow generation and agents for Vantis "
        "(Google Gemini, bring-your-own-key).",
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
        return {
            "status": "ok",
            "service": settings.service_name,
            "version": settings.version,
            # Honest signal to the backend/UI without leaking the key.
            "ai_configured": settings.ai_configured,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    app.include_router(ai_routes.router)

    return app


app = create_app()
