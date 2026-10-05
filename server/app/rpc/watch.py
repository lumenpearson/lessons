"""``WatchService``: the class changing, as it happens — a beta of the host target.

No target streams in 3a. Vercel never will: it runs this app under HTTP/1.1
with no trailers and no connection held open between requests, which is the
programme's reason for a second target. 3c adds the stream on the host, read
from its deployment marker (``LESSONS_TARGET=host``) with
``LESSONS_STREAMING=true``; until then, and on Vercel always, the method says
the feature is not here, after the gate has checked the caller like any other
(``docs/specs/2026-10-05-server-v2-design.md``, decision 7).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.watch_pb import WatchClassRequest, WatchClassResponse
from app.rpc.errors import Refusal

if TYPE_CHECKING:
    from app.rpc.call import Call

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a server capability, as
#: ``errors.proto`` allows beside a ``DiaryFeature`` name.
STREAMING = "streaming"

NOT_HERE = "Streaming is served by the host target, not by this deployment"


async def watch_class(call: Call, request: WatchClassRequest) -> WatchClassResponse:
    raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NOT_HERE, feature=STREAMING)
