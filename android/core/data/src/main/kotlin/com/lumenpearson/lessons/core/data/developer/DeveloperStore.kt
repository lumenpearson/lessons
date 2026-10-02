package com.lumenpearson.lessons.core.data.developer

import kotlinx.coroutines.flow.Flow

/** What the mode keeps between launches: whether it was found, GitHub's last answer, and the switches. */
data class DeveloperStored(
    val revealed: Boolean = false,
    val verdict: DeveloperVerdict? = null,
    val tools: Set<DeveloperTool> = emptySet(),
)

/**
 * The mode's half of the disk, as an interface so the rules in
 * [DeveloperModeImpl] can be tested without a DataStore;
 * [DeveloperPreferences] is the one on a phone.
 */
internal interface DeveloperStore {
    val stored: Flow<DeveloperStored>
    suspend fun update(transform: (DeveloperStored) -> DeveloperStored)
}
