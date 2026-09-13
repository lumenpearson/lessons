"""The one HTML page this project serves, and why it is worth the exception.

`CLAUDE.md` says there is no web frontend, and that is still true of the app:
there is no dashboard, no session cookie and no password reset. This is a
single server-rendered form with no JavaScript, and it exists for one reason
that the bot cannot satisfy — a password typed into a Telegram chat is in the
chat history, on Telegram's servers, in the notification on a locked screen
and in that phone's backup, and deleting the message undoes none of it.

So the password is taken here instead: over HTTPS, straight from the browser
to the upstream, written down nowhere. The page holds no state of its own. It
sets no cookie, because the session it opens belongs to a Telegram account
rather than to whichever browser happened to be handy. It is reached once,
with a ticket the bot handed out (``services/diary_link``), and the ticket is
spent whether the password was right or not.

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
from urllib.parse import parse_qs

from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import diary_enabled
from app.db import get_session
from app.models import DiaryLinkCode
from app.providers.petersburg import PetersburgError, UpstreamUnavailable
from app.security import hash_token
from app.services import diary as diary_service
from app.services import diary_link

log = logging.getLogger(__name__)

router = APIRouter(prefix="/diary", tags=["diary-web"])

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
.bad { margin: 0 0 16px; padding: 10px 12px; border-radius: 10px;
  background: #fee2e2; color: #991b1b; font-size: .9rem; }
.ok { font-size: 2rem; margin: 0 0 8px; }
@media (prefers-color-scheme: dark) {
  body { background: #0b0f19; color: #e5e7eb; }
  main { background: #151b28; box-shadow: none; }
  p.sub, .note { color: #9ca3af; }
  input { background: #0b0f19; border-color: #374151; }
  .bad { background: #7f1d1d; color: #fecaca; }
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


def _closed(message: str, status: int = 400) -> HTMLResponse:
    return _page(
        "Дневник",
        f"<h1>Не получилось</h1><p class=sub>{escape(message)}</p>"
        "<p class=note>Вернитесь в бота и запросите ссылку заново.</p>",
        status=status,
    )


def _form(code: str, error: str | None = None) -> HTMLResponse:
    # The code goes in the action rather than a hidden field: it is already in
    # the URL the browser is on, and one place for it is one place to get
    # wrong. autocomplete is on, so a password manager can fill this — the
    # people using it are signing in to an account they already have.
    warning = f"<p class=bad>{escape(error)}</p>" if error else ""
    return _page(
        "Вход в дневник",
        f"<h1>Электронный дневник</h1>"
        f"<p class=sub>Санкт-Петербург · dnevnik2.petersburgedu.ru</p>"
        f"{warning}"
        f'<form method=post action="/diary/signin/{escape(code)}">'
        "<label for=login>Логин</label>"
        "<input id=login name=login type=text inputmode=email autocomplete=username "
        "autocapitalize=off autocorrect=off required autofocus>"
        "<label for=password>Пароль</label>"
        "<input id=password name=password type=password "
        "autocomplete=current-password required>"
        "<button type=submit>Войти</button>"
        "</form>"
        "<p class=note>Пароль уходит прямо в дневник и нигде не сохраняется — "
        "ни у бота, ни в этой базе. Хранится только сессия дневника, "
        "зашифрованной, и её всегда можно отозвать кнопкой «Выйти».</p>",
        status=200 if error is None else 401,
    )


@router.get("/signin/{code}", response_class=HTMLResponse)
async def sign_in_form(
    code: str, session: AsyncSession = Depends(get_session)
) -> HTMLResponse:
    """The form. The ticket is *checked* here but not spent — spending it on a
    GET would mean a link preview or a prefetch burned it before anybody typed
    anything."""
    if not diary_enabled():
        return _closed("Дневник на этом сервере выключен.", status=503)
    row = await _peek(session, code)
    if row is None:
        return _closed("Ссылка уже использована или устарела.", status=410)
    return _form(code)


#: Refuse a body larger than this before reading it.
#:
#: The real form is two short fields. Anything bigger is either a mistake or
#: somebody making the server hold a megabyte in memory per request, and a
#: serverless function's memory is the thing it has least of.
MAX_BODY = 8 * 1024


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
    raw = await request.body()
    if len(raw) > MAX_BODY:
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
    session: AsyncSession = Depends(get_session),
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

    ticket = await diary_link.claim(session, code)
    if ticket is None:
        return _closed("Ссылка уже использована или устарела.", status=410)

    try:
        _, opened = await diary_service.sign_in(
            session, login, password, telegram_id=ticket.telegram_id
        )
    except PetersburgError as error:
        # The ticket is already spent, so a wrong password means a new link.
        # That is the cost of not letting whoever holds this URL sit and guess
        # against the upstream from our address.
        return _closed(_why(error), status=401)
    except Exception:  # noqa: BLE001 - never let an upstream shape reach the page
        log.exception("diary sign-in failed")
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


def _why(error: PetersburgError) -> str:
    """The upstream's failure, said in a way the reader can act on."""
    if isinstance(error, UpstreamUnavailable):
        return "Дневник сейчас не отвечает. Попробуйте позже."
    return "Неверный логин или пароль."


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
