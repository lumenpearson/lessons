"""What can go wrong upstream, named so a route can answer it.

The upstream speaks HTTP and sometimes speaks HTML at us; the rest of the
application should not have to tell the difference between "wrong password",
"session over", "they are down" and "they changed the response". Each of those
needs a different answer at the API edge, so each is a type here.
"""

from __future__ import annotations


class PetersburgError(Exception):
    """Base for everything this provider raises."""

    #: What the client is told. Deliberately short and Russian - these reach a
    #: phone screen, not a log reader.
    message = "Электронный дневник недоступен"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.message)
        self.message = message or self.message


class BadCredentials(PetersburgError):
    """The login or the password is wrong. Nothing to retry."""

    message = "Неверный логин или пароль"


class SessionExpired(PetersburgError):
    """The stored session is no longer accepted.

    Distinct from :class:`BadCredentials` because the answer is different: the
    person has not changed their password, they have simply been away long
    enough. The client turns this into "войдите снова", not "вы ошиблись".
    """

    message = "Сессия дневника истекла — войдите заново"


class UpstreamUnavailable(PetersburgError):
    """A timeout, a connection failure, or a 5xx."""

    message = "Электронный дневник не отвечает"


class UnexpectedResponse(PetersburgError):
    """A 200 whose body is not what this provider knows how to read.

    Its own type because it means *this code* needs changing, not the network
    and not the user's password - and because it is the failure an undocumented
    API produces when it is quietly redesigned.
    """

    message = "Электронный дневник ответил непонятно"
