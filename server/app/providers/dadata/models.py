"""A school as this project describes it.

Not the upstream's shape. DaData answers with a row of the state register of
legal entities — forty-odd fields about a juridical person, most of which
belong to an accountant rather than to somebody picking their child's school.
What a class needs is a name it recognises, an address that tells two
«Гимназия № 3» apart, and one stable id to keep.
"""

from __future__ import annotations

from pydantic import BaseModel


class School(BaseModel):
    """One row of the directory, ready to be shown in a list."""

    #: The official name, as the register spells it — «МУНИЦИПАЛЬНОЕ
    #: БЮДЖЕТНОЕ ОБЩЕОБРАЗОВАТЕЛЬНОЕ УЧРЕЖДЕНИЕ "ГИМНАЗИЯ № 3"». Kept because
    #: it is the only unambiguous name; never the one shown first.
    full_name: str
    #: What goes on the screen: «МБОУ "Гимназия № 3"» where the register has a
    #: short form, the full name otherwise.
    name: str
    #: The OGRN registration number — thirteen digits, assigned once and never
    #: reused, which is why it is this project's handle on a school rather than
    #: the INN (reissued after
    #: a reorganisation) or the name (changes).
    ogrn: str | None = None
    inn: str | None = None
    #: The whole address in one line, and the city alone. Both, because the
    #: list needs the short one and the card the long one.
    address: str | None = None
    city: str | None = None
    region: str | None = None
    #: Whether the register still considers it operating. A liquidated school
    #: is kept rather than hidden: a class created in May may well belong to
    #: one merged over the summer, and «ликвидирована» beside the name is more
    #: use than an empty result.
    active: bool = True

    @property
    def label(self) -> str:
        """One line for a button: the name, and the city when there is one."""
        return f"{self.name} · {self.city}" if self.city else self.name


class SchoolPage(BaseModel):
    """One screenful of results, and whether there is more behind it."""

    items: list[School]
    #: One-based, as it is shown to a person.
    page: int
    pages: int
    total: int
    #: True when the upstream's own ceiling was hit and the answer is therefore
    #: «первые 20», not «все». Surfaced rather than hidden because the correct
    #: response is to type more of the name, and nothing else will say so.
    truncated: bool = False
