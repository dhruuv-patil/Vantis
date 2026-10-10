"""Tests for valid Vantis workflows passing the complete validation pipeline."""

import pytest

from app.validator import normalize_workflow, validate_workflow_graph


def test_valid_simple_linear_workflow():
    workflow = {
        "nodes": [
            {"id": "trigger", "type": "manual_trigger", "config": {}},
            {
                "id": "fetch_data",
                "type": "http_request",
                "config": {
                    "method": "GET",
                    "url": "https://api.example.com/items",
                },
            },
            {
                "id": "notify",
                "type": "notification",
                "config": {
                    "channel": "slack",
                    "message": "Items fetched: {{fetch_data.status}}",
                },
            },
        ],
        "edges": [
            {"source": "trigger", "target": "fetch_data"},
            {"source": "fetch_data", "target": "notify"},
        ],
        "notes": "Fetch data and notify Slack",
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0

    normalized = normalize_workflow(workflow)
    assert len(normalized["nodes"]) == 3
    assert len(normalized["edges"]) == 2
    # Verify deterministic positions assigned
    assert normalized["nodes"][0]["position"]["y"] == 80
    assert normalized["nodes"][1]["position"]["y"] == 220
    assert normalized["nodes"][2]["position"]["y"] == 360


def test_valid_branching_workflow():
    workflow = {
        "nodes": [
            {"id": "hook", "type": "webhook_trigger", "config": {"method": "POST"}},
            {
                "id": "check_status",
                "type": "condition",
                "config": {"expression": "{{hook.body.amount}} > 100"},
            },
            {
                "id": "high_val_alert",
                "type": "notification",
                "config": {"channel": "email", "message": "High value lead arrived"},
            },
            {
                "id": "standard_log",
                "type": "notification",
                "config": {"channel": "slack", "message": "Standard transaction"},
            },
        ],
        "edges": [
            {"source": "hook", "target": "check_status"},
            {"source": "check_status", "target": "high_val_alert", "sourceHandle": "true"},
            {"source": "check_status", "target": "standard_log", "sourceHandle": "false"},
        ],
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0


def test_valid_human_approval_workflow():
    workflow = {
        "nodes": [
            {"id": "trigger", "type": "manual_trigger", "config": {}},
            {
                "id": "approval_gate",
                "type": "human_approval",
                "config": {"prompt": "Approve production deployment?"},
            },
            {
                "id": "deploy_step",
                "type": "http_request",
                "config": {
                    "method": "POST",
                    "url": "https://deploy.example.com/api/deploy",
                },
            },
            {
                "id": "reject_notice",
                "type": "notification",
                "config": {"channel": "slack", "message": "Deployment rejected"},
            },
        ],
        "edges": [
            {"source": "trigger", "target": "approval_gate"},
            {"source": "approval_gate", "target": "deploy_step", "sourceHandle": "approved"},
            {"source": "approval_gate", "target": "reject_notice", "sourceHandle": "rejected"},
        ],
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0


def test_valid_retry_workflow():
    workflow = {
        "nodes": [
            {"id": "trigger", "type": "schedule_trigger", "config": {"cron": "0 9 * * 1-5"}},
            {
                "id": "sync_api",
                "type": "http_retry",
                "config": {
                    "url": "https://api.example.com/sync",
                    "method": "POST",
                    "maxAttempts": 3,
                    "backoffSeconds": 2,
                },
            },
            {
                "id": "notify_done",
                "type": "notification",
                "config": {"channel": "slack", "message": "Sync finished: {{sync_api.status}}"},
            },
        ],
        "edges": [
            {"source": "trigger", "target": "sync_api"},
            {"source": "sync_api", "target": "notify_done"},
        ],
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0


def test_valid_integration_reference():
    workflow = {
        "nodes": [
            {"id": "trigger", "type": "manual_trigger", "config": {}},
            {
                "id": "slack_alert",
                "type": "slack",
                "config": {
                    "message": "Incident reported",
                    "integrationId": "conn_slack_prod",
                },
            },
        ],
        "edges": [
            {"source": "trigger", "target": "slack_alert"},
        ],
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0


def test_valid_ai_agent_workflow():
    workflow = {
        "nodes": [
            {"id": "trigger", "type": "webhook_trigger", "config": {"method": "POST"}},
            {
                "id": "agent_triage",
                "type": "ai_agent",
                "config": {
                    "instructions": "Classify incoming ticket",
                    "input": "{{trigger.body}}",
                },
            },
            {
                "id": "notify_agent",
                "type": "notification",
                "config": {
                    "channel": "slack",
                    "message": "Agent summary: {{agent_triage.output}}",
                },
            },
        ],
        "edges": [
            {"source": "trigger", "target": "agent_triage"},
            {"source": "agent_triage", "target": "notify_agent"},
        ],
    }
    result = validate_workflow_graph(workflow)
    assert result.valid is True
    assert len(result.errors) == 0
