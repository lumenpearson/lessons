"""Permission rules. These are the only thing standing between a classmate and
the ability to rewrite everyone's timetable, so they are tested directly."""

from __future__ import annotations

import pytest

from app.bot.roles import can_grant, claim_phone_invites, get_role, is_env_owner
from app.models import BotUser, PhoneInvite, Role
from app.security import normalise_phone

OWNER_TELEGRAM_ID = 1000  # matches OWNER_IDS in tests/conftest.py


def test_role_ladder_is_ordered():
    assert Role.OWNER.at_least(Role.ADMIN)
    assert Role.ADMIN.at_least(Role.EDITOR)
    assert Role.EDITOR.at_least(Role.VIEWER)
    assert not Role.VIEWER.at_least(Role.EDITOR)
    assert Role.EDITOR.at_least(Role.EDITOR)


@pytest.mark.parametrize(
    ("actor", "target", "allowed"),
    [
        (Role.OWNER, Role.ADMIN, True),
        (Role.OWNER, Role.EDITOR, True),
        (Role.OWNER, Role.OWNER, False),  # OWNER comes from the environment only
        (Role.ADMIN, Role.EDITOR, True),
        (Role.ADMIN, Role.VIEWER, True),
        (Role.ADMIN, Role.ADMIN, False),  # no sideways promotion
        (Role.ADMIN, Role.OWNER, False),
        (Role.EDITOR, Role.VIEWER, False),  # editors grant nothing
        (Role.VIEWER, Role.VIEWER, False),
    ],
)
def test_can_grant_never_lets_anyone_match_or_exceed_themselves(actor, target, allowed):
    assert can_grant(actor, target) is allowed


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("+7 900 123-45-67", "79001234567"),
        ("8 900 123 45 67", "79001234567"),
        ("79001234567", "79001234567"),
        ("+380 44 123 4567", "380441234567"),
        ("", ""),
        ("не номер", ""),
    ],
)
def test_normalise_phone_collapses_the_formats_people_actually_type(raw, expected):
    assert normalise_phone(raw) == expected


def test_env_owner_is_recognised():
    assert is_env_owner(OWNER_TELEGRAM_ID)
    assert not is_env_owner(2000)


async def test_env_owner_outranks_a_stored_membership(session, school_class):
    session.add(
        BotUser(telegram_id=OWNER_TELEGRAM_ID, class_id=school_class.id, role=Role.VIEWER)
    )
    await session.commit()

    assert await get_role(session, OWNER_TELEGRAM_ID, school_class.id) is Role.OWNER


async def test_sharing_a_contact_claims_a_matching_invite(session, school_class):
    session.add(
        PhoneInvite(
            class_id=school_class.id,
            phone="79001234567",
            role=Role.EDITOR,
            label="Аня",
            invited_by=OWNER_TELEGRAM_ID,
        )
    )
    await session.commit()

    granted = await claim_phone_invites(
        session, telegram_id=555, phone="+7 (900) 123-45-67", username="anya", full_name="Аня"
    )

    assert [(cls.id, role) for cls, role in granted] == [(school_class.id, Role.EDITOR)]
    assert await get_role(session, 555, school_class.id) is Role.EDITOR


async def test_an_invite_can_only_be_claimed_once(session, school_class):
    session.add(
        PhoneInvite(class_id=school_class.id, phone="79001234567", role=Role.EDITOR)
    )
    await session.commit()

    first = await claim_phone_invites(session, 555, "+79001234567", None, None)
    second = await claim_phone_invites(session, 666, "+79001234567", None, None)

    assert len(first) == 1
    assert second == []
    assert await get_role(session, 666, school_class.id) is None


async def test_claiming_never_downgrades_an_existing_role(session, school_class):
    session.add(BotUser(telegram_id=555, class_id=school_class.id, role=Role.ADMIN))
    session.add(
        PhoneInvite(class_id=school_class.id, phone="79001234567", role=Role.VIEWER)
    )
    await session.commit()

    await claim_phone_invites(session, 555, "+79001234567", None, None)
    assert await get_role(session, 555, school_class.id) is Role.ADMIN


async def test_claiming_reports_the_role_the_person_ends_up_with(session, school_class):
    """What this returns is what the bot draws the main menu from.

    `handlers/start.on_contact` reads `granted[0]` and hands that role to
    `main_menu`, so reporting the invite's role rather than the effective one
    tells an admin they are an observer and takes «🧩 Расписание»,
    «👥 Доступ» and «⚙️ Класс» off their screen — while the stored role, which
    the rule above keeps, is still ADMIN.
    """
    session.add(BotUser(telegram_id=555, class_id=school_class.id, role=Role.ADMIN))
    session.add(
        PhoneInvite(class_id=school_class.id, phone="79001234567", role=Role.VIEWER)
    )
    await session.commit()

    granted = await claim_phone_invites(session, 555, "+79001234567", None, None)

    assert [role for _, role in granted] == [Role.ADMIN]


async def test_an_unmatched_number_grants_nothing(session, school_class):
    granted = await claim_phone_invites(session, 555, "+79990000000", None, None)
    assert granted == []
    assert await get_role(session, 555, school_class.id) is None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("123456789", [123456789]),
        ("111,222,333", [111, 222, 333]),
        ("111, 222, 333", [111, 222, 333]),
        ("111;222;333", [111, 222, 333]),
        ("111,222,", [111, 222]),
        # The three that read as fewer owners than were written down. They are
        # here because docs/deploy.md now prints this exact table, and a table
        # that stops being true is worse than no table.
        ("111 222 333", []),
        ("111\n222\n333", []),
        ("111,222x,333", [111, 333]),
    ],
)
def test_the_owner_list_reads_only_commas(raw, expected):
    """A separator that is not a comma loses the whole list, silently.

    Nothing announces it: the bot starts, logs nothing, and simply has no owner
    — or, for a typo in one id, has every owner but that one. Vercel's value
    field is a textarea, so «one id per line» is the natural thing to type and
    the one that yields an empty list.
    """
    from app.config import Settings

    assert Settings(OWNER_IDS=raw).owner_id_list == expected
