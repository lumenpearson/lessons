package com.lumenpearson.lessons.core.data.network.v2

import java.time.Clock
import okhttp3.OkHttpClient
import retrofit2.Retrofit

internal data class V2Remotes(
    val join: JoinRemote,
    val schedule: ScheduleRemote,
    val me: MeRemote,
    val diary: DiaryRemote,
    val directory: DirectoryRemote,
    val manage: ManageRemote,
) {
    companion object {
        fun using(client: OkHttpClient, retrofit: Retrofit, transport: AppTransport, clock: Clock): V2Remotes {
            return if (transport == AppTransport.REST) {
                val api = retrofit.create(V2Api::class.java)
                V2Remotes(RestJoinRemote(api), RestScheduleRemote(api, clock), RestMeRemote(api),
                    RestDiaryRemote(retrofit), RestDirectoryRemote(retrofit.create(V2DirectoryApi::class.java)),
                    RestManageRemote(retrofit))
            } else {
                val rpc = RpcCalls.using(client, transport.rpcProtocol)
                V2Remotes(RpcJoinRemote(rpc), RpcScheduleRemote(rpc, clock), RpcMeRemote(rpc),
                    RpcDiaryRemote(rpc), RpcDirectoryRemote(rpc), RpcManageRemote(rpc))
            }
        }
    }
}
