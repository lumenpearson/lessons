package com.lumenpearson.lessons.core.data.datastore

import kotlinx.coroutines.flow.first

/**
 * What the sync keeps in the preferences file beside the settings: one `ETag`
 * per window it holds, and the shape of the last schedule it saw.
 *
 * Over the store rather than over the file, so that every write here goes
 * through [LessonsPreferences.write] and refreshes the credentials like every
 * other, and the store keeps its `dataStore` to itself. Its callers still ask
 * [LessonsPreferences], which hands each call on.
 */
internal class BundleTags(private val store: LessonsPreferences) {

    /**
     * The shape of the cached schedule as of the previous sync.
     *
     * Kept here rather than in [AppSettings] because it is not a preference and
     * nothing outside the notification code has any business reading it: it
     * exists only so a sync can tell "the timetable changed" from "the timetable
     * was fetched again".
     */
    suspend fun scheduleFingerprint(): String? = store.preferences.first()[PreferenceKeys.KEY_SCHEDULE_FINGERPRINT]

    /** @see scheduleFingerprint */
    suspend fun writeScheduleFingerprint(value: String) {
        store.write { prefs -> prefs[PreferenceKeys.KEY_SCHEDULE_FINGERPRINT] = value }
    }

    /**
     * The ETag of one `/bundle` window this phone holds, if it holds that one.
     *
     * A key per signature rather than one signature and one tag, because the
     * cache holds a school year per window now and a store with room for one
     * would make every step between two years a full download in both
     * directions. The signature is `classId|openingYear`, so a class switch or
     * another year is simply a different key and answers `null` — which asks
     * for the whole window, the direction that heals itself.
     *
     * Nothing here bounds the number of them; the three `forget…` calls below
     * do, one per shape of wipe the cache has — a year evicted, a class left,
     * a sign-out. The alternative is a preferences file that only ever grows,
     * one entry per class-year anybody ever synced, which is what this was
     * until leaving a class started reaching it.
     */
    suspend fun bundleTag(signature: String): String? =
        store.preferences.first()[PreferenceKeys.bundleTagKey(signature)]

    /** @see bundleTag */
    suspend fun writeBundleTag(signature: String, etag: String) {
        store.write { prefs -> prefs[PreferenceKeys.bundleTagKey(signature)] = etag }
    }

    /** @see bundleTag */
    suspend fun forgetBundleTag(signature: String) {
        store.write { prefs -> prefs.remove(PreferenceKeys.bundleTagKey(signature)) }
    }

    /**
     * Every year's tag for one class, for a class being left or re-joined.
     *
     * By key prefix rather than by asking which years are cached, because the
     * two can disagree in exactly the state this has to clean up: the tags
     * outlive a destructive Room migration and a `dao.clear`, and a tag whose
     * window row is already gone is precisely the one nothing else would find.
     *
     * @see bundleTag
     */
    suspend fun forgetBundleTagsOf(classId: Long) {
        val prefix = PreferenceKeys.bundleTagPrefix(classId)
        store.write { prefs -> prefs.removeKeysStartingWith { it.startsWith(prefix) } }
    }

    /**
     * The tags of every class outside [keep]; an empty [keep] is all of them.
     *
     * @see bundleTag
     */
    suspend fun forgetBundleTagsOutside(keep: Collection<Long>) {
        val kept = keep.map { PreferenceKeys.bundleTagPrefix(it) }
        store.write { prefs ->
            prefs.removeKeysStartingWith { name ->
                name.startsWith(PreferenceKeys.BUNDLE_TAG_PREFIX) && kept.none { name.startsWith(it) }
            }
        }
    }
}
