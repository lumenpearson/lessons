package com.lumenpearson.lessons.core.data.network.v2

import retrofit2.Retrofit

internal class RestManageRemote private constructor(parts: RestManageParts) : ManageRemote,
    ManageClassRemote by parts.classes,
    ManageSubjectRemote by parts.subjects,
    ManageBellRemote by parts.bells,
    ManageTimetableRemote by parts.timetable,
    ManageDeviceRemote by parts.devices,
    ManageRequestRemote by parts.requests,
    ManageReportRemote by parts.reports {
    constructor(retrofit: Retrofit) : this(RestManageParts(retrofit))
}

private class RestManageParts(retrofit: Retrofit) {
    val classes = RestManageClassRemote(retrofit.create(V2ManageClassApi::class.java),
        retrofit.create(V2DirectoryApi::class.java))
    val subjects = RestManageSubjectRemote(retrofit.create(V2ManageSubjectApi::class.java))
    val bells = RestManageBellRemote(retrofit.create(V2ManageBellApi::class.java))
    val timetable = RestManageTimetableRemote(retrofit.create(V2ManageTimetableApi::class.java))
    val devices = RestManageDeviceRemote(retrofit.create(V2ManageDeviceApi::class.java), classes)
    val requests = RestManageRequestRemote(retrofit.create(V2ManageRequestApi::class.java), classes)
    val reports = RestManageReportRemote(retrofit.create(V2ManageReportApi::class.java), classes)
}
