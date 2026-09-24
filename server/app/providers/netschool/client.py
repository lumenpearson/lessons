"""The HTTP client for one «Сетевой город» session.

One pooled `httpx.AsyncClient` per process, carrying no cookies of its own and
following no redirect (`app.providers.diary.http`), so two families' sessions
can never meet and the `at` bearer never rides a cross-origin redirect to
another host. Every call is to a region's own ``origin``, which comes only from
the allow-list (`app.providers.netschool.regions`).

The session is a small dict, sealed by the service into ``upstream_token``:
``at`` (the bearer), the cookies to send, ``ver`` (for the SecurityWarning
acknowledgement), the school year id and its bounds, the assignment-type map,
and the login's ``timeOut`` so the keep-alive knows this server's idle window.
No password, and no hash of it, is ever in it.

Rules the review nailed down, each with a reason:

- ``at``, the cookies, ``pw`` and ``pw2`` travel only in a header or a form
  body, never in a query string or a path — this process logs every request
  URL, so a token in a URL is a token in the logs. The vendor's own
  ``?token=`` keep-alive is banned for that reason.
- ``X-Requested-With: XMLHttpRequest`` on every call, so the ASP.NET side
  answers JSON and 401 rather than an HTML redirect.
- A blocked address (403, a WAF page, a connection reset) is
  :class:`AddressRefused`, never a wrong password and never an expired session.
- Sign-in *ends* at the accepted ``POST /webapi/login``. A failure before it
  (logindata, getdata) is :class:`UpstreamUnavailable`/:class:`AddressRefused`/
  :class:`SignInUnsupported` — nothing looked at the password. A failure in the
  bootstrap *after* it is :class:`SessionExpired`-shaped, because the password
  was already judged.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from typing import Any

import httpx

from app.providers.diary.errors import (
    AddressRefused,
    BadCredentials,
    SessionExpired,
    SignInUnsupported,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.diary.http import build_client, session_cookie
from app.providers.netschool.regions import Region

log = logging.getLogger(__name__)

CREDENTIAL_VERSION = 1

#: Cookies worth carrying between calls. NSSESSIONID is the session; ESRNSec is
#: the ESRN security module's, mandatory on the servers that set it (asurso.ru).
SESSION_COOKIES = ("NSSESSIONID", "ESRNSec")

#: A short data timeout: a regional server that hangs must not hold a request
#: to the platform's ceiling. The keep-alive uses a shorter one of its own.
_TIMEOUT = httpx.Timeout(connect=5.0, read=15.0, write=10.0, pool=5.0)
_KEEPALIVE_TIMEOUT = httpx.Timeout(connect=3.0, read=5.0, write=5.0, pool=3.0)

#: Markers of a "we do not serve this address" page, whatever its status.
_WAF_MARKERS = ("Доступ к сайту", "Request forbidden", "Access denied", "blocked")

_client: httpx.AsyncClient | None = None
_client_lock = asyncio.Lock()


async def shared_client() -> httpx.AsyncClient:
    """The one client, built on first use. Carries no cookies; the session is
    passed on every call, so two families' cookies never meet."""
    global _client
    if _client is None:
        async with _client_lock:
            if _client is None:
                _client = build_client(
                    timeout=_TIMEOUT,
                    headers={
                        "x-requested-with": "XMLHttpRequest",
                        "accept": "application/json, text/plain, */*",
                    },
                )
    return _client


async def close_client() -> None:
    """Close the shared client. For the lifespan and for tests."""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def _hash_password(salt: str, password: str) -> tuple[str, str]:
    """``(pw, pw2)`` as every windows-1251 client computes them.

    A character the region's charset cannot encode is a wrong password from
    here: raised as :class:`BadCredentials` without the source character in the
    message, because that message would otherwise carry the password.
    """
    try:
        raw = password.encode("windows-1251")
    except UnicodeEncodeError:
        raise BadCredentials from None
    inner = hashlib.md5(raw).hexdigest()  # noqa: S324 - the upstream's scheme, not ours
    pw2 = hashlib.md5((salt + inner).encode("ascii")).hexdigest()  # noqa: S324
    return pw2[: len(password)], pw2


class NetSchoolClient:
    """Calls one region's ``/webapi`` on behalf of one session.

    @param region the allow-listed region — the only source of the origin.
    @param session the sealed credential parsed back, or ``None`` before login.
    """

    __slots__ = ("region", "session")

    def __init__(self, region: Region, session: dict[str, Any] | None = None) -> None:
        self.region = region
        self.session: dict[str, Any] = session or {}

    @property
    def credential(self) -> str:
        return json.dumps(self.session, ensure_ascii=False, separators=(",", ":"))

    # ---- request plumbing -------------------------------------------------

    def _url(self, path: str) -> str:
        return f"{self.region.origin}{path}"

    def _auth_headers(self) -> dict[str, str]:
        headers: dict[str, str] = {}
        at = self.session.get("at")
        if at:
            headers["at"] = at
        cookies = self.session.get("cookies") or {}
        if cookies:
            headers["cookie"] = "; ".join(f"{k}={v}" for k, v in cookies.items())
        return headers

    def _absorb_cookies(self, response: httpx.Response) -> None:
        """Fold this answer's session cookies into the stored set."""
        cookies = dict(self.session.get("cookies") or {})
        changed = False
        for name in SESSION_COOKIES:
            value = session_cookie(response, name)
            if value and value != cookies.get(name):
                cookies[name] = value
                changed = True
        if changed:
            self.session["cookies"] = cookies

    @staticmethod
    def _looks_refused(response: httpx.Response) -> bool:
        if response.status_code == 403:
            return True
        ctype = response.headers.get("content-type", "")
        if "html" in ctype or "json" not in ctype:
            body = response.text[:2000]
            return any(marker in body for marker in _WAF_MARKERS)
        return False

    @staticmethod
    def _is_security_warning(response: httpx.Response) -> bool:
        ctype = response.headers.get("content-type", "")
        return "html" in ctype and "/asp/SecurityWarning.asp" in response.text[:2000]

    async def _send(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, str] | None = None,
        data: dict[str, str] | None = None,
        auth: bool = True,
        timeout: httpx.Timeout | None = None,
    ) -> httpx.Response:
        """One request, with the session in headers and never in the URL."""
        client = await shared_client()
        headers = self._auth_headers() if auth else {}
        try:
            response = await client.request(
                method,
                self._url(path),
                params=params,
                data=data,
                headers=headers,
                timeout=timeout or _TIMEOUT,
            )
        except httpx.TransportError as error:
            text = str(error).lower()
            if "reset" in text or "refused" in text or "denied" in text:
                raise AddressRefused from error
            raise UpstreamUnavailable from error
        if self._looks_refused(response):
            raise AddressRefused
        return response

    def _decode(self, response: httpx.Response, *, step: str) -> Any:
        """A JSON body, or the mapped failure for anything else."""
        status = response.status_code
        if status >= 500:
            raise UpstreamUnavailable
        if status == 429:
            raise UpstreamUnavailable
        try:
            return response.json()
        except ValueError:
            raise UnexpectedResponse from None

    async def _authed_json(self, method: str, path: str, **kwargs: Any) -> Any:
        """An authenticated call that returns JSON, handling the ways a dead
        NetSchool session shows: 401, a redirect, an HTML login page, or the
        co-working SecurityWarning interstitial."""
        response = await self._send(method, path, **kwargs)
        if self._is_security_warning(response):
            await self._acknowledge_warning()
            response = await self._send(method, path, **kwargs)
            if self._is_security_warning(response):
                raise SessionExpired
        if response.status_code == 401 or response.is_redirect:
            raise SessionExpired
        ctype = response.headers.get("content-type", "")
        if "json" not in ctype:
            # A non-JSON 200 on an authenticated call is a login page: the
            # session is gone, which is «войдите снова», not «непонятно».
            raise SessionExpired
        self._absorb_cookies(response)
        new_at = response.headers.get("at")
        if new_at and new_at != self.session.get("at"):
            self.session["at"] = new_at
        return self._decode(response, step=path)

    async def _acknowledge_warning(self) -> None:
        """Answer the «another session is open» interstitial once, best effort."""
        with_at = self.session.get("at")
        if not with_at:
            return
        data = {"at": with_at, "WarnType": "2", "ver": str(self.session.get("ver", ""))}
        try:
            await self._send("POST", "/asp/SecurityWarning.asp", data=data)
        except UpstreamUnavailable:
            return

    # ---- sign-in ----------------------------------------------------------

    async def login_allowed(self) -> None:
        """Read the region's sign-in options. Raise before any credential is sent.

        This is the only place :class:`SignInUnsupported` comes from, so handing
        the ticket back on it is safe: no password has been touched.
        """
        response = await self._send("GET", "/webapi/logindata", auth=False)
        data = self._decode(response, step="logindata")
        self._absorb_cookies(response)
        if not data.get("schoolLogin", True):
            raise SignInUnsupported
        self.session.setdefault("ver", data.get("cacheVer"))

    async def login(self, login: str, password: str, school_id: int) -> None:
        """Sign in with a password. On return the session holds ``at`` and cookies.

        Ends at the accepted ``POST /webapi/login``; the caller runs the
        bootstrap. A wrong password is :class:`BadCredentials`; the region
        refusing our address is :class:`AddressRefused`; anything unreadable
        before the password POST is :class:`UpstreamUnavailable`, so the ticket
        survives.
        """
        await self.login_allowed()
        try:
            await self._password_login(login, password, school_id, rolegroup=None)
        except _NeedsRole as need:
            await self._password_login(login, password, school_id, rolegroup=need.rolegroup)

    async def _password_login(
        self, login: str, password: str, school_id: int, *, rolegroup: str | None
    ) -> None:
        # getdata carries a one-shot salt tied to the cookie it saw, so each
        # attempt (including a role re-login) re-fetches it.
        getdata = await self._send(
            "POST", "/webapi/auth/getdata", auth=True, data={}
        )
        self._absorb_cookies(getdata)
        payload = self._decode(getdata, step="getdata")
        salt = str(payload.get("salt", ""))
        lt = str(payload.get("lt", ""))
        ver = str(payload.get("ver", ""))
        if not salt or not lt:
            raise UpstreamUnavailable  # getdata is before the password; hand the ticket back
        pw, pw2 = _hash_password(salt, password)
        form = {
            "loginType": "1",
            "scid": str(school_id),
            "un": login,
            "pw": pw,
            "pw2": pw2,
            "lt": lt,
            "ver": ver,
        }
        if rolegroup is not None:
            form["rolegroup"] = rolegroup
        response = await self._send("POST", "/webapi/login", auth=True, data=form)
        self._absorb_cookies(response)
        self._consume_login(response, login, password, school_id, tried_role=rolegroup is not None)

    def _consume_login(
        self,
        response: httpx.Response,
        login: str,
        password: str,
        school_id: int,
        *,
        tried_role: bool,
    ) -> None:
        status = response.status_code
        if status == 409:
            raise BadCredentials(self._login_message(response))
        if status == 429:
            raise UpstreamUnavailable
        if status >= 500:
            raise UpstreamUnavailable
        try:
            body = response.json()
        except ValueError:
            raise UnexpectedResponse from None
        entry = body.get("entryPoint") or ""
        if "choose-session-role" in entry and not tried_role:
            # The account is both staff and parent. The salt was one-shot, so
            # the role re-login (in `login`) reruns the whole sequence with a
            # fresh one. Checked before `at` is accepted, as netschoolpy does.
            raise _NeedsRole(self._pick_parent_role(body))
        at = body.get("at")
        if not at:
            raise BadCredentials(self._login_message(body))
        self.session["at"] = at
        if body.get("timeOut"):
            self.session["time_out"] = int(body["timeOut"])

    @staticmethod
    def _login_message(source: Any) -> str | None:
        if isinstance(source, httpx.Response):
            try:
                source = source.json()
            except ValueError:
                return None
        if isinstance(source, dict):
            text = source.get("message") or source.get("errorMessage")
            if isinstance(text, str) and text.strip():
                return text.strip()[:200]
        return None

    @staticmethod
    def _pick_parent_role(body: dict[str, Any]) -> str | None:
        info = body.get("accountInfo") or {}
        roles = info.get("userRoles") or body.get("userRoles") or []
        for entry in roles:
            role = entry.get("role", entry) if isinstance(entry, dict) else {}
            name = str(role.get("name", "")) if isinstance(role, dict) else ""
            if "Родител" in name:
                rid = role.get("id") if isinstance(role, dict) else None
                if rid is not None:
                    return str(rid)
        if roles:
            first = roles[0].get("role", roles[0]) if isinstance(roles[0], dict) else {}
            rid = first.get("id") if isinstance(first, dict) else None
            return str(rid) if rid is not None else None
        return None

    # ---- keep-alive and logout -------------------------------------------

    async def keep_alive(self) -> None:
        """Reset the idle timer with GET /webapi/context (the `at` in a header,
        never in the URL). A dead session raises :class:`SessionExpired`."""
        await self._authed_json("GET", "/webapi/context", timeout=_KEEPALIVE_TIMEOUT)

    async def logout(self) -> None:
        """Tell the upstream we are done. Unlike Petersburg, NetSchool has a
        logout that works without a browser. Best effort: a dead session or a
        transport failure is fine, we are leaving anyway."""
        at = self.session.get("at")
        if not at:
            return
        try:
            await self._send(
                "POST",
                "/webapi/auth/logout",
                auth=True,
                data={"at": at, "ver": str(self.session.get("ver", ""))},
                timeout=_KEEPALIVE_TIMEOUT,
            )
        except (UpstreamUnavailable, SessionExpired, UnexpectedResponse):
            return

    # ---- reads ------------------------------------------------------------

    async def diary_init(self) -> Any:
        return await self._authed_json("GET", "/webapi/student/diary/init")

    async def years_current(self) -> Any:
        return await self._authed_json("GET", "/webapi/years/current")

    async def context(self) -> Any:
        return await self._authed_json("GET", "/webapi/context")

    async def assignment_types(self) -> Any:
        return await self._authed_json(
            "GET", "/webapi/grade/assignment/types", params={"all": "false"}
        )

    async def diary_week(
        self, student_id: int, year_id: int, week_start: str, week_end: str
    ) -> Any:
        return await self._authed_json(
            "GET",
            "/webapi/student/diary",
            params={
                "studentId": str(student_id),
                "yearId": str(year_id),
                "weekStart": week_start,
                "weekEnd": week_end,
                "withLaAssigns": "true",
            },
        )

    async def terms_search(self, group_id: int | None) -> Any:
        # terms/search takes a JSON body, not a form.
        body = {"classIds": [group_id]} if group_id is not None else {}
        client = await shared_client()
        headers = self._auth_headers()
        headers["content-type"] = "application/json"
        try:
            response = await client.request(
                "POST",
                self._url("/webapi/terms/search"),
                content=json.dumps(body),
                headers=headers,
                timeout=_TIMEOUT,
            )
        except httpx.TransportError as error:
            raise UpstreamUnavailable from error
        if self._looks_refused(response):
            raise AddressRefused
        if response.status_code == 401 or response.is_redirect:
            raise SessionExpired
        if response.status_code in (404, 405):
            return []  # this server has no such endpoint
        try:
            return response.json()
        except ValueError:
            raise UnexpectedResponse from None


class _NeedsRole(Exception):
    """Internal: the login must be retried with a role. Never leaves the client."""

    def __init__(self, rolegroup: str | None) -> None:
        super().__init__("role required")
        self.rolegroup = rolegroup
