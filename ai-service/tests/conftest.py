"""Test defaults: the suite exercises routes without a service token, which the
service now refuses unless explicitly opted into (local development only)."""

import os

os.environ.setdefault("AI_ALLOW_UNAUTHENTICATED", "true")
os.environ.setdefault("AI_SERVICE_TOKEN", "")