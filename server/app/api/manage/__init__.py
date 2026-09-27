"""Running the class from the phone: the bot's management commands as an API.

Everything here has a twin in ``app.bot.handlers.manage`` - «📚 Предметы»,
«🔔 Звонки», «📱 Устройства», «📜 Журнал», «⚙️ Класс», export, import,
«📊 Статистика» and the access requests - and the twin is the authority. The
same minimum role guards the same operation on both surfaces, the same rows
move when a subject is renamed, and the same line lands in the audit log. An
admin who does something in the app and then opens the bot must find the class
in the state the app said it was in.

Two rules this package never bends:

* **The role is looked up per request from the linked Telegram account.**
  Nothing the client sends about itself is consulted, and there is no second
  permission system for phones: revoking somebody in the bot revokes their
  phone in the same instant (``linking.effective_role``, exactly as
  ``app.api.edit`` does it).
* **Every id is re-scoped by the query that reads it.** A subject, schedule,
  device or request is fetched by ``(id, class_id)``, so an id naming another
  class's row finds nothing rather than editing it.

Days, substitutions, events and homework are *not* here: they are the day-to-day
writes and they already live in ``app.api.edit`` under the editor's role.

One module per resource, each with a router of its own, and ``_common`` for
who is asking and the conversions they share. This used to be one file of
fifteen hundred lines; the names it defined are still importable from here.
"""

from fastapi import APIRouter

from app.api.manage import (
    bells,
    classes,
    devices,
    journal,
    requests,
    schools,
    statistics,
    subjects,
    terms,
    timetable,
)
from app.api.manage._common import Actor, admin_actor, editor_actor, owner_actor
from app.api.manage.bells import (
    bells_create,
    bells_delete,
    bells_list,
    bells_periods,
    bells_update,
)
from app.api.manage.classes import class_card, class_delete, class_update
from app.api.manage.devices import device_revoke, device_unlink, devices_list
from app.api.manage.journal import AUDIT_PAGE, audit_log
from app.api.manage.requests import request_approve, request_decline, requests_list
from app.api.manage.schools import schools_search
from app.api.manage.statistics import stats
from app.api.manage.subjects import subject_create, subject_delete, subject_update, subjects_list
from app.api.manage.terms import terms_list, terms_set_bounds, terms_set_scheme
from app.api.manage.timetable import timetable_export, timetable_import
from app.api.routing import DishkaAnnotatedRoute

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/api/v1/manage", tags=["manage"])

# The order a request is matched in, written down once here rather than implied
# by which module happens to import which. It is the order the routes stood in
# when this was one file, and none of them moved.
router.include_router(classes.router)
router.include_router(subjects.router)
router.include_router(bells.router)
router.include_router(timetable.router)
router.include_router(devices.router)
router.include_router(journal.router)
router.include_router(statistics.router)
router.include_router(requests.router)
router.include_router(terms.router)
router.include_router(schools.router)

__all__ = [
    "AUDIT_PAGE",
    "Actor",
    "admin_actor",
    "audit_log",
    "bells_create",
    "bells_delete",
    "bells_list",
    "bells_periods",
    "bells_update",
    "class_card",
    "class_delete",
    "class_update",
    "device_revoke",
    "device_unlink",
    "devices_list",
    "editor_actor",
    "owner_actor",
    "request_approve",
    "request_decline",
    "requests_list",
    "router",
    "schools_search",
    "stats",
    "subject_create",
    "subject_delete",
    "subject_update",
    "subjects_list",
    "terms_list",
    "terms_set_bounds",
    "terms_set_scheme",
    "timetable_export",
    "timetable_import",
]
