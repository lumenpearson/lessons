"""#199: how many phones the class code can let into one class.

Every ``POST /join`` mints a row, and the throttle counts only failures — so a
caller who holds a real class code could mint tokens for ever, each one a live
read token for the class until 180 days of silence prune it. The class code now
stops at ``MAX_DEVICES_PER_CLASS`` live devices and answers ``409`` with a
Russian sentence.

Three things are held here beside the bound itself. The ``409`` is not a failed
attempt for the throttle, for the reason the invite-only ``403`` is not: this
caller found a real code, and counting it would lock a whole school NAT out of
every other class. A revoked device is a tombstone, not a phone, and does not
count. And a personal code from the bot still joins a full class: it is minted
only for a member the bot knows, one phone at a time, with a name in the
journal — it is not what the bound is against, and it is the way in that is
left when somebody has filled the class through the shared code.
"""

from __future__ import annotations

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import func, select

from app.api.public import MAX_DEVICES_PER_CLASS, join_limiter
from app.fsm_storage import FsmRecord  # noqa: F401 - registers fsm_states before create_all
from app.main import app
from app.models import DeviceToken, JoinAttempt, SchoolClass
from app.security import hash_token
from app.services import device_invites

FULL_DETAIL = (
    "К классу подключено слишком много телефонов. Возьмите личный код в боте "
    "(«📱 Подключить телефон») или попросите администратора отключить старые телефоны"
)


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def _join(client, code: str) -> httpx.Response:
    return await client.post("/api/v1/join", json={"code": code, "device_name": "pytest"})


async def _fill(session, klass: SchoolClass, count: int, *, revoked: bool = False) -> None:
    """``count`` devices already in ``klass``, as earlier joins would have left them."""
    session.add_all(
        DeviceToken(
            token_hash=hash_token(f"{klass.id}-{revoked}-{n}"),
            class_id=klass.id,
            device_name=f"phone {n}",
            revoked=revoked,
        )
        for n in range(count)
    )
    await session.commit()


async def _live(session, klass: SchoolClass) -> int:
    return await session.scalar(
        select(func.count())
        .select_from(DeviceToken)
        .where(DeviceToken.class_id == klass.id, DeviceToken.revoked.is_(False))
    )


async def _attempts(session) -> int:
    return await session.scalar(select(func.count()).select_from(JoinAttempt))


async def test_the_class_code_joins_up_to_the_bound_and_no_further(
    client, session, school_class
):
    await _fill(session, school_class, MAX_DEVICES_PER_CLASS - 1)

    last = await _join(client, "TEST42")
    assert last.status_code == 200, last.text
    assert await _live(session, school_class) == MAX_DEVICES_PER_CLASS

    refused = await _join(client, "TEST42")
    assert refused.status_code == 409
    assert refused.json()["detail"] == FULL_DETAIL
    # Nothing minted: the refusal is the whole point.
    assert await _live(session, school_class) == MAX_DEVICES_PER_CLASS


async def test_a_full_class_is_not_counted_against_the_caller(client, session, school_class):
    """Past the throttle's own limit, and still a 409 rather than a 429 — and
    the school next door, behind the same address, still joins its own class."""
    await _fill(session, school_class, MAX_DEVICES_PER_CLASS)
    other = SchoolClass(name="9Б", join_code="OTHER42")
    session.add(other)
    await session.commit()

    for _ in range(join_limiter.limit + 5):
        assert (await _join(client, "TEST42")).status_code == 409

    assert await _attempts(session) == 0
    assert (await _join(client, "OTHER42")).status_code == 200


async def test_revoked_devices_are_not_phones(client, session, school_class):
    """A revoked row is kept as a tombstone so its token keeps failing; it holds
    no phone, so switching old phones off is how an admin makes room."""
    await _fill(session, school_class, MAX_DEVICES_PER_CLASS, revoked=True)
    await _fill(session, school_class, MAX_DEVICES_PER_CLASS - 1)

    assert (await _join(client, "TEST42")).status_code == 200


async def test_the_bound_is_per_class(client, session, school_class):
    other = SchoolClass(name="9Б", join_code="OTHER42")
    session.add(other)
    await session.commit()
    await _fill(session, other, MAX_DEVICES_PER_CLASS)

    assert (await _join(client, "TEST42")).status_code == 200
    assert (await _join(client, "OTHER42")).status_code == 409


async def test_a_personal_code_still_joins_a_full_class(client, session, school_class):
    """The way in that is left when the class code has been used to fill it."""
    await _fill(session, school_class, MAX_DEVICES_PER_CLASS)
    code = await device_invites.mint(session, telegram_id=4242, class_id=school_class.id)

    response = await _join(client, code)

    assert response.status_code == 200, response.text
    assert await _live(session, school_class) == MAX_DEVICES_PER_CLASS + 1
