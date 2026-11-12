"""The one production probe must be schema-valid and impossible for our issuers."""

import re
from pathlib import Path

from sqlalchemy import func, select

from app import security
from app.contract.lessons.v2.device_pb import CreateDeviceRequest
from app.models import AuditEntry, DeviceInvite, DeviceToken, JoinAttempt
from app.schemas import JoinRequest
from app.services import device_invites


def _native_code() -> str:
    source = (
        Path(__file__).resolve().parents[2]
        / "android/app/src/androidTest/kotlin/com/lumenpearson/lessons/TransportReleaseTest.kt"
    )
    assert source.is_file(), "The bounded release harness must be checked in"
    declaration = re.search(r'const val NativeJoinCode = "([^\"]+)"', source.read_text("utf-8"))
    assert declaration is not None
    assert declaration[1] == "!5A!"
    return declaration[1]


def test_native_code_is_schema_valid_and_neither_issuer_can_produce_it(monkeypatch) -> None:
    code = _native_code()
    assert JoinRequest(code=code).code == code
    assert device_invites.new_join_code is security.new_join_code

    def choose(alphabet: str) -> str:
        assert alphabet.isascii() and alphabet.isalnum()
        assert "!" not in alphabet
        return alphabet[0]

    monkeypatch.setattr(security.secrets, "choice", choose)
    assert "!" not in security.new_join_code()
    assert "!" not in security.new_join_code(device_invites.CODE_LENGTH)


async def test_native_probe_is_one_unknown_attempt_without_creation(v2, session) -> None:
    async def count(model) -> int:
        return await session.scalar(select(func.count()).select_from(model)) or 0

    unchanged = (DeviceToken, AuditEntry, DeviceInvite)
    before = [await count(model) for model in unchanged]
    attempts = await count(JoinAttempt)

    answer = await v2.connect(
        "DeviceService/CreateDevice", CreateDeviceRequest(code=_native_code())
    )

    assert (answer.code, answer.reason) == ("NOT_FOUND", "JOIN_CODE_UNKNOWN")
    assert [await count(model) for model in unchanged] == before
    assert await count(JoinAttempt) == attempts + 1
