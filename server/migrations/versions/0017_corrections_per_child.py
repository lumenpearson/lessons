"""File the diary corrections under the child, not under a login.

Revision ID: 0017
Revises: 0016
Create Date: 2026-09-26

``diary_overrides.login`` held the key a family's corrections were filed under:
the Petersburg login, case-folded, and — only on a database that ran #140's
branch — ``netschool:`` and a SHA-256 of the region and the login. A phone's
registration names its own login and nothing upstream vouches for it, so that
key let one family name another's (#165). The owner decided on 26 September
2026 that the corrections are the **child's**: shared by everyone whose own
diary lists the child, reached only through that list. The column keeps its
name and now holds a scope, ``services/diary.child_scope``: ``CHILD:petersburg``,
or ``CHILD:netschool:`` and the regional server's host — those two shapes and
no other.

**Data only.** No table, column, index or constraint changes; three statements,
in this order, after a lock (below):

1. **Deletes every legacy «Сетевой город» row** — ``netschool:`` and 64
   characters, 74 in all. Its region is inside a hash: it could be recovered
   only by recomputing the hash from a diary session of the same login and
   region, which exists only while its author is still signed in, so some
   could be re-filed and the rest never. All of them go instead. Such rows
   exist only where #140's branch ran — ``main`` never wrote one — and what
   nobody can read or reset is exactly what the privacy policy promises not to
   keep. The count, before the transaction::

       SELECT count(*) FROM diary_overrides
        WHERE substr(login, 1, 10) = 'netschool:' AND length(login) = 74;

2. **Deletes the losers of every collision** among the Petersburg rows. Two
   logins that corrected the same field of the same child — two parents,
   which is the case this change exists for — become one row per field, and so
   does a legacy row beside a per-child row. The one kept is the newest by
   ``updated_at``, then the highest ``id``, as ``0013`` kept ``max(id)``: the
   value somebody would have been shown last. The rest is text somebody typed;
   what goes, per field of a child, before the transaction::

       SELECT student_id, target, field, count(*) - 1 AS lost
         FROM diary_overrides
        WHERE (substr(login, 1, 6) <> 'CHILD:' OR login = 'CHILD:petersburg')
          AND NOT (substr(login, 1, 10) = 'netschool:' AND length(login) = 74)
        GROUP BY 1, 2, 3 HAVING count(*) > 1;

   The legacy «Сетевой город» rows are left out of it because step 1 has
   already deleted them by the time this step ranks anything, whatever their
   dates. Deleted **before** the update, so ``uq_diary_override`` cannot fire —
   provided nothing writes in between, which is what the lock is for.
3. **Rewrites every remaining legacy row** to ``login = 'CHILD:petersburg'``,
   keeping ``student_id``, ``target``, ``field`` and the values. A legacy
   ``student_id`` cannot say which numbering it came from; it is taken as the
   person's ``identity.id``, the one the ``related-child-list`` items that
   third-party clients document carry. A child listed by a plain ``id``
   instead gets no corrections from now on (``DiaryService.scope_of``), so a
   legacy row that came from one would surface on whichever child has that
   number as a person id. Production holds none.

**The lock.** On PostgreSQL the first statement is ``LOCK TABLE diary_overrides
IN SHARE ROW EXCLUSIVE MODE``: reads go on, and a write waits for the commit.
Without it, applied while the new code runs (the order recommended below for a
deployment that holds corrections), a correction saved between step 2 and step
3 — a per-child row for a field that still has a legacy row — made step 3 raise
``UniqueViolation`` and the whole transaction roll back. With it, that save
waits, then meets the rewritten row in ``uq_diary_override``, which
``put_override`` already answers by updating that row. SQLite has no ``LOCK
TABLE``, so it is skipped there; migrate a SQLite file with the app stopped.

**Idempotent.** A second run finds no legacy row and no duplicate, which
``uq_diary_override`` rules out, and changes nothing. Portable SQL throughout,
so the same statements run on PostgreSQL and on SQLite, where the tests run
the chain through alembic.

A per-child row is one whose login starts with ``CHILD:`` in upper case, which
no legacy key can: those are casefolded or lower-case hex. ``substr`` rather
than ``LIKE`` because SQLite's ``LIKE`` ignores case. A legacy Petersburg login
that was literally ``netschool:`` followed by 64 characters would be taken for
the other kind and deleted; dnevnik2 signs nobody in with such a login, and
only this branch's ``/session`` ever stored one unchecked.

**On production it rewrites and deletes nothing.** Read through the Neon
connector on 26 September 2026: schema ``0014``, ``diary_overrides`` 0 rows,
``diary_sessions`` 0 rows. There it goes on **together with ``0015`` and
``0016``, before #140 merges**, in their one transaction — re-read the counts
first, since nothing stops a family writing a correction in between.

**On a deployment that holds corrections it is better applied just AFTER the
merge** — the other side from every additive revision, as ``0013`` was, though
for a different reason: nothing here breaks either code, but the old code and
the new file the same corrections under different keys, and each order loses
something made in the window between. Applied first, every rewritten row
vanishes from the running app until the deploy lands, and every correction
typed in that window is filed under a login the new code never reads — lost,
unless somebody runs these statements again by hand. Applied after, nothing
*typed* in the window is lost: new writes are already per-child, and the
rewrite folds the legacy rows into them, the newer value winning. What is lost
is a **reset** made in the window. The new code resets only per-child rows, so
a legacy row it could not see survives «Сбросить правки» or «Сбросить всё»,
and this revision then files it under the child — the value somebody just took
off comes back, for everyone who sees the child. The code cannot tell a reset
from a correction that was never made, so the only defence is a short window:
minutes, not a day. In it ``/api/v1/warmup`` reports «База отстала от кода»;
here that is the expected state, not an incident.

**Downgrade is a no-op, on purpose.** The logins are not recoverable from a
scope. Code at ``0016`` looks corrections up by a casefolded login or a
lower-case hash, neither of which can equal an upper-case scope, so it cannot
read a per-child row or write into one, and those rows stay as they are. What
such code *writes* is another matter, and a revert is not lossless: every
correction typed while older code runs against this database is filed under a
login, where the per-child code never reads it, and a redeploy does not re-run
this revision, because ``alembic_version`` already says ``0017``. So after any
window in which older code ran here — a revert, or this revision applied
before its merge — run the statements again by hand; they are idempotent. With
alembic, ``alembic downgrade 0016 && alembic upgrade head`` does the same,
because the downgrade does nothing and the upgrade runs this revision again.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0017"
down_revision: str | None = "0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

#: `services/diary.child_scope(PETERSBURG, None)`, written out: a revision must
#: not import the app it predates, or it changes when the app does. The colon
#: is escaped because `text()` reads «:name» as a bound parameter even inside a
#: quoted string.
_PETERSBURG = r"'CHILD\:petersburg'"

#: The key #140's branch built for «Сетевой город»: the provider, a colon and
#: a SHA-256 in lower-case hex.
_LEGACY_NETSCHOOL = "substr({t}login, 1, 10) = 'netschool:' AND length({t}login) = 74"

#: Everything that ends up under the Petersburg scope: that scope itself, and
#: every row that is not per-child (the «Сетевой город» ones are gone by then).
_PETERSBURG_SET = "(substr({t}login, 1, 6) <> 'CHILD:' OR {t}login = " + _PETERSBURG + ")"

#: Held to the commit: see the docstring. PostgreSQL only.
_LOCK = sa.text("LOCK TABLE diary_overrides IN SHARE ROW EXCLUSIVE MODE")

_DROP_LEGACY_NETSCHOOL = sa.text(
    "DELETE FROM diary_overrides WHERE " + _LEGACY_NETSCHOOL.format(t="")
)

#: A row loses to a row of the same field of the same child that is newer, or
#: as new and written later. `updated_at` is NOT NULL since `0011`, so the
#: comparison is total and exactly one row per group survives.
_DROP_COLLISIONS = sa.text(
    "DELETE FROM diary_overrides WHERE "
    + _PETERSBURG_SET.format(t="")
    + " AND EXISTS (SELECT 1 FROM diary_overrides AS keeper WHERE "
    + _PETERSBURG_SET.format(t="keeper.")
    + " AND keeper.student_id = diary_overrides.student_id"
    " AND keeper.target = diary_overrides.target"
    " AND keeper.field = diary_overrides.field"
    " AND (keeper.updated_at > diary_overrides.updated_at"
    " OR (keeper.updated_at = diary_overrides.updated_at"
    " AND keeper.id > diary_overrides.id)))"
)

_REFILE = sa.text(
    "UPDATE diary_overrides SET login = " + _PETERSBURG + " WHERE substr(login, 1, 6) <> 'CHILD:'"
)


def _has_table(table: str) -> bool:
    # Offline (`--sql`) there is nobody to ask, and the database the script is
    # written for has the table: `0009` made it. Asked of the migration context
    # rather than `alembic.context`, so a test that renders this revision
    # in-process runs this very guard, as `0015`'s does.
    if op.get_context().as_sql:
        return True
    return sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not _has_table("diary_overrides"):
        return
    if op.get_context().dialect.name == "postgresql":
        op.execute(_LOCK)
    op.execute(_DROP_LEGACY_NETSCHOOL)
    op.execute(_DROP_COLLISIONS)
    op.execute(_REFILE)


def downgrade() -> None:
    # See the docstring: nothing to put back, and nothing the old code can
    # misread — and doing nothing is what lets a downgrade and an upgrade
    # re-file what reverted code wrote.
    pass
