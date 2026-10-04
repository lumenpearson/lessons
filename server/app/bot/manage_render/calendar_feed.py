"""«📅 Календарь»: the feed's address and how to subscribe to it."""

from __future__ import annotations

from html import escape


def render_calendar(url: str | None, rotated: bool = False) -> str:
    if url is None:
        return (
            "📅 <b>Календарь</b>\n\n"
            "Адрес сервера не настроен — задайте <code>PUBLIC_BASE_URL</code>, "
            "и ссылка появится здесь."
        )
    head = "🔁 <b>Новая ссылка на календарь</b>" if rotated else "📅 <b>Календарь класса</b>"
    lines = [
        head,
        "",
        f"<code>{escape(url)}</code>",
        "",
        "• Google Календарь: «Другие календари» → «Подписаться по URL» → вставьте ссылку.",
        "• iPhone: «Настройки» → «Календарь» → «Учётные записи» → «Другое» → "
        "«Подписной календарь».",
    ]
    if rotated:
        lines.append("")
        lines.append("<i>Старая ссылка больше не работает — раздайте новую заново.</i>")
    return "\n".join(lines)
