package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.DeviceLink

/** Each operation returns a complete linking view owned by one membership. */
internal interface MeRemote {
    suspend fun refresh(observation: CallObservation = CallObservation()): DeviceLink
    suspend fun unlink(observation: CallObservation = CallObservation()): DeviceLink
}
