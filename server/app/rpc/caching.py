"""Which answers no cache may keep, for every transport v2 is served on.

The REST transcoder and the Connect adapters both read :data:`NEVER_STORED`
and nothing else. While the decision lived in ``rest/``, Connect's answers
carried no ``Cache-Control`` at all, and ``GetClass`` and ``GetCalendarFeed``
are ``NO_SIDE_EFFECTS``, so a Connect client could fetch the join code or the
feed's secret address with a ``GET`` a private cache was free to keep (#357).
It lives under ``rpc/`` because ``rest/`` already stands on ``rpc/`` and the
reverse would be a cycle.
"""

from __future__ import annotations

from app.rpc.methods import METHODS, Method

#: What a no-store answer carries, on every transport.
NO_STORE = "private, no-store"

#: Reads outside the diary that are as private: the class's calendar
#: subscription URL is a secret, and so is the class card's join code, which
#: admits a phone to an open class; a shared cache must keep neither.
NO_STORE_ALSO = frozenset(
    {"lessons.v2.MeService/GetCalendarFeed", "lessons.v2.ClassService/GetClass"}
)

#: Writes whose *answer* is a credential: the token a phone will use for good,
#: the class card ``UpdateClass`` answers with, join code included, the code
#: that links a phone to whoever sends it to the bot, until it is used, the
#: class's secret calendar address, which ``GetCalendarFeed`` keeps from
#: caches too, and the diary token ``CreateDiarySession`` answers with. Nobody
#: asked a cache to keep a POST or a PATCH, but the answer says so anyway, as
#: defence in depth.
NO_STORE_CREDENTIAL = frozenset(
    {
        "lessons.v2.DeviceService/CreateDevice",
        "lessons.v2.ClassService/UpdateClass",
        "lessons.v2.MeService/CreateLinkCode",
        "lessons.v2.MeService/CreateCalendarFeed",
        "lessons.v2.DiaryService/CreateDiarySession",
    }
)


def _never_stored(method: Method) -> bool:
    # «A read» is a method whose REST binding is a GET: a family's marks are
    # nobody's cache's, so every diary read is in, and so is what
    # NO_STORE_ALSO names. The binding is a fact of the contract, so Connect
    # asks the same question of a method whatever verb its caller used.
    if method.key in NO_STORE_CREDENTIAL:
        return True
    if method.binding is None or method.binding.verb != "get":
        return False
    return method.service == "lessons.v2.DiaryService" or method.key in NO_STORE_ALSO


#: Every method whose successful answer says :data:`NO_STORE`, keyed as
#: :data:`~app.rpc.methods.METHODS` is.
NEVER_STORED: frozenset[str] = frozenset(
    key for key, method in METHODS.items() if _never_stored(method)
)
