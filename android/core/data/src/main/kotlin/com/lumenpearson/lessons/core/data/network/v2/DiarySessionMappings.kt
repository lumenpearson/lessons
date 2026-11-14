package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDiarySessionRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiarySessionDto
import com.lumenpearson.lessons.core.data.network.v2.dto.NetSchoolCookiesDto
import com.lumenpearson.lessons.core.data.network.v2.dto.NetSchoolCredentialDto
import com.lumenpearson.lessons.core.data.network.v2.dto.PetersburgCredentialDto
import com.lumenpearson.lessons.core.data.repository.DiaryCredential
import com.lumenpearson.lessons.core.data.repository.DiaryHandover
import com.lumenpearson.lessons.core.data.repository.DiaryLogin
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryTarget

internal fun DiaryHandover.restRequest(): CreateDiarySessionRequestDto = when (val secret = credential) {
    is DiaryCredential.Petersburg -> CreateDiarySessionRequestDto(
        login = target.login, petersburg = PetersburgCredentialDto(secret.token),
    )
    is DiaryCredential.NetSchool -> CreateDiarySessionRequestDto(
        login = target.login, region = target.region, schoolId = target.schoolId,
        netschool = NetSchoolCredentialDto(
            secret.at, NetSchoolCookiesDto(secret.sessionCookie, secret.securityCookie), secret.ver, secret.timeOut,
        ),
    )
}

internal fun DiarySessionDto.toRegistration(sent: DiaryTarget, status: Int = 200): DiaryRegistration {
    if (token.isBlank()) throw malformedV2Payload("The response has no diary token", status)
    val cleanedLogin = DiaryLogin.clean(login) ?: DiaryLogin.clean(sent.login) ?: sent.login.trim()
    // The submitted provider is authoritative; an echo must never redirect the account to another upstream.
    val target = sent.copy(
        region = region ?: sent.region,
        schoolId = schoolId ?: sent.schoolId,
        schoolName = schoolName?.takeIf { it.isNotBlank() } ?: sent.schoolName,
        login = cleanedLogin,
        zone = zone.takeIf { it.isNotBlank() } ?: sent.zone,
    )
    return DiaryRegistration(DiarySession(cleanedLogin, token, target), students.map { it.toDomain() })
}
