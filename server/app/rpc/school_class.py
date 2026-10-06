"""``ClassService``: the class itself, as «⚙️ Класс» runs it.

v1's ``/manage/class``, ``/manage/stats`` and ``/manage/terms…``, over the
same services. The card's patch is ``classes_service.update``, which v1's
``PATCH`` applies too, read here through an ``update_mask`` (``rpc/masks.py``,
AIP-134). Deleting the class is the owner's, confirmed by its name typed
back; it takes every device token with it, the caller's own included, so the
caller's next call is ``DEVICE_TOKEN_INVALID``
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 18).

The terms are read without seeding: a year with no rows is answered the
conventional set for the class's scheme, computed (``terms.spans``), where
v1's ``GET /manage/terms`` stored it on the read (the server-v2 design,
decision 10). The two writes store the year's set inside their own write, as
v1's do.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from app.contract.lessons.v2.common_pb import Term, TermKind
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.school_class_pb import (
    ClassStats,
    DeleteClassRequest,
    DeleteClassResponse,
    GetClassRequest,
    GetClassResponse,
    GetClassStatsRequest,
    GetClassStatsResponse,
    GetTermSchemeRequest,
    GetTermSchemeResponse,
    JoinMode,
    ListTermsRequest,
    ListTermsResponse,
    RoleCount,
    SubjectHours,
    TermScheme,
    UpdateClassRequest,
    UpdateClassResponse,
    UpdateTermRequest,
    UpdateTermResponse,
    UpdateTermSchemeRequest,
    UpdateTermSchemeResponse,
)
from app.contract.lessons.v2.school_class_pb import SchoolClass as Card
from app.models import SchoolClass
from app.models import TermKind as SchemeRow
from app.rpc import values
from app.rpc.errors import Refusal, validate
from app.rpc.masks import update_paths
from app.schemas import ClassPatch, TermBoundsIn
from app.services import stats as stats_service
from app.services import terms as terms_service
from app.services.manage import classes as classes_service
from app.services.manage import terms as terms_manage

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.rpc.call import Call

log = logging.getLogger(__name__)

#: What ``update_mask`` may name, and nothing more. The order a patch is
#: applied in is not this tuple's: ``classes_service.update`` decides it, the
#: fields in ``ClassPatch``'s order, the zone asked about before any is
#: written, the name recomposed after the grade and the letter, and the join
#: mode last.
CHANGEABLE = ("name", "grade", "letter", "school", "city", "timezone", "join_mode")

#: The ``optional`` fields of ``SchoolClass``: unset reads as ``0`` or ``""``,
#: and means none.
_OPTIONAL = frozenset({"grade", "letter", "school", "city"})

#: v2's join modes, as v1's ``ClassPatch`` spells them.
_JOIN_MODES = {JoinMode.OPEN: "open", JoinMode.INVITE: "invite"}

#: v2's schemes, as the model's.
_SCHEMES = {TermKind.QUARTER: SchemeRow.QUARTER, TermKind.SEMESTER: SchemeRow.SEMESTER}

#: Fixed, and naming no value: who may join is never decided by a field
#: nobody filled in (Ruling 18), and a year is cut one of two ways.
JOIN_MODE_REFUSED = "join_mode must be JOIN_MODE_OPEN or JOIN_MODE_INVITE"
TERM_KIND_REFUSED = "kind must be TERM_KIND_QUARTER or TERM_KIND_SEMESTER"


async def _card(session: AsyncSession, school_class: SchoolClass) -> Card:
    """The card «⚙️ Класс» draws: v1's ``ManagedClassOut``, with the grade and
    the letter."""
    counts = await classes_service.counts(session, school_class.id)
    return Card(
        id=school_class.id,
        name=school_class.name,
        grade=school_class.grade,
        letter=school_class.letter,
        school=school_class.school,
        city=school_class.city,
        timezone=school_class.timezone_name,
        timezone_label=classes_service.timezone_label(school_class),
        join_code=school_class.join_code,
        join_mode=JoinMode[school_class.join_mode.name],
        members=counts.members,
        devices=counts.devices,
        pending_requests=counts.pending,
        bell_schedule_id=school_class.bell_schedule_id,
        calendar_ready=bool(school_class.calendar_token),
    )


def _sent(card: Card, field: str) -> Any:
    """What the request says of ``field``, as v1's ``ClassPatch`` reads it:
    ``None`` for an optional field it leaves unset, since protobuf-py reads an
    unset one as ``0`` or ``""``, and the join mode as v1 spells it."""
    if field == "join_mode":
        return _JOIN_MODES[card.join_mode]
    if field in _OPTIONAL and not card.has_field(field):
        return None
    return getattr(card, field)


def _scheme(school_class: SchoolClass) -> TermScheme:
    """The scheme in force, and the school year in force for the class, read
    on the class's own clock."""
    return TermScheme(
        kind=values.term_kind(terms_service.scheme_of(school_class)),
        year=terms_manage.current_year(school_class),
    )


async def get_class(call: Call, request: GetClassRequest) -> GetClassResponse:
    """The card, the join code included, which is why it is an admin's. Writes nothing."""
    _admin, school_class = call.device_and_class()
    return GetClassResponse(school_class=await _card(call.session, school_class))


async def update_class(call: Call, request: UpdateClassRequest) -> UpdateClassResponse:
    """Rename the class, re-home it, move its zone or change who may join.

    The mask is read once, by ``masks.update_paths``. A masked field the
    request leaves out is cleared, except the name and the zone, which v1's
    ``ClassPatch`` refuses blank, and the join mode, which is refused here: a
    class is never opened or closed by a field nobody filled in. The patch is
    v1's (``classes_service.update``), one audit line per field: the letter
    read by the bot's rule, the zone asked about before anything is written,
    and the name recomposed from the grade and the letter unless the request
    names the class.
    """
    admin, school_class = call.device_and_class()
    sent = request.school_class if request.school_class is not None else Card()
    paths = update_paths(request.update_mask, request.school_class, CHANGEABLE)
    if "join_mode" in paths and sent.join_mode not in _JOIN_MODES:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            JOIN_MODE_REFUSED,
            violations=[("school_class.join_mode", JOIN_MODE_REFUSED)],
        )
    patch = validate(ClassPatch, {field: _sent(sent, field) for field in paths}, at="school_class.")
    await classes_service.update(
        call.session, school_class, admin.telegram_id, patch.model_dump(exclude_unset=True)
    )
    return UpdateClassResponse(school_class=await _card(call.session, school_class))


async def delete_class(call: Call, request: DeleteClassRequest) -> DeleteClassResponse:
    """Delete the class and everything hanging off it, every device token
    included, so the caller's own next call is ``DEVICE_TOKEN_INVALID``.

    The name typed back is the confirmation, as the bot makes an owner type
    it, give or take the spaces around it; anything else is
    ``VALIDATION_FAILED`` on ``confirmation``. Nothing goes to the log, which
    lives in the class and goes with it.
    """
    owner, school_class = call.device_and_class()
    class_id = school_class.id
    await classes_service.delete(call.session, school_class, request.confirmation)
    log.info("class %s deleted by %s", class_id, owner.telegram_id)
    return DeleteClassResponse()


async def get_class_stats(call: Call, request: GetClassStatsRequest) -> GetClassStatsResponse:
    """The numbers «📊 Статистика» shows: v1's ``/manage/stats``. Writes nothing."""
    _editor, school_class = call.device_and_class()
    numbers = await stats_service.class_stats(call.session, school_class)
    roles = numbers["members_by_role"]
    return GetClassStatsResponse(
        stats=ClassStats(
            today=values.date_string(numbers["today"]),
            lessons_per_week=numbers["lessons_per_week"],
            subjects_count=numbers["subjects_count"],
            subjects=[SubjectHours(name=name, hours=hours) for name, hours in numbers["subjects"]],
            homework_open=numbers["homework_open"],
            homework_total=numbers["homework_total"],
            # Owner first, as the bot lists them; v1 sent a dictionary.
            members_by_role=[
                RoleCount(role=values.role(role), count=roles[role])
                for role in sorted(roles, key=lambda role: role.rank, reverse=True)
            ],
            devices_active=numbers["devices_active"],
            # The bot's «Замен и особых дней впереди»: substitutions and
            # special days together, v1's overrides_upcoming (Ruling 31).
            substitutions_upcoming=numbers["overrides_upcoming"],
            events_upcoming=numbers["events_upcoming"],
        )
    )


async def get_term_scheme(call: Call, request: GetTermSchemeRequest) -> GetTermSchemeResponse:
    """Quarters or half-years, and the school year they are for. Never seeds."""
    _admin, school_class = call.device_and_class()
    return GetTermSchemeResponse(term_scheme=_scheme(school_class))


async def update_term_scheme(
    call: Call, request: UpdateTermSchemeRequest
) -> UpdateTermSchemeResponse:
    """Switch between quarters and half-years and reseed the year: four quarters
    and two halves do not map onto each other. A scheme that is neither is
    ``VALIDATION_FAILED`` on ``term_scheme.kind``."""
    admin, school_class = call.device_and_class()
    sent = request.term_scheme if request.term_scheme is not None else TermScheme()
    if sent.kind not in _SCHEMES:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            TERM_KIND_REFUSED,
            violations=[("term_scheme.kind", TERM_KIND_REFUSED)],
        )
    rows = await terms_manage.change_scheme(
        call.session, school_class, admin.telegram_id, _SCHEMES[sent.kind]
    )
    return UpdateTermSchemeResponse(
        term_scheme=_scheme(school_class),
        terms=[values.term(terms_service.TermSpan.of(row)) for row in rows],
    )


async def list_terms(call: Call, request: ListTermsRequest) -> ListTermsResponse:
    """This school year's terms: the stored ones, or the conventional set for
    the class's scheme, computed and never stored. Writes nothing."""
    _admin, school_class = call.device_and_class()
    year = terms_manage.current_year(school_class)
    spans = await terms_service.spans(call.session, school_class, year)
    return ListTermsResponse(year=year, terms=[values.term(span) for span in spans])


async def update_term(call: Call, request: UpdateTermRequest) -> UpdateTermResponse:
    """Move one term's edges, both at once, checked by v1's ``TermBoundsIn``.

    The year's set is stored first if the class has none, inside this write,
    as v1's does. Dates the year cannot hold are ``TERM_BOUNDS_REFUSED``, with
    the service's own sentence: past 31 May, overlapping a neighbour, ending
    before they start, or a term the year does not have.
    """
    admin, school_class = call.device_and_class()
    sent = request.term if request.term is not None else Term()
    bounds = validate(
        TermBoundsIn, {"starts_on": sent.starts_on, "ends_on": sent.ends_on}, at="term."
    )
    year = await terms_manage.move_term(
        call.session,
        school_class,
        admin.telegram_id,
        sent.index,
        bounds.starts_on,
        bounds.ends_on,
    )
    rows = await terms_service.read(call.session, school_class.id, year)
    return UpdateTermResponse(
        year=year, terms=[values.term(terms_service.TermSpan.of(row)) for row in rows]
    )
