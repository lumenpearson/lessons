package com.lumenpearson.lessons.core.data.network.v2

import java.time.ZoneId

internal data class ManageReadContext(val zone: ZoneId, val observation: CallObservation)

/** Class metadata and its dependent call must belong to the same actual sent membership. */
internal suspend fun ManageClassRemote.readContext(observation: CallObservation): ManageReadContext {
    val card = classCard(observation)
    return ManageReadContext(managementZone(card.timezone), observation.followUp())
}
