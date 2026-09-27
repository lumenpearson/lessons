"""Running the class: subjects, special days, bells, devices, the log, import.

This is the structural half of the bot - everything that shapes what the
day-to-day flows in ``content.py`` and ``timetable.py`` are allowed to say.

Three rules hold everywhere in this package, and each of them is the answer to
a way the previous shape of this code could be abused:

* **A role check on every handler, including every FSM step** - the
  ``@needs(Role.X)`` under its ``@router…`` line (``_common.needs``). FSM
  state is per-user and therefore attacker-controlled: a client can set
  itself into ``EditSubject.name`` and send a message, so a step that trusted
  the step before it would be a rename with no permission check at all.
* **Every id arrives as text and is re-scoped by the query that reads it.**
  Callback data is user-supplied. A subject is never fetched by id alone but
  by ``(id, class_id)``, so a crafted payload naming another class's row finds
  nothing rather than editing it. The queries are ``app.services.manage``'s;
  no handler here runs SQL of its own.
* **No state in the process.** Every Vercel invocation is a fresh Python
  process, so anything that has to survive a step lives in FSM storage (the
  database) or in the callback payload - never in a module-level dict.

User-supplied text is escaped once, at render time, by ``manage_render``; the
few strings built here are escaped in place for the same reason.

One module per screen of «⚙️ Класс», each with a router of its own, and
``_common`` for what they share. This used to be one file of three and a half
thousand lines, where a change to the bells meant scrolling past the subjects.
The names every module defines are still importable from here, so
``from app.bot.handlers.manage import cmd_bells`` means what it always meant.
"""

from aiogram import Router

from app.bot.handlers.manage import (
    audit_log,
    bells,
    calendar_feed,
    class_card,
    devices,
    diary_binding,
    holidays,
    import_export,
    requests,
    stats,
    subjects,
    terms,
)
from app.bot.handlers.manage._common import (
    NEED_ADMIN,
    NEED_EDITOR,
    NEED_OWNER,
    NO_ACCESS,
    _day_kind_or_none,
)
from app.bot.handlers.manage.audit_log import audit_page, cmd_log
from app.bot.handlers.manage.bells import (
    BELLS_ROWS_HELP,
    _parse_bells,
    bells_create,
    bells_delete,
    bells_edit,
    bells_list,
    bells_make_default,
    bells_new_name,
    bells_new_rows,
    bells_rows_apply,
    cmd_bells,
)
from app.bot.handlers.manage.calendar_feed import calendar_card, calendar_rotate, cmd_calendar
from app.bot.handlers.manage.class_card import (
    class_delete_apply,
    class_delete_prompt,
    class_field_apply,
    class_field_prompt,
    class_root,
    class_switch,
    class_switch_to,
    cmd_class,
)
from app.bot.handlers.manage.devices import cmd_devices, device_revoke, device_unlink, devices_list
from app.bot.handlers.manage.diary_binding import (
    class_diary_bind,
    class_diary_cancel,
    class_diary_provider,
    class_diary_region,
    class_diary_school,
    class_diary_search,
)
from app.bot.handlers.manage.holidays import (
    _KIND_BY_TAG,
    _KIND_SUMMARY,
    HOLIDAY_DATE_HELP,
    NOTE_MAX,
    PERIOD_MAX_DAYS,
    _holiday_view,
    cmd_holidays,
    holiday_add,
    holiday_bells,
    holiday_delete,
    holiday_kind,
    holiday_note,
    holiday_period_apply,
    holiday_period_kind,
    holiday_period_start,
    holiday_pick_date,
    holiday_typed_date,
    holidays_list,
)
from app.bot.handlers.manage.import_export import (
    IMPORT_HELP,
    cmd_export,
    cmd_import,
    import_apply,
    import_cancel,
    import_preview,
)
from app.bot.handlers.manage.requests import (
    cmd_link,
    cmd_request,
    request_approve,
    request_decline,
    request_message,
)
from app.bot.handlers.manage.stats import SEARCH_BACK_DAYS, SEARCH_MAX, cmd_find, cmd_stats
from app.bot.handlers.manage.subjects import (
    COLOUR_RE,
    SHORT_NAME_MAX,
    SUBJECT_NAME_MAX,
    TEACHER_MAX,
    cmd_subjects,
    subject_add,
    subject_colour_pick,
    subject_colour_typed,
    subject_create,
    subject_delete,
    subject_field,
    subject_open,
    subject_rename,
    subject_short_name,
    subject_teacher,
    subjects_collect,
    subjects_list,
)
from app.bot.handlers.manage.terms import (
    term_edit_apply,
    term_edit_prompt,
    terms_list,
    terms_scheme,
)

router = Router(name="manage")

# The order an update is offered to the screens in, written down once here
# rather than implied by which module happens to import which. It is the
# order the handlers stood in when this was one file, with one exception that
# changes nothing: «📅 Календарь»'s two buttons used to sit after the diary
# binding's five, and every one of those seven matches its own
# `ManageAction.action` or `DiarySchoolPick`, so no press can match two.
router.include_routers(
    subjects.router,
    holidays.router,
    bells.router,
    devices.router,
    audit_log.router,
    class_card.router,
    calendar_feed.router,
    diary_binding.router,
    import_export.router,
    stats.router,
    requests.router,
    terms.router,
)

__all__ = [
    "BELLS_ROWS_HELP",
    "COLOUR_RE",
    "HOLIDAY_DATE_HELP",
    "IMPORT_HELP",
    "NEED_ADMIN",
    "NEED_EDITOR",
    "NEED_OWNER",
    "NOTE_MAX",
    "NO_ACCESS",
    "PERIOD_MAX_DAYS",
    "SEARCH_BACK_DAYS",
    "SEARCH_MAX",
    "SHORT_NAME_MAX",
    "SUBJECT_NAME_MAX",
    "TEACHER_MAX",
    "_KIND_BY_TAG",
    "_KIND_SUMMARY",
    "_day_kind_or_none",
    "_holiday_view",
    "_parse_bells",
    "audit_page",
    "bells_create",
    "bells_delete",
    "bells_edit",
    "bells_list",
    "bells_make_default",
    "bells_new_name",
    "bells_new_rows",
    "bells_rows_apply",
    "calendar_card",
    "calendar_rotate",
    "class_delete_apply",
    "class_delete_prompt",
    "class_diary_bind",
    "class_diary_cancel",
    "class_diary_provider",
    "class_diary_region",
    "class_diary_school",
    "class_diary_search",
    "class_field_apply",
    "class_field_prompt",
    "class_root",
    "class_switch",
    "class_switch_to",
    "cmd_bells",
    "cmd_calendar",
    "cmd_class",
    "cmd_devices",
    "cmd_export",
    "cmd_find",
    "cmd_holidays",
    "cmd_import",
    "cmd_link",
    "cmd_log",
    "cmd_request",
    "cmd_stats",
    "cmd_subjects",
    "device_revoke",
    "device_unlink",
    "devices_list",
    "holiday_add",
    "holiday_bells",
    "holiday_delete",
    "holiday_kind",
    "holiday_note",
    "holiday_period_apply",
    "holiday_period_kind",
    "holiday_period_start",
    "holiday_pick_date",
    "holiday_typed_date",
    "holidays_list",
    "import_apply",
    "import_cancel",
    "import_preview",
    "request_approve",
    "request_decline",
    "request_message",
    "router",
    "subject_add",
    "subject_colour_pick",
    "subject_colour_typed",
    "subject_create",
    "subject_delete",
    "subject_field",
    "subject_open",
    "subject_rename",
    "subject_short_name",
    "subject_teacher",
    "subjects_collect",
    "subjects_list",
    "term_edit_apply",
    "term_edit_prompt",
    "terms_list",
    "terms_scheme",
]
