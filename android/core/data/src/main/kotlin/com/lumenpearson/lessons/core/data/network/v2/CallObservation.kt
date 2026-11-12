package com.lumenpearson.lessons.core.data.network.v2

/** Owned by one call; callback and coroutine threads never share it with another request. */
internal class CallObservation {
    @Volatile
    var status: Int? = null

    @Volatile
    var failure: Throwable? = null
}
