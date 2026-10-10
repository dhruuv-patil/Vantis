"""Comprehensive tests for invalid workflows rejected by the validation pipeline."""

import pytest

from app.validator import validate_workflow_graph


def test_reject_malformed_root():
    res = validate_workflow_graph("not a dict")
    assert res.valid is False
    assert any(e.code == "MALFORMED_ROOT_OBJECT" for e in res.errors)


def test_reject_missing_trigger():
    workflow = {
        "nodes": [
            {
                "id": "step1",
                "type": "http_request",
                "config": {"method": "GET", "url": "https://api.example.com"},
            }
        ],
        "edges": [],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "NO_TRIGGER" for e in res.errors)


def test_reject_multiple_triggers():
    workflow = {
        "nodes": [
            {"id": "t1", "type": "manual_trigger", "config": {}},
            {"id": "t2", "type": "webhook_trigger", "config": {}},
            {
                "id": "step1",
                "type": "http_request",
                "config": {"method": "GET", "url": "https://api.example.com"},
            },
        ],
        "edges": [
            {"source": "t1", "target": "step1"},
            {"source": "t2", "target": "step1"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "MULTIPLE_TRIGGERS" for e in res.errors)


def test_reject_duplicate_node_ids():
    workflow = {
        "nodes": [
            {"id": "dup", "type": "manual_trigger", "config": {}},
            {"id": "dup", "type": "notification", "config": {"channel": "slack", "message": "hello"}},
        ],
        "edges": [],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "DUPLICATE_NODE_ID" for e in res.errors)


def test_reject_unknown_node_type():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {"id": "custom", "type": "execute_python_script", "config": {"code": "print(1)"}},
        ],
        "edges": [{"source": "t", "target": "custom"}],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "UNKNOWN_NODE_TYPE" for e in res.errors)


def test_reject_unsupported_config_key():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "req",
                "type": "http_request",
                "config": {
                    "method": "GET",
                    "url": "https://api.example.com",
                    "hallucinatedKey": "unsupported",
                },
            },
        ],
        "edges": [{"source": "t", "target": "req"}],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "UNSUPPORTED_CONFIG_KEY" for e in res.errors)


def test_reject_dangling_edge():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
        ],
        "edges": [
            {"source": "t", "target": "nonexistent_node"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "EDGE_DANGLING" for e in res.errors)


def test_reject_self_loop():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "step",
                "type": "http_request",
                "config": {"method": "GET", "url": "https://api.example.com"},
            },
        ],
        "edges": [
            {"source": "t", "target": "step"},
            {"source": "step", "target": "step"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "SELF_LOOP_DETECTED" for e in res.errors)


def test_reject_cycle():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {"id": "a", "type": "delay", "config": {"seconds": 5}},
            {"id": "b", "type": "delay", "config": {"seconds": 5}},
        ],
        "edges": [
            {"source": "t", "target": "a"},
            {"source": "a", "target": "b"},
            {"source": "b", "target": "a"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "CYCLE_DETECTED" for e in res.errors)


def test_reject_unreachable_orphan_node():
    # A -> B, with C disconnected
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "step1",
                "type": "notification",
                "config": {"channel": "slack", "message": "hi"},
            },
            {
                "id": "step_orphan",
                "type": "notification",
                "config": {"channel": "slack", "message": "alone"},
            },
        ],
        "edges": [
            {"source": "t", "target": "step1"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "UNREACHABLE_NODE" and e.node_id == "step_orphan" for e in res.errors)


def test_reject_missing_branch_on_condition():
    # Condition node with only "true" branch wired, "false" disappears
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "cond",
                "type": "condition",
                "config": {"expression": "true"},
            },
            {
                "id": "on_true",
                "type": "notification",
                "config": {"channel": "slack", "message": "true path"},
            },
        ],
        "edges": [
            {"source": "t", "target": "cond"},
            {"source": "cond", "target": "on_true", "sourceHandle": "true"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "MISSING_BRANCH_TARGET" for e in res.errors)


def test_reject_missing_approval_branch():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "gate",
                "type": "human_approval",
                "config": {"prompt": "Confirm?"},
            },
            {
                "id": "proceed",
                "type": "notification",
                "config": {"channel": "slack", "message": "done"},
            },
        ],
        "edges": [
            {"source": "t", "target": "gate"},
            {"source": "gate", "target": "proceed", "sourceHandle": "approved"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "APPROVAL_BRANCH_MISSING" for e in res.errors)


def test_reject_undefined_variable_reference():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "step1",
                "type": "notification",
                "config": {"channel": "slack", "message": "Hello {{nonexistent_node.output}}"},
            },
        ],
        "edges": [
            {"source": "t", "target": "step1"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "UNDEFINED_REFERENCE" for e in res.errors)


def test_reject_out_of_scope_variable_reference():
    # step1 attempts to reference step2 which runs AFTER step1
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "step1",
                "type": "notification",
                "config": {"channel": "slack", "message": "Future data: {{step2.body}}"},
            },
            {
                "id": "step2",
                "type": "http_request",
                "config": {"method": "GET", "url": "https://api.example.com"},
            },
        ],
        "edges": [
            {"source": "t", "target": "step1"},
            {"source": "step1", "target": "step2"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "OUT_OF_SCOPE_REFERENCE" for e in res.errors)


def test_reject_hardcoded_secrets():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "call_api",
                "type": "http_request",
                "config": {
                    "method": "POST",
                    "url": "https://api.openai.com/v1/chat",
                    "headers": "{\"Authorization\": \"Bearer sk-proj-1234567890abcdef1234567890abcdef\"}",
                },
            },
        ],
        "edges": [{"source": "t", "target": "call_api"}],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "CREDENTIAL_LEAK_DETECTED" for e in res.errors)


def test_reject_ssrf_localhost_and_private_ips():
    for target_url in [
        "http://localhost:8080/admin",
        "http://127.0.0.1:9000",
        "http://169.254.169.254/latest/meta-data/",
        "http://192.168.1.5/internal",
        "http://10.0.0.1/secrets",
        "http://host.docker.internal:20128",
    ]:
        workflow = {
            "nodes": [
                {"id": "t", "type": "manual_trigger", "config": {}},
                {
                    "id": "req",
                    "type": "http_request",
                    "config": {"method": "GET", "url": target_url},
                },
            ],
            "edges": [{"source": "t", "target": "req"}],
        }
        res = validate_workflow_graph(workflow)
        assert res.valid is False, f"Should reject SSRF URL: {target_url}"
        assert any(e.code == "SSRF_TARGET_BLOCKED" for e in res.errors)


def test_reject_infinite_retries():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "retry_node",
                "type": "http_retry",
                "config": {
                    "method": "GET",
                    "url": "https://api.example.com",
                    "maxAttempts": 9999,
                },
            },
        ],
        "edges": [{"source": "t", "target": "retry_node"}],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "INVALID_RETRY_POLICY" for e in res.errors)


def test_reject_unsafe_script_in_expression():
    workflow = {
        "nodes": [
            {"id": "t", "type": "manual_trigger", "config": {}},
            {
                "id": "cond",
                "type": "condition",
                "config": {"expression": "__import__('os').system('rm -rf /') == 0"},
            },
            {
                "id": "stop",
                "type": "stop_fail",
                "config": {"mode": "stop", "message": "halt"},
            },
            {
                "id": "ok",
                "type": "notification",
                "config": {"channel": "slack", "message": "ok"},
            },
        ],
        "edges": [
            {"source": "t", "target": "cond"},
            {"source": "cond", "target": "stop", "sourceHandle": "true"},
            {"source": "cond", "target": "ok", "sourceHandle": "false"},
        ],
    }
    res = validate_workflow_graph(workflow)
    assert res.valid is False
    assert any(e.code == "UNSAFE_EXPRESSION" for e in res.errors)
