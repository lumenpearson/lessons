"""``MeService``'s link: the code a phone shows to be linked, and unlinking it.

v1's ``GET /me`` minted the code on a read, and ``POST /me/unlink`` took the
phone back to read-only. v2 mints only in ``CreateLinkCode``; ``GetMe`` mints
nothing (``docs/specs/2026-10-05-server-v2-design.md``, decision 10). The code
is the one either version shows, stable until it is used, and the bot links
with it as it always has. ``UnlinkMe`` on a phone that is not linked writes
nothing, as the proto says.
"""

from __future__ import annotations

from sqlalchemy import func, select

from app.contract.lessons.v2.me_pb import Me
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.models import AuditEntry, DeviceToken
from app.services import linking

LINK = "MeService/CreateLinkCode"
UNLINK = "MeService/UnlinkMe"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def _link_columns(session, device_name: str):
    return (
        await session.execute(
            select(DeviceToken.telegram_id, DeviceToken.linked_at, DeviceToken.link_code).where(
                DeviceToken.device_name == device_name
            )
        )
    ).one()


async def test_a_code_minted_over_v2_is_the_one_v1_shows_and_the_bot_links_with(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["unlinked"]
    answer = await v2.both(LINK, token=token)
    code = answer.message.link_code.code
    assert len(code) == linking.LINK_CODE_LENGTH
    # This deployment names no bot, so there is no deep link to offer.
    assert not answer.message.link_code.has_field("bot_deep_link")
    # A credential for the minutes until it is used: nobody's cache's.
    assert answer.headers["cache-control"] == "private, no-store"
    assert (await v2.http.get("/api/v1/me", headers=_auth(token))).json()["link_code"] == code

    # The bot's «/link» with it, as account 2001, the class's viewer.
    assert await linking.link_device(session, code, 2001) is not None
    me = await v2.both("MeService/GetMe", token=token)
    assert (me.message.me.linked, me.message.me.role) == (True, ProtoRole.VIEWER)


async def test_a_linked_phone_gets_no_code(v2, v2_tokens, session) -> None:
    answer = await v2.both(LINK, token=v2_tokens["editor"])
    assert answer.status == 200
    assert answer.message.link_code is None
    assert set(await session.scalars(select(DeviceToken.link_code))) == {None}


async def test_the_deep_link_names_the_deployment_s_bot(
    v2, v2_tokens, monkeypatch, served_settings
) -> None:
    monkeypatch.setattr(served_settings, "bot_username", "@lessons_bot")
    link = (await v2.both(LINK, token=v2_tokens["unlinked"])).message.link_code
    assert link.bot_deep_link == f"https://t.me/lessons_bot?start=link_{link.code}"


async def test_unlinking_goes_back_to_read_only_and_a_retry_lands_on_the_same_answer(
    v2, v2_tokens, session
) -> None:
    token = v2_tokens["editor"]
    answer = await v2.both(UNLINK, token=token)
    assert answer.message.me == Me(
        device_name="editor phone", linked=False, role=ProtoRole.UNSPECIFIED, can_edit=False
    )
    assert tuple(await _link_columns(session, "editor phone")) == (None, None, None)
    # Still the class's phone, read-only now, and nothing in the journal.
    assert (await v2.both("MeService/GetMe", token=token)).message.me.linked is False
    assert await session.scalar(select(func.count()).select_from(AuditEntry)) == 0
    # v1's /me offers it a fresh code, as after v1's own unlink.
    assert len((await v2.http.get("/api/v1/me", headers=_auth(token))).json()["link_code"]) == 6


async def test_unlinking_a_phone_that_is_not_linked_writes_nothing(
    v2, v2_tokens, session, statement_writes
) -> None:
    """Not even its link code goes: unlinking a phone nobody is behind changes
    nothing. The call before it touched the phone's last call, so inside the
    fifteen minutes there is nothing else this one may write."""
    token = v2_tokens["unlinked"]
    code = (await v2.rest(LINK, token=token)).message.link_code.code
    with statement_writes() as seen:
        answer = await v2.both(UNLINK, token=token)
    assert answer.message.me.linked is False
    assert seen == []
    assert tuple(await _link_columns(session, "unlinked phone")) == (None, None, code)
