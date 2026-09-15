"""The school directory: what a ЕГРЮЛ row becomes, and how it is paged.

Every payload below is DaData's documented ``suggest/party`` shape. Nothing
here reaches the network — the point of the provider boundary is that the
mapper and the paging can be tested without a key, and they are the two halves
that decide what somebody sees.
"""

from __future__ import annotations

import pytest

from app.providers.dadata import mapper as m
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


def test_only_the_first_word_is_capitalised():
    """«Средняя Школа» reads as two proper nouns; nobody writes it that way."""
    assert m.humanise("ОСНОВНАЯ ОБЩЕОБРАЗОВАТЕЛЬНАЯ ШКОЛА") == "Основная общеобразовательная школа"


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
    # The second copy is a duplicate ОГРН, so one survives, not two.
    assert len(schools) == 1


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
