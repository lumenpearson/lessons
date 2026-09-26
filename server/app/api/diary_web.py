"""The one HTML page this project serves, and why it is worth the exception.

`CLAUDE.md` says there is no web frontend, and that is still true of the app:
there is no dashboard, no session cookie and no password reset. This is a
single server-rendered form with no JavaScript, and it exists for one reason
that the bot cannot satisfy — a password typed into a Telegram chat is in the
chat history, on Telegram's servers, in the notification on a locked screen
and in that phone's backup, and deleting the message undoes none of it.

So the password is taken here instead: over HTTPS, from the browser to this
server, which passes it to the diary once and writes it down nowhere. It does
cross this server. The app's own sign-in does not — it talks to the diary
directly and hands over only the session — so this page, and the
``POST /api/v1/diary/login`` older apps still call, are where a family's
password passes through us, and they are described that way.

The page holds no state of its own. It sets no cookie, because the session it
opens belongs to a Telegram account rather than to whichever browser happened
to be handy. It is reached once, with a ticket the bot handed out
(``services/diary_link``), and the ticket is spent by an attempt — a right
password and a wrong one cost the same. The one thing that does not cost it is
the diary being unreachable, where nothing ever looked at the password and so
no guess was made (see ``_unspend``, which says why that line is drawn there
and not further along).

Three headers do the rest of the work, and each answers a specific leak:

``Referrer-Policy: no-referrer``
    The ticket is in the URL. Any link or image the browser fetched from this
    page would carry it in ``Referer``; there are none, and this makes that
    true even if one is added later.
``Cache-Control: no-store``
    A shared laptop's back button should not re-serve a form holding a
    half-typed password, and a proxy should not keep the page at all.
``X-Robots-Tag: noindex``
    A ticket URL pasted somewhere public is worth fifteen minutes; it should
    not also be worth a search result.
"""

from __future__ import annotations

import logging
from html import escape
from typing import NamedTuple
from urllib.parse import parse_qs, urlsplit

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse
from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.routing import DishkaAnnotatedRoute
from app.crypto import diary_enabled
from app.models import DiaryLinkCode, SchoolClass
from app.providers.diary.errors import (
    BadCredentials,
    DiaryError,
    NoStudents,
    SignInUnsupported,
    UpstreamUnavailable,
)
from app.providers.diary.registry import Binding
from app.providers.diary.registry import binding as class_binding
from app.security import hash_token
from app.services import diary as diary_service
from app.services import diary_link

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/diary", tags=["diary-web"])

_HEADERS = {
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store, max-age=0",
    "X-Robots-Tag": "noindex, nofollow",
    "X-Content-Type-Options": "nosniff",
    # No inline script, no external anything. Written down rather than assumed,
    # so that an added <script> fails visibly instead of running.
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'",
}

_STYLE = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body {
  margin: 0; min-height: 100vh; display: grid; place-items: center;
  padding: 24px;
  font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: #f3f4f6; color: #111827;
}
main { width: 100%; max-width: 26rem; background: #fff; border-radius: 16px;
  padding: 28px 24px; box-shadow: 0 1px 3px rgba(0,0,0,.1); }
h1 { font-size: 1.25rem; margin: 0 0 4px; }
p.sub { margin: 0 0 20px; color: #6b7280; font-size: .9rem; }
label { display: block; font-size: .85rem; font-weight: 600; margin: 14px 0 6px; }
input { width: 100%; padding: 11px 12px; font-size: 1rem; border-radius: 10px;
  border: 1px solid #d1d5db; background: #fff; color: inherit; }
input:focus { outline: 2px solid #2563eb; outline-offset: 1px; border-color: #2563eb; }
button { width: 100%; margin-top: 20px; padding: 12px; font-size: 1rem; font-weight: 600;
  border: 0; border-radius: 10px; background: #2563eb; color: #fff; cursor: pointer; }
.note { margin-top: 18px; font-size: .8rem; color: #6b7280; }
.ok { font-size: 2rem; margin: 0 0 8px; }
@media (prefers-color-scheme: dark) {
  body { background: #0b0f19; color: #e5e7eb; }
  main { background: #151b28; box-shadow: none; }
  p.sub, .note { color: #9ca3af; }
  input { background: #0b0f19; border-color: #374151; }
}
"""


def _page(title: str, body: str, status: int = 200) -> HTMLResponse:
    return HTMLResponse(
        "<!doctype html><html lang=ru><head><meta charset=utf-8>"
        '<meta name=viewport content="width=device-width,initial-scale=1">'
        f"<title>{escape(title)}</title><style>{_STYLE}</style></head>"
        f"<body><main>{body}</main></body></html>",
        status_code=status,
        headers=_HEADERS,
    )


#: What to do next when the ticket is gone: there is no way back but the bot.
_ASK_AGAIN = "Вернитесь в бота и запросите ссылку заново."

#: What to do next when the ticket survived, because the failure was the
#: diary's. Said out loud, because a page that only reports a problem reads as
#: «start over» and starting over here means a trip back to Telegram.
_TRY_AGAIN = "Эта ссылка ещё действует — откройте её снова и попробуйте ещё раз."


def _closed(message: str, status: int = 400, note: str = _ASK_AGAIN) -> HTMLResponse:
    return _page(
        "Дневник",
        f"<h1>Не получилось</h1><p class=sub>{escape(message)}</p>"
        f"<p class=note>{escape(note)}</p>",
        status=status,
    )


async def _binding_of(session: AsyncSession, class_id: int | None) -> Binding | None:
    """The diary binding of the ticket's class, or ``None`` if it has none now.

    Read at both the GET and the POST, never trusted from when the ticket was
    minted: an admin who unbinds or rebinds the class between the two must not
    have the password sent to a diary the family is no longer looking at (#137).
    """
    if class_id is None:
        return None
    school_class = await session.get(SchoolClass, class_id)
    if school_class is None:
        return None
    return class_binding(school_class)


def _where(binding: Binding | None) -> str:
    """The subtitle line under the form's heading, escaped.

    Names the provider, and for «Сетевой город» its region and school, so the
    family can see which diary and which school this password goes to. Every
    part is escaped: the school name comes from the upstream's search.
    """
    if binding is None:
        return "Санкт-Петербург · dnevnik2.petersburgedu.ru"
    parts = [binding.provider.title]
    if binding.region:
        from app.providers.netschool import regions

        region = regions.get(binding.region)
        if region is not None:
            parts.append(region.title)
        if binding.school_name:
            parts.append(binding.school_name)
    elif binding.provider.site:
        parts.append(binding.provider.site)
    return " · ".join(escape(part) for part in parts)


def _form(code: str, binding: Binding | None = None) -> HTMLResponse:
    # The code goes in the action rather than a hidden field: it is already in
    # the URL the browser is on, and one place for it is one place to get
    # wrong. autocomplete is on, so a password manager can fill this — the
    # people using it are signing in to an account they already have.
    #
    # It takes no error to show, and cannot: the ticket is spent by the attempt,
    # so a form redrawn with «неверный пароль» above it would be a form whose
    # submit button no longer works. Every failure ends on `_closed` instead,
    # which says whether the link is still worth anything. The parameter and
    # the `401` behind it were here from the first draft and nothing ever passed
    # one — an unused branch in a renderer is how a page ends up written for an
    # answer it will never be handed.
    #
    # The note under the button said the password went «прямо в дневник», and
    # it never did: this form posts to our own path (`form-action 'self'`), and
    # `sign_in_submit` hands the password to the provider (#150). So it says
    # what is true of this page — typed here rather than in the chat, relayed
    # by this server for the sign-in, kept nowhere — and nothing about the
    # app, whose own sign-in skips this server but only from a build that has
    # it; an older one still posts the password to `/api/v1/diary/login`, and
    # this page cannot tell which one the reader holds. «Для входа» rather
    # than «один раз»: «Сетевой город» may ask for a role and take a second
    # login POST inside the same sign-in.
    return _page(
        "Вход в дневник",
        f"<h1>Электронный дневник</h1>"
        f"<p class=sub>{_where(binding)}</p>"
        f'<form method=post action="/diary/signin/{escape(code)}">'
        "<label for=login>Логин</label>"
        "<input id=login name=login type=text inputmode=email autocomplete=username "
        "autocapitalize=off autocorrect=off required autofocus>"
        "<label for=password>Пароль</label>"
        "<input id=password name=password type=password "
        "autocomplete=current-password required>"
        "<button type=submit>Войти</button>"
        "</form>"
        "<p class=note>Пароль вводится здесь, а не в чате. Эта страница — на "
        "сервере бота: он передаёт пароль дневнику для входа, и пароль нигде "
        "не сохраняется — ни у бота, ни в этой базе. Хранится только сессия "
        "дневника, в зашифрованном виде, и её всегда можно отозвать кнопкой "
        "«Выйти».</p>",
    )


@router.get("/signin/{code}", response_class=HTMLResponse)
async def sign_in_form(
    code: str, *, session: FromDishka[AsyncSession]
) -> HTMLResponse:
    """The form. The ticket is *checked* here but not spent — spending it on a
    GET would mean a link preview or a prefetch burned it before anybody typed
    anything."""
    if not diary_enabled():
        return _closed("Дневник на этом сервере выключен.", status=503)
    row = await _peek(session, code)
    if row is None:
        return _closed("Ссылка уже использована или устарела.", status=410)
    binding = await _binding_of(session, row.class_id)
    if binding is None:
        return _closed(
            "Дневник в этом классе больше не привязан.", status=410,
            note="Попросите у администратора класса новую ссылку.",
        )
    return _form(code, binding)


#: Refuse a body larger than this before reading it.
#:
#: The real form is two short fields. Anything bigger is either a mistake or
#: somebody making the server hold a megabyte in memory per request, and a
#: serverless function's memory is the thing it has least of.
MAX_BODY = 8 * 1024


async def _body_within_limit(request: Request) -> bytes | None:
    """The body, or ``None`` if it is larger than :data:`MAX_BODY`.

    Read chunk by chunk with a running total. ``await request.body()`` buffers
    the whole thing first and only then lets anything measure it, so the
    constant above described a guard that did not exist: the megabyte was
    already in the function's memory by the time it was called too big. This
    stops at the first chunk that crosses the line and never holds more than
    that.
    """
    size = 0
    chunks: list[bytes] = []
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BODY:
            return None
        if chunk:
            chunks.append(chunk)
    return b"".join(chunks)


async def _fields(request: Request) -> tuple[str, str] | None:
    """The two fields, or ``None`` for a body that is not this form's.

    Parsed here rather than through FastAPI's ``Form(...)``, which requires
    ``python-multipart`` — a dependency this project would be adding for a
    format it never uses. The form is ``application/x-www-form-urlencoded``,
    two fields, no upload; ``parse_qs`` is the whole parser it needs, and the
    function bundle stays one package smaller.
    """
    kind = request.headers.get("content-type", "").split(";")[0].strip()
    if kind != "application/x-www-form-urlencoded":
        return None
    raw = await _body_within_limit(request)
    if raw is None:
        return None
    try:
        fields = parse_qs(raw.decode("utf-8"), keep_blank_values=True)
    except UnicodeDecodeError:
        return None
    login = (fields.get("login") or [""])[0].strip()
    password = (fields.get("password") or [""])[0]
    if not login or not password:
        return None
    return login, password


@router.post("/signin/{code}", response_class=HTMLResponse)
async def sign_in_submit(
    code: str,
    request: Request,
    *,
    session: FromDishka[AsyncSession],
) -> HTMLResponse:
    if not diary_enabled():
        return _closed("Дневник на этом сервере выключен.", status=503)

    fields = await _fields(request)
    if fields is None:
        # Deliberately before the ticket is spent: a malformed body has not
        # attempted a sign-in, and burning the link for it would mean a
        # mistyped form costs a trip back to the bot.
        return _closed("Заполните логин и пароль.", status=400)
    login, password = fields

    # Peek at the ticket first, and read the class's binding, before spending
    # anything. If the class was unbound or rebound since the link was minted,
    # the password must not go to the diary that is bound now (#137): refuse
    # without spending the ticket and without any upstream call.
    peeked = await _peek(session, code)
    if peeked is None:
        return _closed("Ссылка уже использована или устарела.", status=410)
    binding = await _binding_of(session, peeked.class_id)
    if binding is None:
        return _closed(
            "Дневник в этом классе больше не привязан — запросите новую ссылку.",
            status=410,
        )

    ticket = await diary_link.claim(session, code)
    if ticket is None:
        return _closed("Ссылка уже использована или устарела.", status=410)
    # Read before the call, not after: the rollback below expires the instance,
    # and reading an attribute off an expired one inside an async session is
    # lazy IO where none is allowed.
    ticket_id = ticket.id

    try:
        _, opened = await diary_service.sign_in(
            session,
            login,
            password,
            telegram_id=ticket.telegram_id,
            provider=binding.provider.key,
            region=binding.region,
            school_id=binding.school_id,
        )
    except DiaryError as error:
        # A wrong password costs the ticket; the diary being down does not.
        verdict = _why(error, binding)
        if verdict.keep_ticket:
            await _unspend(session, ticket_id)
        return _closed(
            verdict.message,
            status=verdict.status,
            note=verdict.note or (_TRY_AGAIN if verdict.keep_ticket else _ASK_AGAIN),
        )
    except Exception:  # noqa: BLE001 - never let an upstream shape reach the page
        log.exception("diary sign-in failed")
        # The ticket stays spent. This catches everything, including whatever
        # we might raise *after* the upstream has already judged the password,
        # so it cannot be told apart from an attempt — and an attempt is what
        # the ticket pays for.
        return _closed("Что-то пошло не так. Попробуйте ещё раз.", status=500)

    opened.class_id = ticket.class_id
    await session.commit()

    return _page(
        "Готово",
        "<p class=ok>✅</p><h1>Вы вошли</h1>"
        f"<p class=sub>{escape(opened.login)}</p>"
        "<p class=note>Можно закрыть эту страницу и вернуться в Telegram — "
        "дневник уже там.</p>",
    )


class _Verdict(NamedTuple):
    """What to say, what to answer, and whether the ticket is forfeit."""

    message: str
    status: int
    #: True when the failure was the diary's rather than the person's, so the
    #: ticket goes back and the same link still opens the form.
    keep_ticket: bool
    #: What to do next, when neither «попробуйте ещё раз» nor «запросите
    #: ссылку заново» is true. Chosen by `keep_ticket` alone, the Госуслуги
    #: refusal told the family to retry what it had just said cannot work.
    note: str | None = None


def _site_of(binding: Binding) -> str:
    """The address a family can open in a browser to see whether *their*
    diary is up: the regional server for «Сетевой город», dnevnik2 otherwise.

    Taken from the allow-list, never from anything typed, so it is safe to
    show. It used to be dnevnik2 whatever the class was bound to, which sent a
    «Сетевой город» family to Петербург's diary to find out why theirs had
    answered nonsense (#158).
    """
    if binding.region:
        from app.providers.netschool import regions

        region = regions.get(binding.region)
        if region is not None:
            return urlsplit(region.origin).netloc
    return binding.provider.site or "dnevnik2.petersburgedu.ru"


def _why(error: DiaryError, binding: Binding) -> _Verdict:
    """The upstream's failure, said in a way the reader can act on.

    Only :class:`BadCredentials` is «неверный пароль». Everything else said so
    too until this was fixed, and the failure that matters is
    :class:`UnexpectedResponse`: the upstream answers a *200 of HTML* when it
    is in maintenance or showing a captcha, which ``PetersburgClient.login``
    turns into exactly that type (see the comment there — the phone's half of
    this was fixed first). Rendered as «неверный логин или пароль» it sends
    somebody to retype a password that was right, over and over, with nothing
    anywhere hinting that the diary is the thing that is broken.

    A message that is not the class default (the expired-password one, say)
    is passed through rather than flattened to «неверный пароль».
    """
    if isinstance(error, SignInUnsupported):
        # Decided before any password was sent, so the ticket goes back — but
        # retrying will not help, so the note points at Госуслуги rather than
        # «попробуйте снова». A 503, as `/api/v1/diary/login` answers it: the
        # region's own answer about who it lets in, not a reply nobody can read.
        return _Verdict(
            error.message,
            503,
            keep_ticket=True,
            note=f"Дневник региона открывается через Госуслуги на {_site_of(binding)}.",
        )
    if isinstance(error, NoStudents):
        # The password was accepted and there is no pupil behind the account —
        # staff-only, or a pupil's account that does not list itself. Before
        # the generic branch below, which it is a subclass of the family of:
        # there it was «ответил непонятно … откройте сайт», sending the family
        # to check a diary that is up and to ask for a link that fails the
        # same way. The ticket is spent: the password was judged.
        return _Verdict(
            error.message,
            403,
            keep_ticket=False,
            note="Войдите под учётной записью родителя или ученика — "
            "попросите у бота новую ссылку.",
        )
    if isinstance(error, BadCredentials):
        # PasswordExpired and any other BadCredentials with its own words keep
        # them; the bare base message becomes the fixed «неверный пароль».
        spoke = error.message and error.message != BadCredentials.message
        return _Verdict(error.message if spoke else "Неверный логин или пароль.", 401,
                        keep_ticket=False)
    if isinstance(error, UpstreamUnavailable):
        # AddressRefused lands here too, with its own «дело не в пароле» words.
        return _Verdict(error.message, 503, keep_ticket=True)
    # UnexpectedResponse, SessionExpired bar NoStudents, and any future member
    # of the family.
    # The address is named on purpose: opening the diary in a browser is the
    # one check that tells the person which of the two is broken, and it is
    # also where a captcha would be waiting for them.
    #
    # The ticket is spent all the same, and that is not an oversight. This
    # branch is «the upstream answered something we could not read», and a 200
    # of HTML is exactly what a login form built on Yii returns for a *wrong
    # password* as well as for a captcha — we have never opened this diary for
    # real (see the README's honest status), so we cannot tell the two apart
    # from here. Handing the ticket back on a verdict we cannot read would turn
    # this URL into an unlimited password oracle against the upstream from our
    # address, which is the one thing spending it early exists to prevent. Only
    # `UpstreamUnavailable` — a transport failure or a 5xx, where nothing ever
    # looked at the password — is safe to forgive.
    return _Verdict(
        "Дневник ответил непонятно — обычно это технические работы или проверка "
        "«я не робот». Пароль, скорее всего, ни при чём: откройте "
        f"{_site_of(binding)} в браузере, а потом попросите у бота новую ссылку.",
        502,
        keep_ticket=False,
    )


async def _unspend(session: AsyncSession, ticket_id: int) -> None:
    """Give the ticket back.

    The ticket is spent before the sign-in it authorises, and that order is
    right: it is what stops whoever holds the URL guessing passwords against
    the upstream from our address. But it is a rule about *guesses*, and a
    diary that is down or serving a captcha refuses everybody identically —
    nobody can aim at that outcome, so nothing is learnt by provoking it.
    Charging a person a trip back to Telegram for the upstream's maintenance
    window buys no safety at all. The fifteen minutes still run out.
    """
    await session.execute(
        sa_update(DiaryLinkCode).where(DiaryLinkCode.id == ticket_id).values(used_at=None)
    )
    await session.commit()


async def _peek(session: AsyncSession, code: str) -> DiaryLinkCode | None:
    """Is this ticket still worth something? Does not spend it."""
    row = await session.scalar(
        select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
    )
    if row is None or row.used_at is not None:
        return None
    if row.expires_at <= diary_link.utcnow():
        return None
    return row
