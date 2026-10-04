"""The wire format docs/api.md promises for v2, met by the runtime that will serve it.

Nothing serves v2 yet, so «What the values look like» in ``docs/api.md`` has
never been read by a parser. ``test_contract.py`` holds what the descriptors say
about methods; this file holds what the generated messages do on the wire, with
the same runtime sub-project 3's server will use (protobuf-py, not Google's
``protobuf``):

- field names are lowerCamelCase, 64-bit integers are strings, an enum is its
  name and a default is left out, a ``Timestamp`` is RFC 3339 with ``Z``;
- a date, a time of day and a wall-clock moment are plain strings, and the
  proto comments say which format each is;
- in binary, an enum value the reader does not know survives a parse and a
  serialize as the number it was (the comment above ``DiaryFeature``);
- in JSON, an unknown enum name or an unknown field is **refused by default** and
  dropped only under ``ignore_unknown_fields=True``, which is why every v2
  client, sub-project 5's included, has to decode enum names leniently: this
  server writes whatever names it has, and an older client reads them;
- field 4 of ``ScheduleWindow`` is reserved, so ``nextSchoolDay`` is a field
  nobody declares any more.

Every assertion pins what the runtime does, not what the documents hope.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
from protobuf.wkt.google.protobuf.timestamp_pb import Timestamp

from app.contract.lessons.v2 import diary_pb, schedule_pb

PROTO = Path(__file__).resolve().parents[2] / "proto" / "lessons" / "v2"

#: 2026-09-21T14:13:20Z, picked so the seconds are visible in the output.
INSTANT_SECONDS = 1790000000


def _json(message) -> dict:
    return json.loads(message.to_json())


def _features_from_json(text: str, *, lenient: bool) -> diary_pb.ProviderCapabilities:
    return diary_pb.ProviderCapabilities.from_json(text, ignore_unknown_fields=lenient)


# The docs write `createdAt` where the proto writes `created_at`. Without this,
# a runtime that kept the proto name would break every client the docs taught.
def test_json_names_are_lower_camel_case():
    student = diary_pb.DiaryStudent(first_name="Аня", class_name="9А")

    assert _json(student) == {"firstName": "Аня", "className": "9А"}
    assert "first_name" not in student.to_json()


# A diary id is 64-bit and a JavaScript number is not. The docs promise a string
# on the wire, and a client built on a double would round the id silently.
def test_an_int64_is_a_json_string_and_reads_back_as_an_int():
    big = 2**53 + 1  # the first integer a double cannot hold
    student = diary_pb.DiaryStudent(id=big)

    assert _json(student) == {"id": str(big)}
    assert diary_pb.DiaryStudent.from_json(student.to_json()).id == big


# Canonical proto3 JSON leaves a default out, and the default of every enum is
# its …_UNSPECIFIED value. So «none», which docs/api.md says UNSPECIFIED is,
# is an absent key on the wire, never the string "…_UNSPECIFIED": a client must
# read a missing enum as none rather than wait for the name.
def test_an_enum_is_its_name_and_unspecified_is_left_out():
    inbound = diary_pb.DiaryAttendance(direction=diary_pb.AttendanceDirection.IN)
    none = diary_pb.DiaryAttendance(direction=diary_pb.AttendanceDirection.UNSPECIFIED)

    assert _json(inbound) == {"direction": "ATTENDANCE_DIRECTION_IN"}
    assert _json(none) == {}
    assert "UNSPECIFIED" not in none.to_json()
    # Parsing the name back is the other half of «enums by name».
    parsed = diary_pb.DiaryAttendance.from_json('{"direction":"ATTENDANCE_DIRECTION_OUT"}')
    assert parsed.direction is diary_pb.AttendanceDirection.OUT


# An instant is a google.protobuf.Timestamp, and in JSON that is an RFC 3339
# string in UTC, not a wall time with no zone as v1 sent.
def test_a_timestamp_is_rfc3339_in_utc():
    whole = diary_pb.DiaryCorrection(updated_at=Timestamp(seconds=INSTANT_SECONDS))
    fractional = diary_pb.DiaryCorrection(
        updated_at=Timestamp(seconds=INSTANT_SECONDS, nanos=5_000_000)
    )

    assert _json(whole) == {"updatedAt": "2026-09-21T14:13:20Z"}
    assert _json(fractional) == {"updatedAt": "2026-09-21T14:13:20.005Z"}
    parsed = diary_pb.DiaryCorrection.from_json('{"updatedAt":"2026-09-21T17:13:20+03:00"}')
    assert parsed.updated_at.seconds == INSTANT_SECONDS


# A date, a time and a wall-clock moment are `string` fields, so the runtime
# neither checks nor rewrites them: the format is a convention, and this holds
# that the proto comments state it, and that what a client sends is what comes
# back. «08:30:00», v1's spelling, passes through just as unharmed, which is why
# the convention has to be written down where a reader looks.
def test_dates_and_times_are_plain_strings_documented_in_the_proto():
    lesson = diary_pb.DiaryLesson(starts_at="08:30", ends_at="09:15")
    day = diary_pb.DiaryScheduleDay(date="2026-10-05")
    seen = diary_pb.DiaryAttendance(at="2026-10-05T08:30")

    assert _json(lesson) == {"startsAt": "08:30", "endsAt": "09:15"}
    assert _json(day) == {"date": "2026-10-05"}
    assert _json(seen) == {"at": "2026-10-05T08:30"}
    assert _json(diary_pb.DiaryLesson(starts_at="08:30:00")) == {"startsAt": "08:30:00"}

    text = (PROTO / "diary.proto").read_text(encoding="utf-8")
    assert re.search(r'// "YYYY-MM-DD"\.\n\s*string date = 1;', text)
    assert re.search(r'// "HH:MM"\.\n\s*optional string starts_at = ', text)
    assert re.search(r'// "YYYY-MM-DDTHH:MM"[^\n]*\n\s*string at = 1;', text)


# The comment above DiaryFeature: in binary an old client keeps an unknown enum
# value as a number. Proved here by handing the parser bytes carrying 99, in the
# packed form protoc writes and in the unpacked form a sender may use, and by
# writing the message back. Both come back as the number; the packed one is
# rewritten as one element in a packed run, so the bytes are not byte-for-byte
# the input but the value is intact.
def test_an_unknown_enum_number_survives_a_binary_round_trip():
    packed = bytes([0x22, 0x02, 0x01, 99])  # field 4, packed: SCHEDULE, 99
    unpacked = bytes([0x20, 99])  # field 4, one varint

    for data in (packed, unpacked):
        parsed = diary_pb.ProviderCapabilities.from_binary(data)
        assert 99 in parsed.features
        again = diary_pb.ProviderCapabilities.from_binary(parsed.to_binary())
        assert list(again.features) == list(parsed.features)

    mixed = diary_pb.ProviderCapabilities.from_binary(packed)
    assert mixed.features[0] is diary_pb.DiaryFeature.SCHEDULE
    assert int(mixed.features[1]) == 99
    assert diary_pb.DiaryFeature(99) not in set(diary_pb.DiaryFeature)


# In canonical JSON the same unknown value is a *name*, and what this runtime
# does with a name it has never seen is refuse the whole message, a repeated
# field's element included, unless told to ignore unknown fields, and then it
# drops the element and keeps the rest. A server built on this runtime reads
# that way, and Kotlin's strict decoder does the same to a client: that is why
# sub-project 5's clients must decode enum names leniently, element by element.
def test_an_unknown_enum_name_is_refused_by_default_and_dropped_when_lenient():
    alone = '{"features":["DIARY_FEATURE_NEW"]}'
    inside = '{"features":["DIARY_FEATURE_SCHEDULE","DIARY_FEATURE_NEW","DIARY_FEATURE_HOMEWORK"]}'
    single = '{"kind":"MARK_KIND_NEW"}'

    with pytest.raises(ValueError, match="DIARY_FEATURE_NEW"):
        _features_from_json(alone, lenient=False)
    with pytest.raises(ValueError, match="DIARY_FEATURE_NEW"):
        _features_from_json(inside, lenient=False)
    with pytest.raises(ValueError, match="MARK_KIND_NEW"):
        diary_pb.DiaryMark.from_json(single)

    assert list(_features_from_json(alone, lenient=True).features) == []
    kept = _features_from_json(inside, lenient=True)
    assert list(kept.features) == [
        diary_pb.DiaryFeature.SCHEDULE,
        diary_pb.DiaryFeature.HOMEWORK,
    ]
    # A singular enum the parser drops is the default, which is «none».
    assert diary_pb.DiaryMark.from_json(single, ignore_unknown_fields=True).kind == (
        diary_pb.MarkKind.UNSPECIFIED
    )


# The number form of an unknown value is the one place the JSON parser is
# *more* forgiving than for a name: an open proto3 enum accepts any integer and
# writes it back as an integer. Pinned so nobody reads «unknown value» in JSON as
# one behaviour: a name is refused, a number is kept.
def test_an_unknown_enum_number_in_json_is_kept_and_written_as_a_number():
    parsed = _features_from_json('{"features":[1,99]}', lenient=False)

    assert [int(feature) for feature in parsed.features] == [1, 99]
    assert _json(parsed) == {"features": ["DIARY_FEATURE_SCHEDULE", 99]}
    assert list(_features_from_json('{"features":[99]}', lenient=True).features) == []


# An unknown JSON field is an error unless the parser is told to ignore it, and
# that includes `nextSchoolDay`, the field v2 dropped: a v1-shaped body sent to
# a v2 server is refused rather than half-read, and a client that still sends
# it only gets through by being lenient. Both spellings of the name count.
@pytest.mark.parametrize(
    "body", ['{"nextSchoolDay":"2026-10-06"}', '{"next_school_day":"2026-10-06"}']
)
def test_next_school_day_is_an_unknown_json_field(body):
    with pytest.raises(ValueError, match="next_?[sS]chool_?[dD]ay"):
        schedule_pb.ScheduleWindow.from_json(body)

    kept = schedule_pb.ScheduleWindow.from_json(body, ignore_unknown_fields=True)
    assert _json(kept) == {}


def test_any_unknown_json_field_is_refused_by_default_and_ignored_when_lenient():
    body = '{"days":[],"somethingNew":1}'

    with pytest.raises(ValueError, match="somethingNew"):
        schedule_pb.ScheduleWindow.from_json(body)
    assert _json(schedule_pb.ScheduleWindow.from_json(body, ignore_unknown_fields=True)) == {}


# `reserved 4` is what stops a later field from taking the number a v1-shaped
# peer still writes under it. The runtime's descriptor carries the reservation
# of both the number and the name, and no live field uses either.
def test_schedule_window_reserves_field_4_and_its_name():
    desc = schedule_pb.ScheduleWindow.desc()
    reserved = desc.proto.reserved_range

    assert [(r.start, r.end) for r in reserved] == [(4, 5)]  # end is exclusive
    assert list(desc.proto.reserved_name) == ["next_school_day"]
    assert 4 not in {field.number for field in desc.fields}
    assert "next_school_day" not in {field.name for field in desc.fields}


# The bytes a v1-shaped peer would send under field 4 are an unknown field to
# this message. They are kept and written back by default, as any unknown field
# is, and dropped by the option, so a v2 server that must not echo them
# parses leniently or serializes without unknown fields.
def test_binary_field_4_is_carried_as_unknown_and_can_be_dropped():
    data = bytes([0x22, 0x01, ord("A")])  # field 4, length-delimited, "A"

    parsed = schedule_pb.ScheduleWindow.from_binary(data)
    assert parsed.to_binary() == data
    assert parsed.to_binary(write_unknown_fields=False) == b""
    assert (
        schedule_pb.ScheduleWindow.from_binary(data, ignore_unknown_fields=True).to_binary() == b""
    )
