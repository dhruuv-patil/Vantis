"""Regression: the /ai/* guard must fail closed (audit F-12)."""

from types import SimpleNamespace

from fastapi.testclient import TestClient

import app.security as security
from app.main import app


client = TestClient(app)
BODY = {"prompt": "hello"}


def _settings(token: str, allow: bool):
    return lambda: SimpleNamespace(
        ai_service_token=token,
        ai_allow_unauthenticated=allow,
    )


def test_no_token_and_no_optin_refuses_every_call(monkeypatch):
    monkeypatch.setattr(
        security,
        "get_settings",
        _settings("", False),
    )

    res = client.post(
        "/ai/generate-workflow",
        json=BODY,
    )

    assert res.status_code == 503


def test_no_token_with_explicit_optin_allows_dev(monkeypatch):
    monkeypatch.setattr(
        security,
        "get_settings",
        _settings("", True),
    )

    res = client.post(
        "/ai/generate-workflow",
        json=BODY,
    )

    assert res.status_code != 503
    assert res.status_code != 401


def test_token_required_when_configured(monkeypatch):
    monkeypatch.setattr(
        security,
        "get_settings",
        _settings("s3cret-token", False),
    )

    assert (
        client.post(
            "/ai/generate-workflow",
            json=BODY,
        ).status_code
        == 401
    )

    assert (
        client.post(
            "/ai/generate-workflow",
            json=BODY,
            headers={"X-Service-Token": "wrong"},
        ).status_code
        == 401
    )

    ok = client.post(
        "/ai/generate-workflow",
        json=BODY,
        headers={"X-Service-Token": "s3cret-token"},
    )

    assert ok.status_code not in (401, 503)