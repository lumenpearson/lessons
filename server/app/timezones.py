"""Russian school time zones.

The project targets schools across Russia, which spans eleven zones from
Kaliningrad (MSK−1) to Kamchatka (MSK+9). A single server-wide timezone would
be wrong for every class outside the one it was set for, so the zone lives on
the class and this module is the list a class is chosen from.

Zones are labelled by their offset from Moscow because that is how Russian
schedules, transport timetables and television listings are actually written.
"""

from __future__ import annotations

from zoneinfo import ZoneInfo

# (IANA name, Russian label, representative cities)
RUSSIAN_TIMEZONES: list[tuple[str, str, str]] = [
    ("Europe/Kaliningrad", "МСК−1 (UTC+2)", "Калининград"),
    ("Europe/Moscow", "МСК (UTC+3)", "Москва, Санкт-Петербург"),
    ("Europe/Samara", "МСК+1 (UTC+4)", "Самара, Ижевск"),
    ("Asia/Yekaterinburg", "МСК+2 (UTC+5)", "Екатеринбург, Уфа, Пермь"),
    ("Asia/Omsk", "МСК+3 (UTC+6)", "Омск"),
    ("Asia/Krasnoyarsk", "МСК+4 (UTC+7)", "Красноярск, Новосибирск"),
    ("Asia/Irkutsk", "МСК+5 (UTC+8)", "Иркутск, Улан-Удэ"),
    ("Asia/Yakutsk", "МСК+6 (UTC+9)", "Якутск, Чита"),
    ("Asia/Vladivostok", "МСК+7 (UTC+10)", "Владивосток, Хабаровск"),
    ("Asia/Magadan", "МСК+8 (UTC+11)", "Магадан, Южно-Сахалинск"),
    ("Asia/Kamchatka", "МСК+9 (UTC+12)", "Петропавловск-Камчатский"),
]

DEFAULT_TIMEZONE = "Europe/Moscow"

_VALID = {name for name, _, _ in RUSSIAN_TIMEZONES}


def is_supported(name: str) -> bool:
    return name in _VALID


def label_for(name: str) -> str:
    """«МСК+2 (UTC+5) · Екатеринбург, Уфа, Пермь», or the raw name if unknown.

    Unknown names are shown rather than rejected: a school outside Russia can
    still be configured by writing an IANA name directly into the database, and
    the bot should not display that as an error.
    """
    for zone, offset, cities in RUSSIAN_TIMEZONES:
        if zone == name:
            return f"{offset} · {cities}"
    return name


def resolve(name: str | None, fallback: str = DEFAULT_TIMEZONE) -> ZoneInfo:
    """Turn a stored zone name into a ZoneInfo, tolerating nulls and typos.

    A class whose zone was never set, or was set to something this build of
    Python has no data for, must still render a schedule — falling back is
    always better than a 500 on the one endpoint the widget depends on.
    """
    for candidate in (name, fallback, DEFAULT_TIMEZONE):
        if not candidate:
            continue
        try:
            return ZoneInfo(candidate)
        except Exception:  # noqa: BLE001 - any zoneinfo failure means "try the next one"
            continue
    return ZoneInfo("UTC")
