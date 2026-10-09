"""The diary's registry as a table: every question asked of a provider key reads its row.

``providers/diary/registry.py`` chose a provider with an ``if`` per key, and
four more places did the same — the binding, the scope a child's corrections
are filed under, which sessions the tick keeps alive, and the bot's binding
screen (``docs/specs/2026-10-05-server-v2-design.md``, decision 12). These hold
the table's answers, that a provider declares a feature exactly when its
connection asks the diary for it, and that a session of a provider this
deployment does not know is read by nobody (#389).
"""

from __future__ import annotations

import json
import subprocess
import sys
from collections.abc import Callable
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select

from app.contract.lessons.v2.diary_pb import DiaryFeature, SignInMethod
from app.crypto import seal
from app.main import app
from app.models import DiarySession, SchoolClass
from app.providers.diary import registry
from app.providers.diary.errors import DiaryError
from app.providers.diary.registry import Feature, Needs, Scope, SignIn
from app.providers.netschool import client as nsclient
from app.providers.petersburg import client as pbclient
from app.security import hash_token
from app.services import diary_corrections, diary_keepalive

SERVER = Path(__file__).resolve().parents[1]
DAY = date(2026, 9, 14)

#: The connection read behind each feature a method of v2 serves.
READS: dict[Feature, Callable[[Any], Any]] = {
    Feature.SCHEDULE: lambda connection: connection.schedule(1, DAY, DAY),
    Feature.HOMEWORK: lambda connection: connection.homework(1, DAY, DAY),
    Feature.MARKS: lambda connection: connection.marks(1, DAY, DAY),
    Feature.PERIODS: lambda connection: connection.periods(1),
    Feature.SUBJECTS: lambda connection: connection.subjects(1, 1),
    Feature.TEACHERS: lambda connection: connection.teachers(1),
    Feature.TURNSTILE: lambda connection: connection.attendance(1),
}

#: A credential each provider opens, with the school year «Сетевой город»
#: clips its walks to, so that a read asks for one week and nothing else.
CREDENTIALS = {
    registry.PETERSBURG: "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbg",
    registry.NETSCHOOL: json.dumps(
        {
            "v": nsclient.CREDENTIAL_VERSION,
            "region": "zabaikalsky",
            "school_id": 42,
            "at": "56574745368264517434263",
            "cookies": {"NSSESSIONID": "sess"},
            "year_id": 2026,
            "year_start": "2026-09-01",
            "year_end": "2027-05-31",
        }
    ),
}


@pytest.fixture
def asked(monkeypatch) -> list[httpx.Request]:
    """Both diaries, answering every request with an empty JSON object and
    recording it: what matters here is whether a read asks at all."""
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={})

    async def petersburg() -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=pbclient.BASE_URL, transport=httpx.MockTransport(handler))

    async def netschool() -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(handler))

    monkeypatch.setattr(pbclient, "shared_client", petersburg)
    monkeypatch.setattr(nsclient, "shared_client", netschool)
    return seen


def test_every_row_names_the_provider_that_answers_to_its_key() -> None:
    assert registry.KEYS == (registry.PETERSBURG, registry.NETSCHOOL)
    for row in registry.TABLE:
        assert registry.row_for(row.key) is row
        assert row.provider().key == row.key
        assert type(registry.provider_for(row.key)) is type(row.provider())
        # A row that needs a region has an allow-list to take it from, and
        # files its children per regional server; one that does not, neither.
        regional = row.needs is Needs.REGION_AND_SCHOOL
        assert (row.regions is not None) is regional
        assert (row.scope is Scope.REGIONAL_SERVER) is regional
    for unknown in (None, "", "dnevnik-ru", "PETERSBURG"):
        assert registry.row_for(unknown) is None
        assert registry.provider_for(unknown) is None


def test_a_provider_and_its_allow_list_are_imported_only_when_asked_for() -> None:
    """In a fresh interpreter, since this suite's own process has imported both
    providers long since: the table is read on every request, and neither
    provider's client may land on the cold start of one that does not use it."""
    probe = (
        "import json, sys\n"
        "from app.providers.diary import registry\n"
        "names = ('app.providers.petersburg.provider', 'app.providers.netschool.provider',"
        " 'app.providers.netschool.regions')\n"
        "first = [name in sys.modules for name in names]\n"
        "registry.row_for('netschool').listed_regions()\n"
        "second = [name in sys.modules for name in names]\n"
        "registry.row_for('petersburg').provider()\n"
        "third = [name in sys.modules for name in names]\n"
        "print(json.dumps([first, second, third]))\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", probe], cwd=SERVER, capture_output=True, text=True, check=True
    )
    first, second, third = json.loads(result.stdout.strip().splitlines()[-1])
    assert first == [False, False, False]
    assert second == [False, False, True]
    assert third == [True, False, True]


def test_the_features_and_the_ways_in_are_the_contract_s_names() -> None:
    assert {feature.name for feature in Feature} == {
        value.name for value in DiaryFeature if value is not DiaryFeature.UNSPECIFIED
    }
    assert {method.name for method in SignIn} == {
        value.name for value in SignInMethod if value is not SignInMethod.UNSPECIFIED
    }
    for row in registry.TABLE:
        assert row.sign_in == (SignIn.PASSWORD,), row.key


@pytest.mark.parametrize("key", registry.KEYS)
async def test_a_provider_declares_a_feature_exactly_when_its_connection_asks_the_diary(
    asked, key
) -> None:
    """Ruling 104: a feature is undeclared only when the provider never
    implements it. «Сетевой город»'s connection answers subjects, teachers and
    the turnstile with a constant empty list and asks nothing; an empty answer
    from the diary would still be an answer."""
    row = registry.row_for(key)
    assert row is not None
    connection = row.provider().open(CREDENTIALS[key])
    asks: set[Feature] = set()
    for feature, read in READS.items():
        before = len(asked)
        try:
            await read(connection)
        except DiaryError:
            pass  # an empty object is no answer a mapper reads; it was asked
        if len(asked) > before:
            asks.add(feature)
    assert asks == row.features & set(READS)
    # What no method of v2 reads yet is declared by nobody.
    assert not row.features & {Feature.ATTENDANCE, Feature.MEAL_ACCOUNT, Feature.FINAL_MARKS}


def test_netschool_declares_four_and_petersburg_seven() -> None:
    petersburg = registry.row_for(registry.PETERSBURG)
    netschool = registry.row_for(registry.NETSCHOOL)
    assert petersburg is not None and netschool is not None
    assert netschool.features == {
        Feature.SCHEDULE,
        Feature.HOMEWORK,
        Feature.MARKS,
        Feature.PERIODS,
    }
    assert petersburg.features == netschool.features | {
        Feature.SUBJECTS,
        Feature.TEACHERS,
        Feature.TURNSTILE,
    }


def test_a_binding_is_validated_by_its_row() -> None:
    def bound(**columns: Any) -> registry.Binding | None:
        return registry.binding(SchoolClass(name="9А", join_code="ROW1", **columns))

    petersburg = bound(diary_provider="petersburg", diary_region="samara", diary_school_id=7)
    assert petersburg is not None
    # One server: whatever else the class holds, the binding needs nothing more.
    assert (petersburg.provider.key, petersburg.region, petersburg.school_id) == (
        "petersburg",
        None,
        None,
    )
    netschool = bound(
        diary_provider="netschool",
        diary_region="samara",
        diary_school_id=7,
        diary_school_name="Школа № 7",
    )
    assert netschool is not None
    assert (netschool.region, netschool.school_id, netschool.school_name) == (
        "samara",
        7,
        "Школа № 7",
    )
    for unusable in (
        {"diary_provider": "netschool", "diary_region": "samara"},
        {"diary_provider": "netschool", "diary_region": "tula", "diary_school_id": 7},
        {"diary_provider": "netschool", "diary_region": "moscow", "diary_school_id": 7},
        {"diary_provider": "dnevnik-ru"},
        {"diary_provider": None},
    ):
        assert bound(**unusable) is None, unusable


def test_a_child_s_corrections_are_scoped_as_its_row_says() -> None:
    """The two shapes ``child_scope`` has always made, byte for byte, now read
    from the row; a password-less region still names its server, since its
    sessions' corrections are filed under it."""
    scope = diary_corrections.child_scope
    assert scope("petersburg", None) == scope(None, "samara") == "CHILD:petersburg"
    assert scope("netschool", "zabaikalsky") == "CHILD:netschool:region.zabedu.ru"
    assert scope("netschool", "tula") == "CHILD:netschool:sgo1.edu71.ru"
    for provider, region in (("netschool", "moscow"), ("netschool", None), ("dnevnik-ru", None)):
        with pytest.raises(diary_corrections.UnknownDiaryServer):
            scope(provider, region)


async def test_the_tick_claims_only_the_sessions_of_a_provider_it_keeps_alive(session) -> None:
    assert registry.kept_alive() == ("netschool",)
    for provider in ("netschool", "petersburg", None, "dnevnik-ru"):
        session.add(
            DiarySession(
                token_hash=hash_token(f"kept-{provider}"),
                upstream_token=seal("{}"),
                login="parent",
                provider=provider,
                region="zabaikalsky" if provider == "netschool" else None,
            )
        )
    await session.commit()
    claims = await diary_keepalive._claim(session)
    assert [claim.provider for claim in claims] == ["netschool"]


async def test_a_session_of_a_provider_this_deployment_does_not_know_is_read_by_nobody(
    session, asked
) -> None:
    """#389. A provider is a value, not a migration, so a deployment rolled back
    past the release that added one still holds its sessions. The reader fell
    back to Petersburg and sent the other diary's credential there. Now the
    token is refused as v1's ``current_diary`` refuses any unknown token, and
    the row is left for the release that can read it."""
    session.add(
        DiarySession(
            token_hash=hash_token("third-diary"),
            upstream_token=seal("third-diary-cookie"),
            login="parent",
            provider="dnevnik-ru",
        )
    )
    await session.commit()
    async with httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as v1:
        answer = await v1.get(
            "/api/v1/diary/students", headers={"Authorization": "Bearer third-diary"}
        )
    assert (answer.status_code, answer.json()["detail"]) == (401, "Diary session is not valid")
    assert asked == []
    row = await session.scalar(select(DiarySession))
    await session.refresh(row)
    assert row.expired_at is None


async def test_the_bot_binds_a_diary_that_needs_nothing_at_once_in_its_own_name(
    session, school_class, FakeCallback, FakeEditable
) -> None:
    """The provider step reads the row: one that needs nothing is bound, and
    the line in the journal and the alert name it in its own genitive; an
    unknown key is refused and binds nothing."""
    from app.bot.handlers.manage.diary_binding import class_diary_provider
    from app.models import AuditEntry, Role

    callback = FakeCallback(message=FakeEditable())
    await class_diary_provider(
        callback, SimpleNamespace(value="petersburg"), session, school_class, Role.ADMIN
    )
    assert school_class.diary_provider == "petersburg"
    line = await session.scalar(select(AuditEntry.summary))
    assert line == "привязан дневник Санкт-Петербурга"

    refused = FakeCallback(message=FakeEditable())
    school_class.diary_provider = None
    await class_diary_provider(
        refused, SimpleNamespace(value="dnevnik-ru"), session, school_class, Role.ADMIN
    )
    assert school_class.diary_provider is None
    assert refused.alerted
