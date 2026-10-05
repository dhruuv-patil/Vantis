"""Service-to-service authentication for the AI endpoints.

The AI service is never exposed to the browser — only the Spring backend calls
it, presenting the shared ``AI_SERVICE_TOKEN`` in the ``X-Service-Token`` header.
This dependency enforces that on every ``/ai/*`` route.

When no token is configured on this service the check is skipped (local dev
convenience); as soon as a token is set it is required and compared in constant
time. The token itself is never logged or echoed.
"""

import hmac

from fastapi import Header, HTTPException, status

from app.config import get_settings


def require_service_token(x_service_token: str | None = Header(default=None)) -> None:
    settings = get_settings()
    expected = settings.ai_service_token.strip()
    if not expected:
        if settings.ai_allow_unauthenticated:
            return  # explicit local-dev opt-in

    # Fail closed: a missing secret must never silently open the LLM endpoints.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI service token is not configured.",
        )
    presented = (x_service_token or "").strip()
    if not presented or not hmac.compare_digest(presented, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing service token.",
        )
