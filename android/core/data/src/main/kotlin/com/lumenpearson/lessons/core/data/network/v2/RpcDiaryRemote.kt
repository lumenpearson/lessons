package com.lumenpearson.lessons.core.data.network.v2

internal class RpcDiaryRemote(rpc: RpcCalls) : DiaryRemote,
    DiarySessionRemote by RpcDiarySessionRemote(rpc),
    DiaryReadRemote by RpcDiaryReadRemote(rpc),
    DiaryCorrectionRemote by RpcDiaryCorrectionRemote(rpc)
