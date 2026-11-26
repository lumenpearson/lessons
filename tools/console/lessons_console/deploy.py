"""A bounded diagnostic read. This module cannot deploy anything."""

import ast
import json
import queue
import re
import threading
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class Refusal(ValueError):
    """An intentionally public, value-free explanation of a rejected operation."""


def origin(value: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 512 or value != value.strip():
        raise Refusal("Configure an explicit HTTPS production origin.")
    if any(ord(character) < 33 for character in value) or "\\" in value:
        raise Refusal("Production origin contains invalid characters.")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        raise Refusal("Production origin is malformed.") from None
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment or "%" in parsed.netloc
            or not re.fullmatch(r"[A-Za-z0-9.-]+", parsed.hostname)
            or port == 0):
        raise Refusal("Use an HTTPS origin without credentials, path, query or fragment.")
    return "https://" + parsed.hostname.lower() + (f":{port}" if port and port != 443 else "")


def expected_revision(root: Path) -> str:
    module = ast.parse((root / "server/app/db.py").read_text(encoding="utf-8"))
    assignments = []
    for node in ast.walk(module):
        targets = node.targets if isinstance(node, ast.Assign) else [node.target] if isinstance(node, ast.AnnAssign) else []
        if any(isinstance(target, ast.Name) and target.id == "EXPECTED_REVISION" for target in targets):
            assignments.append(node)
    stores = [node for node in ast.walk(module) if isinstance(node, ast.Name)
              and node.id == "EXPECTED_REVISION" and isinstance(node.ctx, ast.Store)]
    if len(assignments) != 1 or len(stores) != 1:
        raise Refusal("Checkout EXPECTED_REVISION must have one literal assignment.")
    assignment = assignments[0]
    if (assignment not in module.body or not isinstance(assignment, ast.Assign)
            or len(assignment.targets) != 1 or not isinstance(assignment.value, ast.Constant)
            or not isinstance(assignment.value.value, str)
            or not re.fullmatch(r"[0-9A-Za-z_-]{1,64}", assignment.value.value)):
        raise Refusal("Checkout EXPECTED_REVISION is not one top-level literal string.")
    return assignment.value.value


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _fetch_warmup(url: str) -> tuple[int, bytes]:
    # Proxies and redirects cannot introduce a credential or another origin.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        response = opener.open(request, timeout=8)
    except HTTPError as error:
        response = error
    except (OSError, URLError):
        raise Refusal("Production warmup could not be reached over HTTPS.") from None
    with response:
        if response.status not in {200, 503}:
            raise Refusal("Production warmup returned an unsupported HTTP status; no body is displayed.")
        body = response.read(65537)
        if len(body) > 65536:
            raise Refusal("Production warmup exceeded the response size limit.")
        return response.status, body


def fetch_warmup(url: str) -> tuple[int, bytes]:
    # Each request lives in the owned operation subprocess. A daemon worker gives
    # the complete read a deadline even if a peer trickles bytes between timeouts.
    answers = queue.Queue(maxsize=1)
    def request() -> None:
        try:
            answers.put(_fetch_warmup(url))
        except Exception:  # noqa: BLE001 -- response/error bodies must never escape this boundary.
            answers.put(Refusal("Production warmup failed; no response body is displayed."))
    threading.Thread(target=request, daemon=True).start()
    try:
        answer = answers.get(timeout=8)
    except queue.Empty:
        raise Refusal("Production warmup exceeded its eight-second deadline.") from None
    if isinstance(answer, Refusal):
        raise answer
    return answer


def warmup(root: Path, configured_origin: str, fetch=fetch_warmup) -> dict[str, str]:
    selected = origin(configured_origin)
    revision = expected_revision(root)
    status_code, body = fetch(selected + "/api/v1/warmup")
    if status_code not in {200, 503} or len(body) > 65536:
        raise Refusal("Production warmup response was refused.")
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        raise Refusal("Production warmup did not return supported JSON.") from None
    if not isinstance(data, dict) or data.get("status") not in {"ok", "degraded", "down"}:
        raise Refusal("Production warmup has no recognized status.")
    state = data["status"]
    if (status_code == 503) != (state == "down"):
        raise Refusal("Production warmup HTTP status and condition disagree.")
    result = {"origin": selected, "status": state, "checkout_expected": revision}
    for name in ("schema", "expected_schema"):
        value = data.get(name)
        if value is not None:
            if not isinstance(value, str) or not re.fullmatch(r"[0-9A-Za-z_-]{1,64}", value):
                raise Refusal("Production warmup has an unsupported schema field.")
            result[name] = value
    if state == "ok" and "schema" not in result:
        raise Refusal("Healthy production warmup omitted its schema.")
    if state == "down" and ("schema" in result or "expected_schema" in result):
        raise Refusal("Database-down warmup unexpectedly supplied a schema.")
    schema = result.get("schema")
    result["compatibility"] = "unknown" if schema is None or schema == "unknown" else "ready" if schema == revision else "mismatch"
    return result
