"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
fills this in as it goes (so far ``GetMe``, ``GetDiaryCapabilities`` and
``WatchClass``'s refusal); 3b does the
rest, one service at a time.

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.rpc import diary, me, watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
