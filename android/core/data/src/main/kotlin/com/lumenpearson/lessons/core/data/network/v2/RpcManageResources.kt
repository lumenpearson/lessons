package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.BellPeriod
import com.lumenpearson.lessons.contract.v2.BellSchedule
import com.lumenpearson.lessons.contract.v2.JoinMode
import com.lumenpearson.lessons.contract.v2.School
import com.lumenpearson.lessons.contract.v2.SchoolClass
import com.lumenpearson.lessons.contract.v2.Subject
import com.lumenpearson.lessons.core.data.network.v2.dto.BellPeriodDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.v2.dto.JoinModeWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectDto

internal fun SchoolClass.canonicalManagedClass() = SchoolClassDto(
    id = id, name = name, school = school.takeIf { hasSchool() }, city = city.takeIf { hasCity() },
    timezone = timezone, timezoneLabel = timezoneLabel, joinCode = joinCode,
    joinMode = JoinModeWireSerializer.wireName(joinModeValue), members = members, devices = devices,
    pendingRequests = pendingRequests, bellScheduleId = bellScheduleId.takeIf { hasBellScheduleId() },
    calendarReady = calendarReady,
)

internal fun Subject.canonicalManagedSubject() = SubjectDto(
    id, name, shortName.takeIf { hasShortName() }, teacher.takeIf { hasTeacher() }, color.takeIf { hasColor() },
)

internal fun BellSchedule.canonicalManagedBells() = BellScheduleDto(
    id, name, isDefault, periodsList.map { BellPeriodDto(it.index, it.startsAt, it.endsAt) },
)

internal fun School.canonicalManagedSchool() = SchoolDto(
    name, fullName, ogrn.takeIf { hasOgrn() }, inn.takeIf { hasInn() }, address.takeIf { hasAddress() },
    city.takeIf { hasCity() }, region.takeIf { hasRegion() }, active,
)

internal fun SchoolClassDto.rpcManagedClass(): SchoolClass = SchoolClass.newBuilder()
    .setName(name).setTimezone(timezone).apply {
        this@rpcManagedClass.school?.let { setSchool(it) }
        this@rpcManagedClass.city?.let { setCity(it) }
        when (this@rpcManagedClass.joinMode) {
            "JOIN_MODE_OPEN" -> setJoinMode(JoinMode.JOIN_MODE_OPEN)
            "JOIN_MODE_INVITE" -> setJoinMode(JoinMode.JOIN_MODE_INVITE)
            else -> Unit
        }
    }.build()

internal fun SubjectDto.rpcManagedSubject(): Subject = Subject.newBuilder().setId(id).setName(name).apply {
    this@rpcManagedSubject.shortName?.let { setShortName(it) }
    this@rpcManagedSubject.teacher?.let { setTeacher(it) }
    this@rpcManagedSubject.color?.let { setColor(it) }
}.build()

internal fun BellScheduleDto.rpcManagedBells(): BellSchedule = BellSchedule.newBuilder()
    .setId(id).setName(name).setIsDefault(isDefault).addAllPeriods(periods.map {
        BellPeriod.newBuilder().setIndex(it.index).setStartsAt(it.startsAt).setEndsAt(it.endsAt).build()
    }).build()
