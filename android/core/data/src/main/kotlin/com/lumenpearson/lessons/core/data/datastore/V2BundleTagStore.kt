package com.lumenpearson.lessons.core.data.datastore

import com.lumenpearson.lessons.core.data.repository.BundleTagStore

/** Requests read only v2 tags; lifecycle wipes remove both generations together. */
internal class V2BundleTagStore(private val preferences: LessonsPreferences) : BundleTagStore {
    override suspend fun tagFor(signature: String): String? =
        runCatching { preferences.v2BundleTag(signature) }.getOrNull()

    override suspend fun remember(signature: String, etag: String) {
        // Losing a shortcut on a full disk must not fail a completed cache write.
        runCatching { preferences.writeV2BundleTag(signature, etag) }
    }

    override suspend fun forget(signature: String) {
        runCatching { preferences.forgetBundleTag(signature) }
    }

    override suspend fun forgetClass(classId: Long) {
        runCatching { preferences.forgetBundleTagsOf(classId) }
    }

    override suspend fun forgetClassesOtherThan(keep: Collection<Long>) {
        runCatching { preferences.forgetBundleTagsOutside(keep) }
    }
}
