package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.contract.v2.CreateDiarySessionRequest
import com.lumenpearson.lessons.contract.v2.DiarySession
import com.lumenpearson.lessons.contract.v2.NetSchoolCookies
import com.lumenpearson.lessons.contract.v2.NetSchoolCredential
import com.lumenpearson.lessons.contract.v2.PetersburgCredential
import com.lumenpearson.lessons.core.data.network.v2.dto.DiarySessionDto
import com.lumenpearson.lessons.core.data.repository.DiaryCredential
import com.lumenpearson.lessons.core.data.repository.DiaryHandover

internal fun DiaryHandover.rpcRequest(): CreateDiarySessionRequest = CreateDiarySessionRequest.newBuilder()
    .setLogin(target.login).apply {
        when (val secret = credential) {
            is DiaryCredential.Petersburg -> setPetersburg(
                PetersburgCredential.newBuilder().setToken(secret.token).build(),
            )
            is DiaryCredential.NetSchool -> {
                target.region?.let { setRegion(it) }
                target.schoolId?.let { setSchoolId(it) }
                setNetschool(NetSchoolCredential.newBuilder().setAt(secret.at).setCookies(
                    NetSchoolCookies.newBuilder().setNsSessionId(secret.sessionCookie).apply {
                        secret.securityCookie?.let { setEsrnSec(it) }
                    }.build(),
                ).apply {
                    secret.ver?.let { setVer(it) }
                    secret.timeOut?.let { setTimeOut(it) }
                }.build())
            }
        }
    }.build()

internal fun DiarySession.canonicalSession() = DiarySessionDto(
    token, login, provider, region.takeIf { hasRegion() }, schoolId.takeIf { hasSchoolId() },
    schoolName.takeIf { hasSchoolName() }, zone, studentsList.map { it.canonicalStudent() },
)
