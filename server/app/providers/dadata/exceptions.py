"""What can go wrong when the school directory is asked a question.

The same shape as the Petersburg provider's errors and for the same reason: a
route has to answer differently for "we never configured this", "they are
down" and "they changed the response", and a caller should not have to read an
httpx exception to tell those apart.
"""

from __future__ import annotations


class DirectoryError(Exception):
    """Base for everything this provider raises."""

    #: What reaches a phone screen or a Telegram message. Short and Russian.
    message = "Справочник школ недоступен"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.message)
        self.message = message or self.message


class NotConfigured(DirectoryError):
    """No API key, so there is no directory.

    Its own type because it is the one failure that is not the upstream's: it
    means this deployment was never given a key, and the honest answer is to
    let the person type the school's name by hand rather than to keep
    retrying. Nothing falls back to a bundled list — a stale snapshot of
    Russian schools would answer confidently and wrongly, and nobody would
    know which of the two they were looking at.
    """

    message = "Поиск по школам не настроен — введите название вручную"


class UpstreamUnavailable(DirectoryError):
    """A timeout, a connection failure, or a 5xx."""

    message = "Справочник школ не отвечает"


class QuotaExceeded(UpstreamUnavailable):
    """The daily request allowance is spent, or the key was refused.

    Separate from the rest because it is the one the owner can act on, and
    because retrying it today will not help.
    """

    message = "Лимит запросов к справочнику исчерпан — введите название вручную"


class UnexpectedResponse(DirectoryError):
    """A 200 whose body is not what this provider knows how to read."""

    message = "Справочник школ ответил непонятно"
