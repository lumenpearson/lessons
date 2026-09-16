"""A connection string pasted from a provider's console must just work.

Every case here is a real form Neon or Supabase hands out. The cost of getting
one wrong is a deployment that fails at startup with an error naming a query
parameter, which tells the reader nothing about what to change.
"""

from __future__ import annotations

import pytest

from app.database_url import describe, is_pooled, normalise_database_url

NEON_POOLED = (
    "postgresql://user:secret@ep-noisy-sea-b2jc3hzb-pooler.c-6.eu-central-1"
    ".aws.neon.tech/neondb?sslmode=require&channel_binding=require"
)
NEON_DIRECT = (
    "postgresql://user:secret@ep-noisy-sea-b2jc3hzb.c-6.eu-central-1"
    ".aws.neon.tech/neondb?sslmode=require&channel_binding=require"
)


def test_the_driver_is_added():
    url, _ = normalise_database_url(NEON_DIRECT)
    assert url.startswith("postgresql+asyncpg://")


def test_the_postgres_scheme_alias_is_handled_too():
    url, _ = normalise_database_url("postgres://u:p@host/db")
    assert url.startswith("postgresql+asyncpg://")


@pytest.mark.parametrize("param", ["sslmode", "channel_binding"])
def test_libpq_only_parameters_are_removed(param):
    url, _ = normalise_database_url(NEON_POOLED)
    assert param not in url


def test_sslmode_require_becomes_an_asyncpg_connect_arg():
    """Dropping it silently would downgrade a connection meant to be encrypted."""
    _, args = normalise_database_url(NEON_DIRECT)
    assert args["ssl"] == "require"


def test_sslmode_disable_is_honoured_rather_than_ignored():
    _, args = normalise_database_url("postgresql://u:p@host/db?sslmode=disable")
    assert args["ssl"] is False


def test_no_sslmode_means_no_opinion():
    _, args = normalise_database_url("postgresql://u:p@host/db")
    assert "ssl" not in args


def test_a_pooled_host_disables_prepared_statement_caching():
    """PgBouncer in transaction mode gives each transaction whichever backend is
    free; asyncpg prepares statements on the connection it first saw."""
    url, args = normalise_database_url(NEON_POOLED)
    assert args["statement_cache_size"] == 0
    assert "prepared_statement_cache_size=0" in url


def test_a_direct_host_keeps_caching():
    url, args = normalise_database_url(NEON_DIRECT)
    assert "statement_cache_size" not in args
    assert "prepared_statement_cache_size" not in url


@pytest.mark.parametrize(
    ("host", "pooled"),
    [
        ("ep-noisy-sea-b2jc3hzb-pooler.c-6.eu-central-1.aws.neon.tech", True),
        ("ep-noisy-sea-b2jc3hzb.c-6.eu-central-1.aws.neon.tech", False),
        ("pooler.example.com", True),
        ("db.example.com", False),
    ],
)
def test_pooled_host_detection(host, pooled):
    assert is_pooled(host) is pooled


def test_an_already_qualified_url_is_left_alone():
    original = "postgresql+asyncpg://u:p@host/db"
    url, args = normalise_database_url(original)
    assert url == original and args == {}


def test_sqlite_is_left_alone():
    original = "sqlite+aiosqlite:///./lessons.db"
    url, args = normalise_database_url(original)
    assert url == original and args == {}


def test_an_empty_url_does_not_raise():
    assert normalise_database_url("") == ("", {})


def test_credentials_and_database_survive_the_rewrite():
    url, _ = normalise_database_url(NEON_POOLED)
    assert "user:secret@" in url
    assert "/neondb" in url


def test_unrecognised_parameters_are_preserved():
    url, _ = normalise_database_url("postgresql://u:p@host/db?application_name=x&custom=keep")
    assert "custom=keep" in url
    assert "application_name" not in url


def test_describe_never_leaks_the_password():
    rendered = describe(NEON_POOLED)
    assert "secret" not in rendered
    assert "neon.tech" in rendered
    assert "sslmode" not in rendered


def test_describe_never_leaks_a_password_that_contains_an_at_sign():
    """«ever» includes the generated password with an unescaped «@» in it.

    Both libpq and asyncpg read the host as what follows the *last* «@», so
    such a URL connects; cutting at the first one left the tail of the password
    in the line this function exists to make safe to print.
    """
    rendered = describe("postgresql://user:pa@ss@ep-x.eu-central-1.aws.neon.tech/neondb")

    assert rendered == "postgresql://ep-x.eu-central-1.aws.neon.tech/neondb"
    assert "ss" not in rendered.split("//", 1)[1].split(".", 1)[0]


# ---- the documented form, and the verify-* modes ---------------------------


def test_a_url_that_already_names_asyncpg_is_still_normalised():
    """docs/deploy.md tells the operator to set ``postgresql+asyncpg://``; that
    form used to skip every rewrite, pooler cache handling included."""
    pooled = NEON_POOLED.replace("postgresql://", "postgresql+asyncpg://", 1)
    url, args = normalise_database_url(pooled)
    assert url.startswith("postgresql+asyncpg://")
    assert "sslmode" not in url
    assert args["ssl"] == "require"
    assert args["statement_cache_size"] == 0
    assert "prepared_statement_cache_size=0" in url


def test_other_drivers_are_left_alone():
    url, args = normalise_database_url("postgresql+psycopg://u:p@host/db?sslmode=require")
    assert url == "postgresql+psycopg://u:p@host/db?sslmode=require"
    assert args == {}


@pytest.mark.parametrize(
    ("mode", "check_hostname"), [("verify-full", True), ("verify-ca", False)]
)
def test_verify_modes_get_a_context_that_verifies(mode, check_hostname):
    """asyncpg's "require" trusts any certificate; verify-* must not be
    quietly downgraded to it."""
    import ssl

    _, args = normalise_database_url(f"postgresql://u:p@host/db?sslmode={mode}")
    context = args["ssl"]
    assert isinstance(context, ssl.SSLContext)
    assert context.verify_mode is ssl.CERT_REQUIRED
    assert context.check_hostname is check_hostname


def test_an_unloadable_sslrootcert_fails_loudly_and_names_itself(tmp_path):
    """Better a startup error naming the parameter than an SSLError on the
    first query, from a stack frame that never mentions the URL."""
    missing = tmp_path / "nope.crt"
    with pytest.raises(ValueError, match="sslrootcert"):
        normalise_database_url(
            f"postgresql://u:p@host/db?sslmode=verify-full&sslrootcert={missing}"
        )


def test_sslrootcert_never_reaches_the_driver_url():
    url, args = normalise_database_url("postgresql://u:p@host/db?sslmode=verify-ca")
    assert "sslrootcert" not in url
    assert "sslmode" not in url
    assert args["ssl"].check_hostname is False


def test_supabase_transaction_pooler_is_recognised():
    assert is_pooled("aws-0-eu-central-1.pooler.supabase.com")
    assert is_pooled("AWS-0-EU-CENTRAL-1.POOLER.SUPABASE.COM")
    assert not is_pooled("db.abcdefghijkl.supabase.co")
