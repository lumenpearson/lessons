"""Which methods this deployment serves, and the handler of each.

The table is empty at this point and is filled method by method from the
next task on. A method missing here answers ``UNIMPLEMENTED`` on both
transports, before any gate or scope, exactly as the generated ``Protocol``'s
default does.

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {}
