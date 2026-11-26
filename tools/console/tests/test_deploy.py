import json

import pytest

from lessons_console.deploy import NoRedirect, Refusal, expected_revision, origin, warmup


@pytest.fixture
def checkout(tmp_path):
    source = tmp_path / "server/app"
    source.mkdir(parents=True)
    (source / "db.py").write_text('EXPECTED_REVISION = "0019"\n')
    return tmp_path


def test_warmup_distinguishes_healthy_drift_and_database_down(checkout):
    cases = [(200, {"status": "ok", "schema": "0019"}, "ready"),
             (200, {"status": "degraded", "schema": "0018", "expected_schema": "0019"}, "mismatch"),
             (503, {"status": "down"}, "unknown")]
    for code, body, compatibility in cases:
        body["detail"] = "never-display-this-response-body"
        calls = []
        result = warmup(checkout, "https://production.example", lambda url, calls=calls, code=code, body=body: calls.append(url) or (code, json.dumps(body).encode()))
        assert result["compatibility"] == compatibility
        assert result["status"] == body["status"]
        assert "detail" not in result and "never-display" not in json.dumps(result)
        assert calls == ["https://production.example/api/v1/warmup"]


def test_warmup_refuses_credentials_redirects_and_unbounded_or_bad_bodies(checkout):
    for value in ("http://production.example", "https://user:password@host", "https://host/path", "https://host?query=secret"):
        with pytest.raises(Refusal):
            origin(value)
    assert NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere") is None
    for answer in ((302, b"redirect body"), (200, b"x" * 65537), (200, b"not-json"),
                   (200, b'{"status":"ok","schema":"password with whitespace"}')):
        with pytest.raises(Refusal):
            warmup(checkout, "https://production.example", lambda url, answer=answer: answer)


def test_checkout_revision_is_unique_literal_without_import(checkout, root):
    assert expected_revision(root)
    assert expected_revision(checkout) == "0019"
    source = checkout / "server/app/db.py"
    for content in ('EXPECTED_REVISION = get_revision()\n',
                    'EXPECTED_REVISION = "0019"\nEXPECTED_REVISION += "a"\n',
                    'if True:\n EXPECTED_REVISION = "0019"\n'):
        source.write_text(content)
        with pytest.raises(Refusal):
            expected_revision(checkout)
