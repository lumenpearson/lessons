package com.lumenpearson.lessons.core.data.network.v2

internal class RpcManageRemote private constructor(parts: RpcManageParts) : ManageRemote,
    ManageClassRemote by parts.classes,
    ManageSubjectRemote by parts.subjects,
    ManageBellRemote by parts.bells,
    ManageTimetableRemote by parts.timetable,
    ManageDeviceRemote by parts.devices,
    ManageRequestRemote by parts.requests,
    ManageReportRemote by parts.reports {
    constructor(rpc: RpcCalls) : this(RpcManageParts(rpc))
}

private class RpcManageParts(rpc: RpcCalls) {
    val classes = RpcManageClassRemote(rpc)
    val subjects = RpcManageSubjectRemote(rpc)
    val bells = RpcManageBellRemote(rpc)
    val timetable = RpcManageTimetableRemote(rpc)
    val devices = RpcManageDeviceRemote(rpc, classes)
    val requests = RpcManageRequestRemote(rpc, classes)
    val reports = RpcManageReportRemote(rpc, classes)
}
