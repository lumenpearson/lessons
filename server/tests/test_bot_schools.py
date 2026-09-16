"""Picking a school in the bot.

The handlers are called directly with lightweight stubs, the way the rest of
the bot tests do it: what is worth pinning here is that the payload is not
trusted, that a page turn costs no upstream request, and that typing the name
is never taken away — not Telegram's transport.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.bot.handlers import start as handlers
from app.bot.states import CreateClass
from app.config import get_settings
from app.providers.dadata.exceptions import UpstreamUnavailable


class _Message:
    def __init__(self, text: str = "") -> None:
        self.text = text
        self.answers: list[tuple[str, object]] = []
        self.edits: list[str] = []

    async def answer(self, text: str, reply_markup=None, **_) -> None:
        self.answers.append((text, reply_markup))

    async def edit_text(self, text: str, reply_markup=None, **_) -> None:
        self.edits.append(text)
        self.answers.append((text, reply_markup))


class _Callback:
    def __init__(self) -> None:
        self.message = _Message()
        self.answers: list[tuple[str | None, bool]] = []

    @property
    def from_user(self):
        return SimpleNamespace(id=1000, username="x", full_name="X")

    async def answer(self, text=None, show_alert: bool = False, **_) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


class _State:
    def __init__(self, data: dict | None = None) -> None:
        self.data = data or {}
        self.state = None

    async def get_data(self) -> dict:
        return dict(self.data)

    async def update_data(self, **kw) -> dict:
        self.data.update(kw)
        return dict(self.data)

    async def set_state(self, state) -> None:
        self.state = state

    async def clear(self) -> None:
        self.data.clear()


def _suggestions(count: int) -> list[dict]:
    return [
        {
            "value": f"ШКОЛА № {i}",
            "data": {
                "ogrn": f"102780000{i:04d}",
                "name": {"short_with_opf": f'МБОУ "ШКОЛА № {i}"'},
                "state": {"status": "ACTIVE"},
                "address": {"value": "г Казань", "data": {"city": "Казань"}},
            },
        }
        for i in range(count)
    ]


@pytest.fixture
def directory(monkeypatch):
    """A configured directory whose answers and call count are visible."""
    monkeypatch.setattr(get_settings(), "dadata_token", "test-key")
    calls: list[str] = []

    def answer_with(items):
        async def fake(query: str, *, region: str | None = None):
            calls.append(query)
            return items

        monkeypatch.setattr("app.providers.dadata.suggest_schools", fake)

    def fail_with(error):
        async def fake(query: str, *, region: str | None = None):
            calls.append(query)
            raise error

        monkeypatch.setattr("app.providers.dadata.suggest_schools", fake)

    return SimpleNamespace(calls=calls, answer_with=answer_with, fail_with=fail_with)


# ---- which question gets asked --------------------------------------------


async def test_without_a_key_the_school_step_asks_for_the_name_itself(monkeypatch):
    """Showing a search box that answers «не настроено» to everything would be
    worse than asking the question the deployment can actually answer."""
    monkeypatch.setattr(get_settings(), "dadata_token", "")
    message, state = _Message(), _State()

    await handlers._ask_school(message, state)

    assert state.state == CreateClass.school_manual
    assert message.answers[0][0] == handlers.MANUAL_PROMPT


async def test_a_key_of_whitespace_asks_for_the_name_too(monkeypatch):
    """«Set» and «usable» are one question at this screen.

    A key pasted into a host's environment form arrives with a newline often
    enough to matter, and a search box that answers «Лимит запросов исчерпан»
    to everything is worse than the honest question — it sends the owner to
    read their DaData billing for a key they never had.
    """
    monkeypatch.setattr(get_settings(), "dadata_token", "  \n ")
    message, state = _Message(), _State()

    await handlers._ask_school(message, state)

    assert state.state == CreateClass.school_manual
    assert message.answers[0][0] == handlers.MANUAL_PROMPT


async def test_with_a_key_the_school_step_asks_for_a_search(directory):
    message, state = _Message(), _State()

    await handlers._ask_school(message, state)

    assert state.state == CreateClass.school
    assert message.answers[0][0] == handlers.SEARCH_PROMPT


# ---- searching ------------------------------------------------------------


async def test_a_dash_skips_the_school_and_moves_on(directory):
    await handlers.create_class_school_search(_Message("-"), state := _State())

    assert state.data["school"] is None
    assert state.state == CreateClass.timezone
    assert directory.calls == []


async def test_a_search_keeps_the_whole_result_set_not_the_page(directory):
    """The upstream has no offset, so paging is local — and a page turn that
    searched again would spend the allowance twice for one answer."""
    directory.answer_with(_suggestions(12))
    message, state = _Message("школа казань"), _State()

    await handlers.create_class_school_search(message, state)

    assert len(state.data["school_results"]) == 12
    assert directory.calls == ["школа казань"]


async def test_twenty_results_say_so_rather_than_claiming_twenty_matches(directory):
    directory.answer_with(_suggestions(20))
    message, state = _Message("школа"), _State()

    await handlers.create_class_school_search(message, state)

    assert "первые" in message.answers[0][0]
    assert state.data["school_truncated"] is True


async def test_a_directory_that_is_down_still_lets_the_name_be_typed(directory):
    """It is somebody else's service, and a class must be creatable today."""
    directory.fail_with(UpstreamUnavailable())
    message, state = _Message("гимназия 3"), _State()

    await handlers.create_class_school_search(message, state)

    text, keyboard = message.answers[0]
    assert text == UpstreamUnavailable.message
    assert _has_button(keyboard, "✏️ Ввести вручную")


async def test_nothing_found_offers_the_same_way_out(directory):
    directory.answer_with([])
    message, state = _Message("шклоа 3"), _State()

    await handlers.create_class_school_search(message, state)

    text, keyboard = message.answers[0]
    assert "ничего не нашлось" in text.lower()
    assert _has_button(keyboard, "✏️ Ввести вручную")


async def test_too_short_a_query_is_answered_without_a_request(directory):
    message, state = _Message("шк"), _State()

    await handlers.create_class_school_search(message, state)

    assert directory.calls == []
    assert "символа" in message.answers[0][0]
    # Still on the search step: the question was not answered.
    assert state.state is None


# ---- picking --------------------------------------------------------------


async def _searched(directory, count: int = 12) -> _State:
    directory.answer_with(_suggestions(count))
    state = _State()
    await handlers.create_class_school_search(_Message("школа казань"), state)
    return state


async def test_picking_stores_the_name_and_moves_to_the_zone(directory):
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_pick(callback, SimpleNamespace(value=7), state)

    assert state.data["school"] == 'МБОУ "Школа № 7"'
    assert state.state == CreateClass.timezone
    assert not callback.alerted


async def test_a_forged_index_is_refused_rather_than_stored(directory):
    """The keyboard draws 0..11; the payload it packs is a number an attacker
    types."""
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_pick(callback, SimpleNamespace(value=999), state)

    assert callback.alerted
    assert "school" not in state.data


async def test_a_negative_index_does_not_pick_from_the_end(directory):
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_pick(callback, SimpleNamespace(value=-1), state)

    assert callback.alerted
    assert "school" not in state.data


async def test_picking_after_the_search_was_cleared_says_so(directory):
    callback = _Callback()

    await handlers.create_class_school_pick(callback, SimpleNamespace(value=0), _State())

    assert callback.alerted


# ---- paging ---------------------------------------------------------------


async def test_turning_a_page_costs_no_upstream_request(directory):
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_page(callback, SimpleNamespace(value=2), state)

    assert directory.calls == ["школа казань"]
    assert callback.message.edits


async def test_a_page_past_the_end_is_clamped_rather_than_refused(directory):
    state = await _searched(directory, count=7)
    callback = _Callback()

    await handlers.create_class_school_page(callback, SimpleNamespace(value=99), state)

    assert not callback.alerted
    assert callback.message.edits


# ---- the two ways out -----------------------------------------------------


async def test_manual_moves_to_the_typed_name_step(directory):
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_manual(callback, state)

    assert state.state == CreateClass.school_manual
    assert state.data["school_results"] == []


async def test_skip_leaves_the_school_empty_and_moves_on(directory):
    state = await _searched(directory)
    callback = _Callback()

    await handlers.create_class_school_skip(callback, state)

    assert state.data["school"] is None
    assert state.state == CreateClass.timezone


async def test_a_typed_name_is_stored_as_it_was_written():
    message, state = _Message("Лицей при СПбГУ"), _State()

    await handlers.create_class_school_typed(message, state)

    assert state.data["school"] == "Лицей при СПбГУ"
    assert state.state == CreateClass.timezone


async def test_a_typed_name_longer_than_the_column_is_cut_not_refused():
    message, state = _Message("Ш" * 400), _State()

    await handlers.create_class_school_typed(message, state)

    assert len(state.data["school"]) == 200


def _has_button(keyboard, text: str) -> bool:
    return any(button.text == text for row in keyboard.inline_keyboard for button in row)
