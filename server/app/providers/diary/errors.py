"""What can go wrong with a diary, named so a route can answer it.

An upstream speaks HTTP and sometimes speaks HTML at us; the rest of the
application should not have to tell "wrong password" from "session over" from
"they are down" from "they changed the response" from "they will not talk to
our address". Each needs a different answer at the API edge, so each is a type.

`api/diary._guard` maps this family to a status code; anything outside it is a
500. `app.providers.petersburg` re-exports :class:`DiaryError` under its old
name :class:`PetersburgError`, so both spellings are the one class.
"""

from __future__ import annotations


class DiaryError(Exception):
    """Base for everything a diary provider raises."""

    #: What the client is told. Deliberately short and Russian - these reach a
    #: phone screen, not a log reader.
    message = "Электронный дневник недоступен"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.message)
        self.message = message or self.message


class BadCredentials(DiaryError):
    """The login or the password is wrong. Nothing to retry."""

    message = "Неверный логин или пароль"


class PasswordExpired(BadCredentials):
    """The account's own password has expired upstream.

    A :class:`BadCredentials`, because there is nothing this end can retry, but
    with its own message: retyping the same password will not help, and the fix
    is on the region's own site. Kept apart so the web form can say which of
    the two it is rather than «неверный пароль» over a password that was right
    until last week.
    """

    message = "Пароль в дневнике устарел — смените его на сайте региона, потом войдите снова"


class SessionExpired(DiaryError):
    """The stored session is no longer accepted.

    Distinct from :class:`BadCredentials` because the answer is different: the
    person has not changed their password, they have simply been away long
    enough. The client turns this into «войдите снова», not «вы ошиблись».
    """

    message = "Сессия дневника истекла — войдите заново"


class UpstreamUnavailable(DiaryError):
    """A timeout, a connection failure, or a 5xx.

    The one failure that is safe to forgive on the sign-in path: nothing ever
    looked at what was typed, so the ticket goes back and the throttle does not
    count it.
    """

    message = "Электронный дневник не отвечает"


class AddressRefused(UpstreamUnavailable):
    """The region's server will not talk to this deployment's address.

    A subclass of :class:`UpstreamUnavailable` — nothing looked at the
    password, so the ticket is handed back and the throttle does not count it —
    but a *permanent* refusal rather than a passing one: a Russian regional
    server dropping a foreign or datacenter address, seen as a WAF block page,
    a 403, or a connection reset. Told apart from a timeout because retrying
    cannot help and the family must hear that it is not their password and not
    worth trying again; the keep-alive trips a per-origin breaker on it rather
    than pinging that origin's other sessions into the same wall.
    """

    message = (
        "Сайт дневника региона не пускает запросы с адреса нашего сервера — "
        "дело не в пароле, и повтор не поможет. Сообщите администратору класса."
    )


class SignInUnsupported(DiaryError):
    """The region takes only Госуслуги, so a login and password cannot get in.

    Raised only from the check made on the sign-in options *before* any
    credential is sent (`_login_allowed`), so it is safe to hand the ticket
    back and not count it: nothing looked at a password, and none was sent.
    A different answer from :class:`BadCredentials`, which would send the family
    to retype a password the region was never going to accept.
    """

    message = (
        "В этом регионе дневник впускает только через Госуслуги — "
        "логин и пароль тут не подойдут"
    )


class UnexpectedResponse(DiaryError):
    """A 200 whose body is not what the provider knows how to read.

    Its own type because it means *this code* needs changing, not the network
    and not the user's password - and because it is the failure an undocumented
    API produces when it is quietly redesigned.
    """

    message = "Электронный дневник ответил непонятно"
