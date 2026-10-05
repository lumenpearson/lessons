"""The rules v1's routers held and v2 needs, now where both can reach them.

``caller_bucket`` over header lines and a peer, the bearer, the clock and the
date bounds, and one instance of each limiter
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2). v1's own tests are
the proof that its answers did not move; these hold the shapes v2 adds.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from types import SimpleNamespace

import pytest
from starlette.datastructures import Headers

from app import security
from app.api import diary, directory, public
from app.api.deps import bearer, caller_bucket, peer_host, request_bucket
from app.config import get_settings
from app.security import client_bucket
from app.services import clock


def test_one_rule_buckets_a_starlette_request_and_a_list_of_header_lines(monkeypatch) -> None:
    monkeypatch.setattr(get_settings(), "trusted_proxy_hops", 1)
    raw = [(b"x-forwarded-for", b"10.0.0.1"), (b"x-forwarded-for", b"198.51.100.7")]
    request = SimpleNamespace(headers=Headers(raw=raw), client=SimpleNamespace(host="127.0.0.1"))
    lines = [("X-Forwarded-For", "10.0.0.1"), ("X-Forwarded-For", "198.51.100.7")]
    assert request_bucket(request, scope="diary:") == caller_bucket(
        lines, "127.0.0.1", scope="diary:"
    )
    assert caller_bucket(lines, "127.0.0.1") == client_bucket("198.51.100.7")


@pytest.mark.parametrize(
    ("reported", "host"),
    [
        ("203.0.113.9:4321", "203.0.113.9"),
        ("::1:52144", "::1"),
        ("2001:db8::7:443", "2001:db8::7"),
        ("testclient:50000", "testclient"),
        (None, None),
    ],
)
def test_the_port_connectrpc_reports_is_stripped_ipv6_included(reported, host) -> None:
    assert peer_host(reported) == host


def test_two_connections_from_one_address_are_one_bucket() -> None:
    assert caller_bucket([], peer_host("198.51.100.20:40001")) == caller_bucket(
        [], peer_host("198.51.100.20:40002")
    )


@pytest.mark.parametrize(
    ("header", "token"),
    [
        ("Bearer abc", "abc"),
        ("bearer abc", "abc"),
        ("Basic abc", None),
        ("Bearer ", None),
        (None, None),
    ],
)
def test_a_bearer_is_read_by_one_rule(header, token) -> None:
    assert bearer(header) == token


def test_every_limiter_is_one_instance_whichever_module_names_it() -> None:
    assert public.join_limiter is security.join_limiter
    assert public.MAX_DEVICES_PER_CLASS == security.MAX_DEVICES_PER_CLASS == 300
    assert diary.diary_login_limiter is security.diary_login_limiter
    assert diary.diary_open_limiter is security.diary_open_limiter
    assert directory.directory_limiter is security.directory_limiter


def test_the_class_clock_moves_today_with_it(monkeypatch, school_class) -> None:
    moment = datetime(2026, 9, 7, 23, 30, tzinfo=UTC)
    monkeypatch.setattr(clock, "now", lambda klass: moment.astimezone(klass.tz))
    # 23:30 UTC is already Tuesday in Moscow.
    assert clock.today(school_class) == date(2026, 9, 8)


def test_the_class_clock_runs_in_the_class_s_zone_not_the_server_s(school_class) -> None:
    # The test above replaces ``now`` and so cannot see which zone it reads;
    # a naive ``datetime.now()`` is the server's zone, which on Vercel is UTC.
    assert clock.now(school_class).tzinfo == school_class.tz


@pytest.mark.parametrize(
    ("retry_after", "seconds"), [(0.0, 1), (0.2, 1), (5.0, 6), (899.9, 900)]
)
def test_a_wait_is_whole_seconds_and_never_zero(retry_after, seconds) -> None:
    assert security.Throttled(retry_after).seconds == seconds


def test_the_bounds_are_inclusive_and_v1_s() -> None:
    assert clock.in_bounds(date(2000, 1, 1), date(2100, 1, 1))
    assert not clock.in_bounds(date(1999, 12, 31))
    assert not clock.in_bounds(date(2026, 9, 1), date(2100, 1, 2))
