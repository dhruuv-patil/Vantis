"""Property and fuzz tests ensuring the validator never crashes on arbitrary malformed inputs."""

import pytest
import random
import string

from app.validator import validate_workflow_graph
from app.workflow_generator import extract_json_payload


def _random_string(length: int = 10) -> str:
    return "".join(random.choices(string.ascii_letters + string.digits + string.punctuation, k=length))


def _generate_fuzz_node():
    return {
        "id": _random_string(8),
        "type": random.choice(["manual_trigger", "http_request", "condition", "invalid_type", 123, None, {}]),
        "label": _random_string(15),
        "config": {
            _random_string(5): random.choice([_random_string(20), random.randint(-1000, 100000), [1, 2, 3], {"nested": True}, None])
            for _ in range(random.randint(0, 5))
        },
    }


def _generate_fuzz_edge():
    return {
        "id": _random_string(8),
        "source": random.choice([_random_string(8), None, 456]),
        "target": random.choice([_random_string(8), None, 789]),
        "sourceHandle": random.choice(["out", "true", "false", _random_string(6), None]),
    }


@pytest.mark.parametrize("iteration", range(50))
def test_fuzz_arbitrary_structures_never_crash(iteration: int):
    fuzz_payload = {
        "nodes": [_generate_fuzz_node() for _ in range(random.randint(0, 10))],
        "edges": [_generate_fuzz_edge() for _ in range(random.randint(0, 15))],
        "notes": _random_string(30) if random.random() > 0.5 else None,
    }

    try:
        report = validate_workflow_graph(fuzz_payload)
        # Result must always be a valid ValidationResult instance
        assert hasattr(report, "valid")
        assert isinstance(report.issues, list)
    except Exception as exc:
        pytest.fail(f"Validator crashed on fuzz input with exception {type(exc).__name__}: {exc}")


@pytest.mark.parametrize(
    "corrupt_input",
    [
        None,
        "",
        [],
        12345,
        3.14159,
        True,
        {"nodes": None, "edges": None},
        {"nodes": "not-a-list", "edges": 123},
        {"nodes": [None, 123, "string"], "edges": [None]},
        {"nodes": [{"id": None, "type": None}], "edges": [{"source": None, "target": None}]},
        {"nodes": [{"id": "", "type": ""}], "edges": []},
        {"nodes": [{"id": "   ", "type": "   "}], "edges": []},
    ],
)
def test_fuzz_edge_case_inputs(corrupt_input):
    report = validate_workflow_graph(corrupt_input)
    assert report.valid is False
    assert len(report.errors) > 0
