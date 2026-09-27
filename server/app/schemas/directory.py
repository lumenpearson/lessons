"""The school directory: a school found by name, and the regions it may be in.

``SchoolOut`` and ``SchoolSearchOut`` answer an admin's search for the class's
school (``GET /api/v1/manage/schools``); ``SchoolRegionOut`` and
``SchoolRegionsOut`` answer the anonymous question of which regions a school
may be in.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class SchoolOut(BaseModel):
    """One row of the school directory, as the picker shows it."""

    name: str
    full_name: str
    #: The OGRN, a Russian company registration number — thirteen digits,
    #: assigned once and never reused. Returned so a
    #: client can tell two «Гимназия № 3» apart without parsing the address.
    ogrn: str | None = None
    inn: str | None = None
    address: str | None = None
    city: str | None = None
    region: str | None = None
    #: False for a school the register has closed. Shown rather than hidden:
    #: a class created in May may belong to one merged over the summer.
    active: bool = True


class SchoolSearchOut(BaseModel):
    """One page of results, and whether there is more behind it.

    ``truncated`` is not «есть ещё страницы» — those are ``pages``. It means
    the directory's own ceiling of twenty was reached, so this is the first
    twenty of an unknown number and the way forward is a longer query, not a
    next page. A client that ignores it will show «найдено 20» for a search
    matching three hundred schools.
    """

    items: list[SchoolOut] = Field(default_factory=list)
    page: int
    pages: int
    total: int
    truncated: bool = False


class SchoolRegionOut(BaseModel):
    """One region the search found schools in."""

    #: The region catalog's key («tatarstan»), the one the phone's bundled copy
    #: of the same catalog knows. ``null`` when the directory named a region
    #: the catalog cannot place — then ``label`` says what it called it.
    region: str | None = None
    #: The two-digit subject code: the catalog's for a placed region, the
    #: directory's own for one it could not place.
    code: str | None = None
    #: The directory's own name for the region («г Байконур»), and only for
    #: an unplaced one: a placed region is named from the catalog, in the
    #: reader's language.
    label: str | None = None
    #: Hits in this region among the twenty the directory returns at most.
    schools: int
    #: Up to three towns, best-ranked first.
    cities: list[str] = Field(default_factory=list)
    #: Up to three school names as a person writes them.
    examples: list[str] = Field(default_factory=list)


class SchoolRegionsOut(BaseModel):
    """What ``GET /api/v1/directory/school-regions`` answers.

    Picking the region from the list always works; this only saves the
    scrolling. So every failure of it is a 503 that says «выберите регион из
    списка», and an answer that places nothing is an empty list rather than
    an error.
    """

    #: As searched: whitespace collapsed, cut to 150 characters.
    query: str
    #: Best-ranked region first, at most ten.
    regions: list[SchoolRegionOut] = Field(default_factory=list)
    #: The directory's own ceiling of twenty was reached, so these are the
    #: regions of the first twenty matches, not of all of them.
    truncated: bool = False
    #: The name is too common to place — «школа № 5» is in every region.
    #: Answered with no upstream call when the name is only a kind of school
    #: and a small number; after one when twenty matches span four regions or
    #: more. The phone says so and shows the list.
    generic: bool = False
