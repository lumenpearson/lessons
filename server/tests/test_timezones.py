"""Time-zone handling for a country eleven zones wide."""

from __future__ import annotations

from datetime import datetime

import pytest

from app.timezones import (
    DEFAULT_TIMEZONE,
    RUSSIAN_TIMEZONES,
    is_supported,
    label_for,
    resolve,
)


def test_every_listed_zone_is_real_and_loadable():
    for name, _, _ in RUSSIAN_TIMEZONES:
        assert resolve(name).key == name


def test_the_list_spans_kaliningrad_to_kamchatka():
    reference = datetime(2026, 9, 9, 12, 0)
    offsets = [
        resolve(name).utcoffset(reference).total_seconds() / 3600
        for name, _, _ in RUSSIAN_TIMEZONES
    ]
    assert offsets == sorted(offsets)
    assert offsets[0] == 2  # Калининград, UTC+2
    assert offsets[-1] == 12  # Камчатка, UTC+12
    assert len(offsets) == 11


def test_moscow_covers_saint_petersburg():
    _, _, cities = next(row for row in RUSSIAN_TIMEZONES if row[0] == "Europe/Moscow")
    assert "Санкт-Петербург" in cities


@pytest.mark.parametrize(
    ("name", "expected"),
    [("Europe/Moscow", True), ("Asia/Vladivostok", True), ("Europe/Berlin", False), ("", False)],
)
def test_is_supported(name, expected):
    assert is_supported(name) is expected


def test_label_falls_back_to_the_raw_name_for_a_zone_outside_russia():
    assert label_for("Europe/Berlin") == "Europe/Berlin"
    assert "Екатеринбург" in label_for("Asia/Yekaterinburg")


@pytest.mark.parametrize("bad", [None, "", "Not/AZone", "Mars/Olympus"])
def test_resolve_never_raises_on_bad_input(bad):
    """The bundle endpoint must not 500 because a zone name was mistyped."""
    assert resolve(bad).key == DEFAULT_TIMEZONE


async def test_the_model_exposes_its_own_zone(session, school_class):
    assert school_class.tz.key == "Europe/Moscow"  # server default
    school_class.timezone = "Asia/Omsk"
    assert school_class.tz.key == "Asia/Omsk"
    assert school_class.timezone_name == "Asia/Omsk"
