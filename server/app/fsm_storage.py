"""FSM storage backed by the database.

Why this exists: aiogram's default ``MemoryStorage`` keeps every multi-step
conversation in process memory. That is correct for a long-running process and
completely wrong for a serverless deployment, where each Telegram update may hit
a fresh instance. With memory storage on Vercel, a user picking a date for
homework would have the bot forget the date before they typed the text, and
every multi-step flow in the bot - homework, invites, замены, events, timetable
editing - would break in a way that looks random.

Keeping the state next to the data it edits also means a redeploy does not drop
half-finished conversations.

The table is deliberately dumb: one row per (bot, chat, user, thread, destiny),
holding the state name and a JSON blob. Rows are tiny and self-expiring by way
of :func:`purge_stale`, which the bot calls occasionally rather than on a timer.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any

from aiogram.fsm.state import State
from aiogram.fsm.storage.base import BaseStorage, StorageKey
from sqlalchemy import DateTime, String, Text, delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import async_sessionmaker
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base

# A conversation nobody touched for this long is abandoned. Long enough that a
# distracted admin can come back after lunch; short enough that the table does
# not accumulate rows for people who wandered off months ago.
STALE_AFTER = timedelta(days=2)


class FsmRecord(Base):
    """One in-progress conversation."""

    __tablename__ = "fsm_states"

    key: Mapped[str] = mapped_column(String(200), primary_key=True)
    state: Mapped[str | None] = mapped_column(String(200))
    data: Mapped[str] = mapped_column(Text, default="{}", nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


def _encode(key: StorageKey) -> str:
    """Flatten a StorageKey into one primary-key string.

    ``destiny`` separates parallel conversations for the same user, so it has to
    be part of the identity; dropping it would let two flows overwrite each
    other's state.
    """
    return ":".join(
        str(part)
        for part in (
            key.bot_id,
            key.chat_id,
            key.user_id,
            key.thread_id or 0,
            key.business_connection_id or "",
            key.destiny,
        )
    )


class DatabaseStorage(BaseStorage):
    """aiogram storage that survives the process it was created in."""

    def __init__(self, session_factory: async_sessionmaker) -> None:
        self._session_factory = session_factory

    async def set_state(self, key: StorageKey, state: State | str | None = None) -> None:
        resolved = state.state if isinstance(state, State) else state

        def apply(record: FsmRecord) -> None:
            record.state = resolved
            record.updated_at = datetime.utcnow()
            # Clearing the state ends the conversation, so its data goes too.
            # Leaving it behind is how a later flow picks up a stale key and
            # acts on a date the user chose an hour ago for something else.
            if resolved is None:
                record.data = "{}"

        # No point storing a row that only says "no state".
        await self._write(key, apply, create=resolved is not None, initial_state=resolved)

    async def get_state(self, key: StorageKey) -> str | None:
        async with self._session_factory() as session:
            record = await session.get(FsmRecord, _encode(key))
            return record.state if record else None

    async def set_data(self, key: StorageKey, data: dict[str, Any]) -> None:
        encoded = json.dumps(data, ensure_ascii=False)

        def apply(record: FsmRecord) -> None:
            record.data = encoded
            record.updated_at = datetime.utcnow()

        await self._write(key, apply, create=True, initial_data=encoded)

    async def _write(
        self,
        key: StorageKey,
        apply,
        *,
        create: bool,
        initial_state: str | None = None,
        initial_data: str = "{}",
    ) -> None:
        """Get-or-insert, then update - and survive losing the insert race.

        Two updates for the same user can be in flight at once (a double tap,
        or Telegram redelivering), each on its own serverless instance, each
        seeing no row and each inserting the same primary key. The loser used
        to raise IntegrityError out of the handler, which the webhook logged
        and the user saw as a button that did nothing. Now the loser rolls
        back, reads the row the winner made, and applies its change on top -
        which is what would have happened had the two arrived a moment apart.
        """
        encoded = _encode(key)
        async with self._session_factory() as session:
            record = await session.get(FsmRecord, encoded)
            if record is None:
                if not create:
                    return
                session.add(
                    FsmRecord(
                        key=encoded,
                        state=initial_state,
                        data=initial_data,
                        updated_at=datetime.utcnow(),
                    )
                )
                try:
                    await session.commit()
                    return
                except IntegrityError:
                    await session.rollback()
                    record = await session.get(FsmRecord, encoded)
                    if record is None:  # pragma: no cover - the winner vanished again
                        return
            apply(record)
            await session.commit()

    async def get_data(self, key: StorageKey) -> dict[str, Any]:
        async with self._session_factory() as session:
            record = await session.get(FsmRecord, _encode(key))
            if record is None:
                return {}
            try:
                loaded = json.loads(record.data)
            except (TypeError, ValueError):
                # Corrupt JSON must not wedge a user out of the bot forever;
                # an empty dict restarts the flow, which is recoverable.
                return {}
            return loaded if isinstance(loaded, dict) else {}

    async def close(self) -> None:
        """Nothing to release: sessions are per-call and the engine is shared."""

    async def purge_stale(self, older_than: timedelta = STALE_AFTER) -> int:
        """Delete abandoned conversations. Returns how many rows went."""
        cutoff = datetime.utcnow() - older_than
        async with self._session_factory() as session:
            stale = await session.scalars(
                select(FsmRecord.key).where(FsmRecord.updated_at < cutoff)
            )
            keys = list(stale)
            if keys:
                await session.execute(delete(FsmRecord).where(FsmRecord.key.in_(keys)))
                await session.commit()
            return len(keys)
