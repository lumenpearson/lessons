"""What one call of ``/api/v1/cron/tick`` did — the clock the server does not have."""

from __future__ import annotations

from pydantic import BaseModel


class TickOut(BaseModel):
    morning: int
    evening: int
    tasks: int
    failed: int
    fsm_purged: int
    join_attempts_purged: int
    diary_sessions_purged: int = 0
    device_tokens_purged: int = 0
    diary_links_purged: int = 0
    device_invites_purged: int = 0
    #: The keep-alive that holds a «Сетевой город» session open across its short
    #: idle window: how many were pinged alive, how many the upstream had already
    #: dropped, and true when the keep-alive itself raised (isolated so a diary
    #: fault never fails the whole tick and reddens the clock).
    diary_sessions_kept_alive: int = 0
    diary_sessions_lost: int = 0
    diary_keepalive_failed: bool = False
