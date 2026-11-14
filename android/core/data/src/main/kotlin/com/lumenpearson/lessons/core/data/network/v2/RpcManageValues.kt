package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.ClassStats
import com.lumenpearson.lessons.contract.v2.ImportTimetableResponse
import com.lumenpearson.lessons.contract.v2.Timetable
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassStatsDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ImportConflictDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ImportTimetableResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleCountDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectHoursDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TimetableDto

internal fun Timetable.canonicalManagedTimetable() = TimetableDto(text, lessons)

internal fun ImportTimetableResponse.canonicalManagedImport() = ImportTimetableResponseDto(
    applied, daysList, lessons, bells,
    conflictsList.map { ImportConflictDto(it.weekday, it.existing, it.incoming) }, rejectedList,
)

internal fun ClassStats.canonicalManagedStats() = ClassStatsDto(
    today, lessonsPerWeek, subjectsCount3, subjects4List.map { SubjectHoursDto(it.name, it.hours) },
    homeworkOpen, homeworkTotal,
    membersByRoleList.map { RoleCountDto(RoleWireSerializer.wireName(it.roleValue), it.count) },
    devicesActive, substitutionsUpcoming, eventsUpcoming,
)
