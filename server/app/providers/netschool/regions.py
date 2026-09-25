"""The regional servers of «Сетевой город», and the only ones we ever call.

This table is an allow-list first and a directory second. A region key that
reaches sign-in — from a client body, a class row or a sealed credential — is
looked up here, and a key that is not in the table is refused before any
network call. That is the whole SSRF guard: nothing turns free text into a
host, so this server cannot be pointed at an address of somebody's choosing.

The set is the survey's (`docs/diaries/netschool.md`). A region that leaves
«Сетевой город» is removed here, which ends its sessions at their next call.

Two flags decide what a region is good for:

- ``password`` — whether a login and password get in at all. Three regions have
  switched to Госуслуги only (`docs/diaries/netschool.md`), so ``password`` is
  ``False`` there: the bot never offers them and every door refuses them
  locally, with no request sent.
- ``verified`` — whether this server has been seen to answer from production.
  It is ``False`` for every row, because **nothing here has been tried from the
  deployment's own address** (#121, #135). The one-off owner probe fills that
  gap; until then the bot marks an unverified region «не проверено».

``https`` only: an ``http`` origin would send the salted password and the
session in clear text, so a region that serves only ``http`` (Волгоград) is
left out rather than downgraded.

Trust is the system's, for every origin alike. A region whose server presents
a root the system does not trust (the Russian Trusted Root) is left out too:
there is no per-origin CA here — an earlier ``ca_bundle`` field promised one
and nothing ever read it — and TLS verification is never turned off.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Region:
    key: str
    title: str
    origin: str  # https:// only; the base for every /webapi call
    zone: str  # IANA; resolved through app.timezones.resolve, which falls back rather than raises
    password: bool = True
    verified: bool = False


# Alphabetical by title, which is the order the bot's region picker shows. Zones
# are the region's administrative centre; a region spanning several keeps its
# capital's.
_REGIONS: tuple[Region, ...] = (
    # ЕСИА-only since 10 Jan 2026 — never offered, refused locally.
    Region("altai-krai", "Алтайский край", "https://netschool.edu22.info",
           "Asia/Barnaul", password=False),
    Region("amur", "Амурская область", "https://region.obramur.ru", "Asia/Yakutsk"),
    Region("zabaikalsky", "Забайкальский край", "https://region.zabedu.ru", "Asia/Chita"),
    Region("kamchatka", "Камчатский край", "https://school.sgo41.ru", "Asia/Kamchatka"),
    Region("kchr", "Карачаево-Черкесская Республика", "https://sgo.kchgov.ru", "Europe/Moscow"),
    Region("leningrad", "Ленинградская область", "https://e-school.obr.lenreg.ru", "Europe/Moscow"),
    # ЕСИА-only.
    Region("primorye", "Приморский край", "https://sgo.prim-edu.ru",
           "Asia/Vladivostok", password=False),
    Region("buryatia", "Республика Бурятия", "https://deti.obr03.ru", "Asia/Irkutsk"),
    Region("ingushetia", "Республика Ингушетия", "https://sgo.edu-ri.ru", "Europe/Moscow"),
    Region("komi", "Республика Коми", "https://giseo.rkomi.ru", "Europe/Moscow"),
    Region("mari-el", "Республика Марий Эл", "https://sgo.mari-el.gov.ru", "Europe/Moscow"),
    Region("mordovia", "Республика Мордовия", "https://sgo.e-mordovia.ru", "Europe/Moscow"),
    Region("yakutia", "Республика Саха (Якутия)", "https://sgo.e-yakutia.ru", "Asia/Yakutsk"),
    Region("ryazan", "Рязанская область", "https://e-school.ryazan.gov.ru", "Europe/Moscow"),
    Region("samara", "Самарская область", "https://asurso.ru", "Europe/Samara"),
    Region("sakhalin", "Сахалинская область", "https://netcity.admsakhalin.ru:11111",
           "Asia/Sakhalin"),
    Region("tomsk", "Томская область", "https://sgo.tomedu.ru", "Asia/Tomsk"),
    # ЕСИА + SMS only.
    Region("tula", "Тульская область", "https://sgo1.edu71.ru", "Europe/Moscow", password=False),
    Region("ulyanovsk", "Ульяновская область", "https://sgo.cit73.ru", "Europe/Ulyanovsk"),
)

_BY_KEY = {r.key: r for r in _REGIONS}


def get(key: str | None) -> Region | None:
    """The region for a key, or ``None`` for one not in the allow-list."""
    if not key:
        return None
    return _BY_KEY.get(key)


def listed(*, password_only: bool = True) -> list[Region]:
    """The regions, for the bot's picker. Only password-capable ones by default."""
    return [r for r in _REGIONS if r.password or not password_only]
