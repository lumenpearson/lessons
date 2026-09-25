"""The school directory: what a company-register row becomes, and how it is paged.

Every payload below is DaData's documented ``suggest/party`` shape. Nothing
here reaches the network — the point of the provider boundary is that the
mapper and the paging can be tested without a key, and they are the two halves
that decide what somebody sees.
"""

from __future__ import annotations

import logging

import httpx
import pytest

from app.config import get_settings
from app.providers.dadata import client as dadata_client
from app.providers.dadata import mapper as m
from app.providers.dadata.exceptions import NotConfigured
from app.providers.dadata.models import School
from app.services import schools as service

FULL_NAME = (
    'МУНИЦИПАЛЬНОЕ БЮДЖЕТНОЕ ОБЩЕОБРАЗОВАТЕЛЬНОЕ УЧРЕЖДЕНИЕ "ГИМНАЗИЯ № 3"'
)


def suggestion(**over):
    item = {
        "value": 'МБОУ "ГИМНАЗИЯ № 3"',
        "unrestricted_value": FULL_NAME,
        "data": {
            "inn": "7801234567",
            "ogrn": "1027800000001",
            "okved": "85.14",
            "name": {
                "full_with_opf": FULL_NAME,
                "short_with_opf": 'МБОУ "ГИМНАЗИЯ № 3"',
            },
            "state": {"status": "ACTIVE"},
            "address": {
                "value": "г Санкт-Петербург, ул Восстания, д 8",
                "unrestricted_value": "190000, г Санкт-Петербург, ул Восстания, д 8",
                "data": {
                    "city": "Санкт-Петербург",
                    "region_with_type": "г Санкт-Петербург",
                },
            },
        },
    }
    data = over.pop("data", None)
    item.update(over)
    if data is not None:
        item["data"] = data
    return item


# ---- the mapper -----------------------------------------------------------


def test_a_register_row_becomes_a_school_a_person_can_read():
    school = m.to_school(suggestion())
    assert school is not None
    assert school.name == 'МБОУ "Гимназия № 3"'
    assert school.full_name.startswith("МУНИЦИПАЛЬНОЕ")
    assert school.ogrn == "1027800000001"
    assert school.city == "Санкт-Петербург"
    assert school.address == "190000, г Санкт-Петербург, ул Восстания, д 8"
    assert school.active is True


def test_the_short_name_is_preferred_and_the_full_one_kept():
    """Both are needed: the short one fits a button, the long one is the only
    unambiguous name a class card can fall back to."""
    school = m.to_school(suggestion())
    assert school.name != school.full_name


def test_abbreviations_keep_their_case_and_words_do_not():
    assert m.humanise('МБОУ "СРЕДНЯЯ ШКОЛА № 197"') == 'МБОУ "Средняя школа № 197"'
    assert m.humanise("ГБОУ ЛИЦЕЙ № 144") == "ГБОУ Лицей № 144"
    # A number is not a word, and neither is a bare «№».
    assert m.humanise("ШКОЛА № 2") == "Школа № 2"


def test_the_abbreviation_on_every_second_school_sign_is_one_of_them():
    """«СОШ», «ООШ» and «НОШ» are three letters, and the rule wanted two in
    front of the «ОШ». The one abbreviation more common than «МБОУ» was
    therefore lowered like a word, on a name a person has to recognise in a
    list of five."""
    assert m.humanise('МБОУ "СОШ № 197"') == 'МБОУ "СОШ № 197"'
    assert m.humanise("МКОУ ООШ ПЕТРОВО") == "МКОУ ООШ Петрово"
    assert m.humanise("МБОУ НОШ № 5") == "МБОУ НОШ № 5"
    # And the words around them are still words.
    assert m.humanise("ГБОУ ЛИЦЕЙ № 144") == "ГБОУ Лицей № 144"


def test_only_the_first_word_is_capitalised():
    """«Средняя Школа» reads as two proper nouns; nobody writes it that way."""
    assert m.humanise("ОСНОВНАЯ ОБЩЕОБРАЗОВАТЕЛЬНАЯ ШКОЛА") == "Основная общеобразовательная школа"


def test_a_school_named_after_somebody_keeps_that_persons_name():
    """Past «им.» the rule inverts, because past «им.» it is a person.

    Three things were wrong at once and all three are on the same register
    rows. Initials were lowered like long words — «им. а.с. пушкина». The
    surname after them was lowered too, which is the only word in a school's
    name that is somebody's. And «ИМ.» itself collected the name's one capital
    whenever nothing lowerable came before it, which is every «СОШ № 5 ИМ. …»
    in the register: «МБОУ "СОШ № 5 Им. м.в. ломоносова"».
    """
    assert (
        m.humanise('МБОУ "СРЕДНЯЯ ШКОЛА № 197 ИМ. А.С. ПУШКИНА"')
        == 'МБОУ "Средняя школа № 197 им. А.С. Пушкина"'
    )
    assert (
        m.humanise('МБОУ "СОШ № 5 ИМ. М.В. ЛОМОНОСОВА"')
        == 'МБОУ "СОШ № 5 им. М.В. Ломоносова"'
    )
    assert (
        m.humanise("ГБОУ ГИМНАЗИЯ № 3 ИМЕНИ К.Д. УШИНСКОГО")
        == "ГБОУ Гимназия № 3 имени К.Д. Ушинского"
    )
    # And a name with nobody in it is untouched by any of that.
    assert m.humanise('МБОУ "СРЕДНЯЯ ШКОЛА № 197"') == 'МБОУ "Средняя школа № 197"'


def test_a_row_with_no_name_at_all_is_dropped_rather_than_shown():
    item = suggestion()
    item["data"]["name"] = {}
    item["value"] = ""
    item["unrestricted_value"] = ""
    assert m.to_school(item) is None


def test_a_row_whose_data_is_not_an_object_is_dropped():
    assert m.to_school({"value": "ШКОЛА", "data": None}) is None
    assert m.to_school({"value": "ШКОЛА"}) is None


def test_a_liquidated_school_is_kept_and_marked():
    """Hidden would be worse: a class created in May may belong to a school
    merged over the summer, and an empty result says nothing."""
    item = suggestion()
    item["data"]["state"] = {"status": "LIQUIDATED"}
    school = m.to_school(item)
    assert school is not None
    assert school.active is False


def test_one_unreadable_row_does_not_take_the_search_with_it():
    schools = m.to_schools([suggestion(), {"value": "", "data": {}}, suggestion()])
    # The second copy is a duplicate registration number, so one survives, not two.
    assert len(schools) == 1


def test_a_whole_answer_that_read_as_nothing_is_said_out_loud(caplog):
    """Twenty well-formed suggestions mapping to nothing is the register's
    shape having moved, not a school that is not in ЕГРЮЛ — and the bot answers
    «По запросу «…» ничего не нашлось» to both.

    The line that was here said it at `debug`, and `app/main.py` sets the level
    to `INFO`: in every deployment there has ever been, it was never emitted.
    """
    with caplog.at_level(logging.WARNING, logger="app.providers.dadata.mapper"):
        schools = m.to_schools([{"value": "МБОУ", "payload": {"ogrn": "102"}} for _ in range(20)])

    assert schools == []
    spoken = [record.getMessage() for record in caplog.records]
    assert len(spoken) == 1
    assert "20" in spoken[0]
    # The keys are the point of the line: they are what the next spelling read
    # in the mapper has to be.
    assert "payload" in spoken[0]


def test_an_answer_read_in_part_is_not_reported_as_a_shape_change(caplog):
    """One unreadable row out of three is the case the mapper is forgiving
    about on purpose, and a warning on it would teach the reader to skip the
    one that matters."""
    with caplog.at_level(logging.WARNING, logger="app.providers.dadata.mapper"):
        m.to_schools([suggestion(), {"value": "", "data": {}}])

    assert caplog.records == []


def test_an_answer_of_nothing_but_duplicates_is_not_a_shape_change(caplog):
    """Every row read fine; deduplication shortened the list. Reading «nothing
    came out» off the result rather than counting what could not be read would
    report this as the register moving."""
    other = suggestion()
    other["data"]["name"] = {"short_with_opf": 'МБОУ "ГИМНАЗИЯ №3"'}
    with caplog.at_level(logging.WARNING, logger="app.providers.dadata.mapper"):
        assert len(m.to_schools([suggestion(), other])) == 1

    assert caplog.records == []


def test_the_same_school_twice_under_two_spellings_is_shown_once():
    other = suggestion()
    other["data"]["name"] = {"short_with_opf": 'МБОУ "ГИМНАЗИЯ №3"'}
    assert len(m.to_schools([suggestion(), other])) == 1


def test_two_schools_with_different_ogrn_both_survive():
    other = suggestion()
    other["data"]["ogrn"] = "1027800000002"
    assert len(m.to_schools([suggestion(), other])) == 2


def test_the_label_carries_the_city_because_two_schools_share_a_number():
    school = m.to_school(suggestion())
    assert school.label == 'МБОУ "Гимназия № 3" · Санкт-Петербург'


def test_a_school_with_no_city_still_has_a_label():
    item = suggestion()
    item["data"]["address"] = {}
    school = m.to_school(item)
    assert school.label == school.name


# ---- the query ------------------------------------------------------------


@pytest.mark.parametrize("raw", ["", " ", "шк", None])
def test_too_short_a_query_is_refused_before_the_network(raw):
    with pytest.raises(service.SearchError):
        service.normalise_query(raw)


def test_whitespace_is_collapsed_so_a_paste_still_searches():
    assert service.normalise_query("  гимназия   3  ") == "гимназия 3"


def test_a_pasted_address_is_cut_rather_than_sent_whole():
    assert len(service.normalise_query("школа " + "ы" * 400)) == 150


# ---- paging ---------------------------------------------------------------


def many(count: int) -> list[School]:
    return [School(full_name=f"ШКОЛА {i}", name=f"Школа {i}", ogrn=str(i)) for i in range(count)]


def test_a_short_answer_is_one_page():
    page = service.page_of(many(3))
    assert (page.page, page.pages, page.total) == (1, 1, 3)
    assert len(page.items) == 3


def test_twenty_results_cut_into_four_pages_of_five():
    page = service.page_of(many(20), 4)
    assert (page.page, page.pages) == (4, 4)
    assert [school.name for school in page.items] == [f"Школа {i}" for i in range(15, 20)]


def test_a_page_past_the_end_is_clamped_rather_than_refused():
    """The pager is a button somebody pressed twice."""
    assert service.page_of(many(7), 99).page == 2
    assert service.page_of(many(7), 0).page == 1
    assert service.page_of(many(7), -5).page == 1


def test_no_results_is_still_one_page_and_not_a_division_by_zero():
    page = service.page_of([])
    assert (page.page, page.pages, page.total) == (1, 1, 0)
    assert page.items == []


def test_truncated_is_carried_through_because_nothing_else_says_it():
    """``pages`` counts what arrived; ``truncated`` says more did not. A client
    that conflates them shows «найдено 20» for a search matching three
    hundred schools."""
    page = service.page_of(many(20), 1, truncated=True)
    assert page.pages == 4
    assert page.truncated is True


def test_the_stored_name_fits_the_column():
    school = School(full_name="Ш" * 400, name="Ш" * 400)
    assert len(service.stored_name(school)) == service.MAX_NAME


def test_a_client_with_a_scrolling_list_can_ask_for_everything_at_once():
    """Four pages of five is four upstream searches for one question; the
    directory has no offset, so every call searches again."""
    page = service.page_of(many(20), 1, size=20)
    assert (page.pages, len(page.items)) == (1, 20)


def test_a_page_size_of_zero_does_not_divide_by_zero():
    page = service.page_of(many(3), 1, size=0)
    assert page.pages == 3


# ---- the key ---------------------------------------------------------------


@pytest.mark.parametrize("token", ["", "   ", "\n", " \t "])
def test_a_key_of_whitespace_is_no_key_at_all(monkeypatch, token):
    """«Set» and «usable» have to be the same question.

    A value pasted into a host's environment form arrives with a space or a
    newline often enough that this is not hypothetical, and untrimmed it was
    truthy: the search went upstream as ``Authorization: Token  ``, came back
    401, and this provider reads a 401 as the daily allowance being spent. So
    a deployment that never had a key was told «Лимит запросов исчерпан» and
    its owner went to look at their DaData billing. ``crypto.cipher()`` has
    always trimmed ``DIARY_SECRET`` for exactly this reason.
    """
    monkeypatch.setattr(get_settings(), "dadata_token", token)
    assert dadata_client.configured() is False


async def test_a_key_of_whitespace_is_not_reported_as_a_spent_quota(monkeypatch):
    """The whole cost of the missing ``strip``, end to end.

    DaData answers a blank key with 401, and 401 here means «the allowance is
    spent or the key was refused» — the one failure the owner is meant to act
    on. So the message was «Лимит запросов к справочнику исчерпан» and the
    owner went and read their billing page, for a deployment that had never
    had a key. Nothing is sent now, so nothing can come back mislabelled.
    """
    monkeypatch.setattr(get_settings(), "dadata_token", "   ")

    def refuses_a_blank_key(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"message": "Authorization required"})

    async def shared():
        return httpx.AsyncClient(
            base_url=dadata_client.BASE_URL,
            transport=httpx.MockTransport(refuses_a_blank_key),
        )

    monkeypatch.setattr(dadata_client, "shared_client", shared)

    with pytest.raises(NotConfigured):
        await dadata_client.suggest_schools("гимназия 3")


def test_a_real_key_is_still_a_real_key(monkeypatch):
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")
    assert dadata_client.configured() is True


# ---- a register row that is not a row at all --------------------------------


def _answering(monkeypatch, *bodies: object) -> list[httpx.Request]:
    """Installs a transport that answers each call with the next body.

    Returns the list the requests land in, because how *many* went out is half
    of what the tests below are about: an empty first answer is what makes
    `suggest_schools` ask a second time without the ОКВЭД filter.
    """
    monkeypatch.setattr(get_settings(), "dadata_token", "<redacted>")
    sent: list[httpx.Request] = []
    answers = list(bodies)

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request)
        body = answers[min(len(sent) - 1, len(answers) - 1)]
        return httpx.Response(200, json=body)

    client = httpx.AsyncClient(
        base_url=dadata_client.BASE_URL, transport=httpx.MockTransport(handler)
    )

    async def shared():
        return client

    monkeypatch.setattr(dadata_client, "shared_client", shared)
    return sent


async def test_an_answer_whose_suggestions_are_not_objects_says_so(monkeypatch, caplog):
    """«Ничего не найдено» is also what a school that is not in the register
    looks like, and nothing said which of the two this was.

    The mapper logs the suggestion it could not read, but it never sees the one
    that was not an object at all — the client had already taken it out. So a
    reshaped answer reached the bot as an empty picker, the person was asked to
    type the name by hand, and the only evidence was a screen somebody had to
    reach first.
    """
    sent = _answering(monkeypatch, {"suggestions": ["1027800000001", "1027800000002"]})

    with caplog.at_level(logging.WARNING, logger="app.providers.dadata.client"):
        found = await dadata_client.suggest_schools("гимназия 3")

    assert found == []
    spoken = [record.getMessage() for record in caplog.records]
    assert any("all 2 suggestion(s)" in line and "str" in line for line in spoken)
    # And the second ask is still made, exactly as before: an empty first
    # answer is what it has always keyed on, and a log line must not move it.
    assert len(sent) == 2


async def test_one_suggestion_of_the_wrong_shape_does_not_shout_or_lose_the_others(
    monkeypatch, caplog
):
    """The tolerance stays: one unreadable row out of twenty is not an error
    screen, and it is not a warning either."""
    sent = _answering(monkeypatch, {"suggestions": [suggestion(), "1027800000002"]})

    with caplog.at_level(logging.WARNING, logger="app.providers.dadata.client"):
        found = await dadata_client.suggest_schools("гимназия 3")

    assert [item["data"]["ogrn"] for item in found] == ["1027800000001"]
    assert caplog.records == []
    # One request, because the first answer was not empty.
    assert len(sent) == 1



# ---- the region a row is in --------------------------------------------------


def test_the_region_code_is_read_from_the_address_kladr_id():
    """The first two digits of the thirteen are the subject: «78» is Saint
    Petersburg however the register spells the name beside it."""
    item = suggestion()
    item["data"]["address"]["data"]["region_kladr_id"] = "7800000000000"
    school = m.to_school(item)
    assert school.region_code == "78"
    assert school.region == "г Санкт-Петербург"
    # And the bot's wire shape does not grow a field for it.
    from app.schemas import SchoolOut

    assert "region_code" not in SchoolOut(**school.model_dump()).model_dump()


@pytest.mark.parametrize("kladr", ["ab00000000000", "7", "²800000000000", "", None, 78])
def test_a_kladr_id_that_is_not_digits_gives_no_code(kladr):
    """No code rather than a guess: the name is still there to place it by."""
    item = suggestion()
    item["data"]["address"]["data"]["region_kladr_id"] = kladr
    school = m.to_school(item)
    assert school.region_code is None
    assert school.region == "г Санкт-Петербург"


def test_a_branch_is_one_row_for_the_bot_and_two_for_the_regions():
    """Same OGRN, two regions. The picker has always folded them and still
    does; the directory keeps one per region, and falls back to the region's
    name for a row that carries no code."""
    head = suggestion()
    head["data"]["address"]["data"]["region_kladr_id"] = "7800000000000"
    branch = suggestion()
    branch["data"]["address"]["data"] = {"region_with_type": "Ленинградская обл"}

    assert len(m.to_schools([head, branch])) == 1
    assert len(m.to_schools([head, branch], per_region=True)) == 2
    # A true duplicate in one region is still one row either way.
    assert len(m.to_schools([head, dict(head)], per_region=True)) == 1


async def test_a_retry_the_caller_refuses_is_not_sent(monkeypatch):
    """The second request costs what the first did, so a caller metering its
    share is asked before it is made — and a «no» is an empty answer, not an
    error."""
    sent = _answering(monkeypatch, {"suggestions": []})
    asked: list[bool] = []

    async def nothing_left() -> bool:
        asked.append(True)
        return False

    found = await dadata_client.suggest_schools("лицей 1535", may_retry=nothing_left)

    assert found == []
    assert len(sent) == 1
    assert asked == [True]


async def test_a_retry_the_caller_allows_is_sent_as_before(monkeypatch):
    sent = _answering(monkeypatch, {"suggestions": []}, {"suggestions": [suggestion()]})
    asked: list[bool] = []

    async def plenty() -> bool:
        asked.append(True)
        return True

    found = await dadata_client.suggest_schools("лицей 1535", may_retry=plenty)

    assert len(found) == 1
    assert len(sent) == 2
    assert asked == [True]


async def test_an_answer_with_rows_never_asks_about_a_retry(monkeypatch):
    sent = _answering(monkeypatch, {"suggestions": [suggestion()]})

    async def never() -> bool:
        raise AssertionError("asked about a retry that was not needed")

    assert len(await dadata_client.suggest_schools("лицей 1535", may_retry=never)) == 1
    assert len(sent) == 1
