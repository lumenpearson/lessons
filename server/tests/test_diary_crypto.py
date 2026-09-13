"""Encryption at rest for the diary's upstream credential.

The property under test is not «Fernet works» — it does. It is the two
decisions this project made around it: that a missing key switches the feature
off rather than quietly writing plaintext, and that a credential which will not
open is a dead session rather than an exception somewhere further in.
"""

from __future__ import annotations

import pytest

from app import crypto
from app.config import get_settings
from app.models import DiarySession
from app.services import diary as service


@pytest.fixture
def secret(monkeypatch):
    """Sets ``DIARY_SECRET`` for one test, cache and all.

    ``get_settings`` is ``lru_cache``d, so the object every caller holds is the
    same one — patching the attribute on it is what actually changes the
    answer, and clearing the cache afterwards is what stops it leaking into the
    next test.
    """

    def _set(value: str) -> None:
        monkeypatch.setattr(get_settings(), "diary_secret", value, raising=False)

    yield _set
    get_settings.cache_clear()


def test_a_sealed_credential_comes_back_exactly(secret):
    secret("x" * 40)
    assert crypto.unseal(crypto.seal("X-JWT-Token-value")) == "X-JWT-Token-value"


def test_the_stored_form_is_not_the_credential(secret):
    """The whole point: a dump of ``diary_sessions`` is not a pile of live
    tickets to somebody else's service."""
    secret("x" * 40)
    sealed = crypto.seal("secret-upstream-token")

    assert "secret-upstream-token" not in sealed
    assert sealed.startswith("gAAAAA")  # Fernet's version byte, base64'd


def test_two_seals_of_one_value_differ(secret):
    """Fernet carries a random IV, so equal credentials do not look equal in
    the table — otherwise the column leaks which rows share a session."""
    secret("x" * 40)
    assert crypto.seal("same") != crypto.seal("same")


def test_without_a_key_the_diary_is_off_rather_than_plaintext(secret):
    """The decision this file exists to hold.

    A fallback to plaintext would be invisible — the feature keeps answering,
    and the only difference is a column nobody looks at — so deployments stay
    in that state for years. Refusing is loud on the first day and silent
    afterwards.
    """
    secret("")

    assert crypto.diary_enabled() is False
    with pytest.raises(crypto.DiaryEncryptionUnavailable):
        crypto.seal("anything")


@pytest.mark.parametrize("weak", ["", "diary", "changeme", "x" * 31])
def test_a_secret_too_short_to_be_random_is_no_secret(secret, weak):
    """The key is one SHA-256 pass over the secret, which is right for
    ``token_urlsafe(48)`` and no defence at all against a dictionary. The
    length check is the blunt filter that keeps «changeme» from being a key."""
    secret(weak)
    assert crypto.diary_enabled() is False


def test_a_credential_sealed_under_another_key_will_not_open(secret):
    secret("a" * 40)
    sealed = crypto.seal("token")

    secret("b" * 40)
    assert crypto.unseal(sealed) is None


def test_rubbish_in_the_column_opens_to_nothing_rather_than_raising(secret):
    """A truncated or hand-edited blob is a dead session, not a stack trace:
    Fernet authenticates the ciphertext, so there is no way for it to decrypt
    into something plausible."""
    secret("x" * 40)

    assert crypto.unseal("not-a-fernet-token") is None
    assert crypto.unseal("") is None


async def test_a_session_that_cannot_be_opened_is_expired_on_the_way_out(session, secret):
    """Checked where a session is looked up, not where it is used.

    Handing the row on would spend an upstream round trip to be told the same
    thing — from an address the upstream rate-limits.
    """
    secret("a" * 40)
    from app.security import hash_token

    row = DiarySession(
        token_hash=hash_token("ours"),
        upstream_token=crypto.seal("upstream"),
        login="parent@example.com",
    )
    session.add(row)
    await session.commit()

    secret("b" * 40)  # key rotated out from under it
    assert await service.find_session(session, "ours") is None

    await session.refresh(row)
    assert row.expired_at is not None


async def test_signing_in_without_a_key_refuses_before_the_password_leaves(secret):
    """Order of operations, and the reason it is that order: sending someone's
    password to a third party to then throw the answer away is worse than
    refusing. ``DiaryDisabled`` rather than a generic failure because the fix
    is an operator's and no retry will help."""
    secret("")

    with pytest.raises(service.DiaryDisabled):
        await service.sign_in(None, "parent@example.com", "hunter2")
