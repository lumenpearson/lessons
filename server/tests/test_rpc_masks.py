"""One reading of an ``update_mask`` for every ``Update*`` method (AIP-134).

``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 5: an explicit mask is
taken as it is, a path the method does not change is refused without being
repeated, and no mask means every field the request sets.
"""

from __future__ import annotations

import pytest
from protobuf.wkt import FieldMask

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.subject_pb import Subject
from app.rpc.errors import Refusal
from app.rpc.masks import NOT_CHANGEABLE, update_paths

CHANGEABLE = ("name", "short_name", "teacher", "color")


def test_no_mask_means_every_field_the_resource_sets() -> None:
    # An optional field set to "" is set: it clears, which is what was sent.
    sent = Subject(id=4, short_name="Физ", teacher="")
    assert update_paths(None, sent, CHANGEABLE) == ["short_name", "teacher"]
    assert update_paths(FieldMask(paths=[]), sent, CHANGEABLE) == ["short_name", "teacher"]
    assert update_paths(None, None, CHANGEABLE) == []


def test_a_mask_is_taken_as_it_is_in_the_method_s_order_once_each() -> None:
    mask = FieldMask(paths=["color", "name", "color"])
    assert update_paths(mask, Subject(), CHANGEABLE) == ["name", "color"]


@pytest.mark.parametrize("path", ["id", "*", "subject.name", "Pa55w0rd-s3cr3t-Hunter2"])
def test_a_path_the_method_does_not_change_is_refused_without_repeating_it(path) -> None:
    with pytest.raises(Refusal) as refused:
        update_paths(FieldMask(paths=["teacher", path]), Subject(), CHANGEABLE)
    assert refused.value.reason is ErrorReason.VALIDATION_FAILED
    assert refused.value.violations == (("update_mask", NOT_CHANGEABLE),)
    assert path not in refused.value.message
