// Protocol enum ordinals are held by the shared golden fixtures.
@file:Suppress("MagicNumber")

package com.lumenpearson.lessons.core.data.network.v2.dto

internal object AttendanceDirectionWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "ATTENDANCE_DIRECTION_UNSPECIFIED",
        1 to "ATTENDANCE_DIRECTION_IN",
        2 to "ATTENDANCE_DIRECTION_OUT",
        3 to "ATTENDANCE_DIRECTION_UNKNOWN",
    ),
)

internal object DayKindWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "DAY_KIND_UNSPECIFIED",
        1 to "DAY_KIND_NORMAL",
        2 to "DAY_KIND_HOLIDAY",
        3 to "DAY_KIND_SHORTENED",
        4 to "DAY_KIND_REMOTE",
        5 to "DAY_KIND_SELF_STUDY",
        6 to "DAY_KIND_DAY_OFF",
    ),
)

internal object DayOffReasonWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "DAY_OFF_REASON_UNSPECIFIED",
        1 to "DAY_OFF_REASON_OUT_OF_YEAR",
        2 to "DAY_OFF_REASON_BETWEEN_TERMS",
        3 to "DAY_OFF_REASON_PUBLIC_HOLIDAY",
    ),
)

internal object DiaryEgressWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "DIARY_EGRESS_UNSPECIFIED",
        1 to "DIARY_EGRESS_DIRECT",
        2 to "DIARY_EGRESS_PROXY",
        3 to "DIARY_EGRESS_RELAY",
    ),
)

internal object DiaryFeatureWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "DIARY_FEATURE_UNSPECIFIED",
        1 to "DIARY_FEATURE_SCHEDULE",
        2 to "DIARY_FEATURE_HOMEWORK",
        3 to "DIARY_FEATURE_MARKS",
        4 to "DIARY_FEATURE_PERIODS",
        5 to "DIARY_FEATURE_SUBJECTS",
        6 to "DIARY_FEATURE_TEACHERS",
        7 to "DIARY_FEATURE_ATTENDANCE",
        8 to "DIARY_FEATURE_TURNSTILE",
        9 to "DIARY_FEATURE_MEAL_ACCOUNT",
        10 to "DIARY_FEATURE_FINAL_MARKS",
    ),
)

internal object EventKindWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "EVENT_KIND_UNSPECIFIED",
        1 to "EVENT_KIND_EVENT",
        2 to "EVENT_KIND_CANTEEN",
        3 to "EVENT_KIND_EXAM",
        4 to "EVENT_KIND_TRIP",
        5 to "EVENT_KIND_MEETING",
    ),
)

internal object JoinModeWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "JOIN_MODE_UNSPECIFIED",
        1 to "JOIN_MODE_OPEN",
        2 to "JOIN_MODE_INVITE",
    ),
)

internal object MarkKindWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "MARK_KIND_UNSPECIFIED",
        1 to "MARK_KIND_GRADE",
        2 to "MARK_KIND_ABSENCE",
        3 to "MARK_KIND_LATE",
        4 to "MARK_KIND_REMARK",
        5 to "MARK_KIND_OTHER",
    ),
)

internal object RoleWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "ROLE_UNSPECIFIED",
        1 to "ROLE_VIEWER",
        2 to "ROLE_EDITOR",
        3 to "ROLE_ADMIN",
        4 to "ROLE_OWNER",
    ),
)

internal object SignInMethodWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "SIGN_IN_METHOD_UNSPECIFIED",
        1 to "SIGN_IN_METHOD_PASSWORD",
        2 to "SIGN_IN_METHOD_SESSION_ADOPT",
    ),
)

internal object SubstitutionActionWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "SUBSTITUTION_ACTION_UNSPECIFIED",
        1 to "SUBSTITUTION_ACTION_REPLACE",
        2 to "SUBSTITUTION_ACTION_CANCEL",
    ),
)

internal object TermKindWireSerializer : WireEnumSerializer(
    mapOf(
        0 to "TERM_KIND_UNSPECIFIED",
        1 to "TERM_KIND_QUARTER",
        2 to "TERM_KIND_SEMESTER",
    ),
)
