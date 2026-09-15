"""Who a class lets in: the shared class code, or a personal one-time code.

A class in ``OPEN`` is what every class has always been — the join code admits
whoever types it. A class in ``INVITE`` stops that code admitting anything and
takes the one-time codes in ``device_invites`` instead, which the bot mints for
somebody it already recognises as a member.

Three things here are worth more than the rest, and each has its own test.

The refusal of a class code in ``INVITE`` is **not** a failed attempt for the
throttle: a class that switches modes would otherwise let everybody still
holding the old code lock the school's NAT out of joining at all.

A personal code is worth exactly one join, and a spent one is refused with the
same 404 as a code that never existed — expired, used and invented are one
answer on purpose, because telling them apart says whether a code was ever
real.

And the column holds ``OPEN``, not ``open``. SQLAlchemy persists a PEP-435 enum
by member *name*; a server default written as ``JoinMode.OPEN.value`` would put
a string on every existing class that the ORM cannot read back, and the first
read of any class — the bot's middleware — would raise ``LookupError``. An
earlier draft of this work had exactly that.
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select, text

from app.api import cron
from app.api.public import join_limiter
from app.config import Settings, get_settings
from app.fsm_storage import FsmRecord  # noqa: F401 - registers fsm_states before create_all
from app.main import app
from app.models import DeviceInvite, DeviceToken, JoinAttempt, JoinMode, SchoolClass
from app.services import device_invites

MEMBER_ID = 4242
CRON_SECRET = "tick-secret"


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
async def invite_class(session) -> SchoolClass:
    """A second class, in «по приглашению», beside the open one in conftest."""
    klass = SchoolClass(name="9Б", join_code="LOCKED1", join_mode=JoinMode.INVITE)
    session.add(klass)
    await session.commit()
    return klass


async def _join(client, code: str) -> httpx.Response:
    return await client.post("/api/v1/join", json={"code": code, "device_name": "pytest"})


async def _attempts(session) -> int:
    return len(list(await session.scalars(select(JoinAttempt))))


# --------------------------------------------------------------------------
# The class code, in each mode
# --------------------------------------------------------------------------


async def test_an_open_class_still_joins_on_the_class_code(client, session, school_class):
    """The regression that matters most: nothing above changed for anybody who
    never touches the new setting."""
    response = await _join(client, "TEST42")

    assert response.status_code == 200, response.text
    assert response.json()["class_id"] == school_class.id

    token = await session.scalar(select(DeviceToken))
    # Joined anonymously, which is what the class code has always meant.
    assert token.telegram_id is None and token.linked_at is None


async def test_an_invite_only_class_refuses_the_class_code(client, session, invite_class):
    """Said plainly, not as «unknown code».

    Whoever is typing this was given it by somebody; «неверный код» sends them
    back to that person, and the sentence names the bot instead.
    """
    response = await _join(client, "LOCKED1")

    assert response.status_code == 403
    assert response.json()["detail"] == "Этот класс принимает только по личному приглашению из бота"
    assert await session.scalar(select(DeviceToken)) is None


async def test_the_invite_only_refusal_is_not_counted_against_the_caller(
    client, session, school_class, invite_class
):
    """A class that switches to invites must not lock the school out.

    The throttle exists to stop somebody walking the code space, and this
    caller has already found a code. Everybody who still had the old one is one
    NAT away from everybody else, so counting these would take the whole school
    off the legitimate class next door with it.
    """
    for _ in range(join_limiter.limit + 5):
        assert (await _join(client, "LOCKED1")).status_code == 403

    assert await _attempts(session) == 0
    assert (await _join(client, "TEST42")).status_code == 200


# --------------------------------------------------------------------------
# The personal code
# --------------------------------------------------------------------------


async def test_a_personal_code_joins_an_invite_only_class(client, session, invite_class):
    """And links the device on the way in.

    That is the half of this feature an admin actually sees: in ``OPEN`` a
    phone joins anonymously and links afterwards by typing a second code into
    the bot, which most people never do. Here the name cannot be missing,
    because the code could not have been minted without it.
    """
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)

    response = await _join(client, code)
    assert response.status_code == 200, response.text
    assert response.json()["class_id"] == invite_class.id

    token = await session.scalar(select(DeviceToken))
    assert token.class_id == invite_class.id
    assert token.telegram_id == MEMBER_ID
    assert token.linked_at is not None

    invite = await session.scalar(select(DeviceInvite))
    await session.refresh(invite)
    assert invite.is_used


async def test_a_personal_code_also_works_while_the_class_is_open(
    client, session, school_class
):
    """The two ways in are not exclusive.

    An admin hands a code to one parent without putting the whole class into
    «по приглашению», and that parent's phone arrives already named.
    """
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=school_class.id)

    assert (await _join(client, code)).status_code == 200
    assert (await _join(client, "TEST42")).status_code == 200

    tokens = list(await session.scalars(select(DeviceToken)))
    assert sorted(token.telegram_id or 0 for token in tokens) == [0, MEMBER_ID]


async def test_a_personal_code_is_worth_exactly_one_join(client, session, invite_class):
    """The second attempt is a 404 **and** a counted failure.

    Deliberate, and the asymmetry with the 403 above is the point: a spent code
    is indistinguishable from one that never existed, so it comes back the same
    way — and a caller replaying codes at this endpoint is the case the
    throttle is for.

    This is the ordinary path and it never reaches ``burn``: ``find_live``
    refuses a row already marked used. The other path — two requests that both
    read it live — is the test above, and that one is *not* counted, because
    losing a race is not a wrong guess.
    """
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)
    assert (await _join(client, code)).status_code == 200

    second = await _join(client, code)
    assert second.status_code == 404
    assert second.json()["detail"] == "Unknown join code"

    assert await _attempts(session) == 1
    assert len(list(await session.scalars(select(DeviceToken)))) == 1


async def test_two_requests_holding_the_same_code_produce_one_phone(session, invite_class):
    """The race «one code, one phone» is actually about.

    Two requests read the code in the same instant — ``find_live`` says live to
    both, because reading is not spending — and the only thing between that and
    two tokens is that the update is conditional. Driven through the service
    rather than through two overlapping HTTP requests: the machinery for the
    latter is considerable and would be testing httpx, while the statement that
    decides it is this one.

    Each burn is followed by the token its caller would mint, in the endpoint's
    order — spend first, mint second — so that a `burn` that stopped being
    conditional shows up here as the second device.
    """
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)

    first = await device_invites.find_live(session, code)
    second = await device_invites.find_live(session, code)
    assert first is not None and second is not None

    for index, invite in enumerate((first, second)):
        spent = await device_invites.burn(session, invite)
        assert spent is (index == 0)
        if spent:
            session.add(
                DeviceToken(
                    token_hash=f"{index}" * 64,
                    class_id=invite_class.id,
                    telegram_id=invite.telegram_id,
                )
            )
    await session.commit()

    assert len(list(await session.scalars(select(DeviceToken)))) == 1


async def test_an_expired_personal_code_is_refused(client, session, invite_class):
    """Fifteen minutes, because the job is «walk from this chat to that phone»
    and the code travels through a chat that is forwardable and synced."""
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)
    invite = await session.scalar(select(DeviceInvite))
    invite.expires_at = device_invites.utcnow() - timedelta(seconds=1)
    await session.commit()

    response = await _join(client, code)
    assert response.status_code == 404
    assert await session.scalar(select(DeviceToken)) is None


async def test_minting_again_leaves_exactly_one_live_code(client, session, invite_class):
    """Two live codes is two chances for the older one — further up the chat,
    where somebody else scrolls past it — to still work."""
    first = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)
    second = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)
    assert first != second

    assert len(list(await session.scalars(select(DeviceInvite)))) == 1
    assert await device_invites.find_live(session, first) is None

    assert (await _join(client, first)).status_code == 404
    assert (await _join(client, second)).status_code == 200


async def test_minting_again_keeps_the_code_that_was_already_spent(
    client, session, invite_class
):
    """The sweep of live codes must not take the redeemed one with it.

    «Connect this phone, now press again for the second» is the ordinary flow
    and the bot's own text says to do it — so a mint that deleted every row for
    this person would destroy the record of the first phone's redemption
    seconds after writing it, in exactly the case `burn` keeps that row for.
    """
    spent = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)
    assert (await _join(client, spent)).status_code == 200

    await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)

    rows = list(await session.scalars(select(DeviceInvite)))
    assert len(rows) == 2
    redeemed = [row for row in rows if row.is_used]
    assert len(redeemed) == 1
    assert redeemed[0].telegram_id == MEMBER_ID


async def test_a_code_opens_the_class_it_was_minted_for_and_no_other(
    client, session, school_class, invite_class
):
    """Minting is per person *and* per class, so somebody in two classes holds
    two codes rather than one that works wherever they are known."""
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=school_class.id)

    joined = (await _join(client, code)).json()["class_id"]
    assert joined == school_class.id
    assert joined != invite_class.id


async def test_a_code_is_upper_cased_before_it_is_looked_up(client, session, invite_class):
    """`/join` upper-cases what it is given, and the alphabet has no lower case
    in it — so a code typed in lower case has to match the same hash."""
    code = await device_invites.mint(session, telegram_id=MEMBER_ID, class_id=invite_class.id)

    assert (await _join(client, code.lower())).status_code == 200


# --------------------------------------------------------------------------
# The sweep
# --------------------------------------------------------------------------


async def test_prune_drops_only_codes_past_the_cutoff(session, invite_class):
    """A day past expiry, not the moment of it.

    The row survives its own expiry on purpose — a used one is how «этот
    телефон подключил такой-то» stays answerable — so the cutoff is the point
    after which nobody is going to ask.
    """
    now = device_invites.utcnow()
    session.add_all([
        DeviceInvite(
            code_hash="a" * 64, class_id=invite_class.id, telegram_id=1,
            expires_at=now - timedelta(days=3),
        ),
        DeviceInvite(
            code_hash="b" * 64, class_id=invite_class.id, telegram_id=2,
            expires_at=now - timedelta(days=1, minutes=1),
        ),
        # Expired an hour ago: still there to be explained.
        DeviceInvite(
            code_hash="c" * 64, class_id=invite_class.id, telegram_id=3,
            expires_at=now - timedelta(hours=1),
        ),
        DeviceInvite(
            code_hash="d" * 64, class_id=invite_class.id, telegram_id=4,
            expires_at=now + timedelta(minutes=15),
        ),
    ])
    await session.commit()

    assert await device_invites.prune(session) == 2

    left = await session.scalars(select(DeviceInvite.telegram_id))
    assert sorted(left) == [3, 4]


class _FakeBot:
    """Enough of a Bot for the tick, which only closes it here."""

    def __init__(self) -> None:
        self.sent: list[tuple[int, str]] = []

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _FakeBot:
        return self

    async def close(self) -> None:
        return None


async def test_the_cron_tick_sweeps_the_codes_and_says_how_many(
    client, session, invite_class, monkeypatch
):
    """This deployment has no clock between requests, so anything periodic has
    to ride something that already is — and the count is reported so that a
    sweep that quietly stopped working is visible in the workflow's log."""
    settings = Settings(
        bot_token="123456:TEST",
        cron_secret=CRON_SECRET,
        database_url=get_settings().database_url,
        run_bot=False,
    )
    monkeypatch.setattr(cron, "get_settings", lambda: settings)
    monkeypatch.setattr(cron, "_build_bot", _FakeBot)

    session.add(
        DeviceInvite(
            code_hash="e" * 64, class_id=invite_class.id, telegram_id=1,
            expires_at=device_invites.utcnow() - timedelta(days=2),
        )
    )
    await session.commit()

    response = await client.get("/api/v1/cron/tick", headers={"X-Cron-Secret": CRON_SECRET})
    assert response.status_code == 200, response.text
    assert response.json()["device_invites_purged"] == 1

    assert await session.scalar(select(DeviceInvite)) is None


# --------------------------------------------------------------------------
# How the mode is spelled, in the database and in the revision that adds it
# --------------------------------------------------------------------------


async def test_the_column_holds_the_member_name_not_the_value(session, school_class):
    """The test this file exists for.

    ``SAEnum(JoinMode)`` writes ``OPEN``; ``JoinMode.OPEN.value`` is ``open``,
    and the API spells it that way. They are not the same string, and the one
    that goes in the database is the name. Flip the model's ``server_default``
    to ``.value`` and this fails here rather than on the first class read in
    production.
    """
    stored = await session.scalar(
        text("select join_mode from classes where id = :id"), {"id": school_class.id}
    )
    assert stored == "OPEN"

    await session.refresh(school_class)
    assert school_class.join_mode is JoinMode.OPEN


async def test_a_row_inserted_without_the_column_reads_back_as_open(session):
    """What a migrated class is: a row the server default filled in.

    Inserted around the ORM on purpose, so the value under test is the column's
    own default rather than the Python one — that is the value every class that
    existed before this feature now carries, and an unreadable one would make
    the first ORM read of any of them raise.
    """
    await session.execute(text("insert into classes (name, join_code) values ('9В', 'OLDCLS1')"))
    await session.commit()

    stored = await session.scalar(
        text("select join_mode from classes where join_code = 'OLDCLS1'")
    )
    assert stored == "OPEN"

    migrated = await session.scalar(
        select(SchoolClass).where(SchoolClass.join_code == "OLDCLS1")
    )
    assert migrated.join_mode is JoinMode.OPEN


def test_the_revision_writes_the_same_spelling_as_the_model():
    """Read as text rather than by running the migration.

    The revision cannot be imported — ``migrations/versions`` is not a package
    and importing it would need an alembic context — but the one thing worth
    pinning is a literal: a ``server_default`` of «open» would be applied to
    every existing class by the one command nobody runs twice.
    """
    source = (
        Path(__file__).resolve().parent.parent
        / "migrations"
        / "versions"
        / "0010_join_modes.py"
    ).read_text(encoding="utf-8")

    assert 'server_default="OPEN"' in source
    assert 'sa.Enum("OPEN", "INVITE", name="joinmode", native_enum=False)' in source
    assert JoinMode.OPEN.name == "OPEN" and JoinMode.OPEN.value == "open"
