package com.lumenpearson.lessons.core.data.repository

/**
 * A [BundleTagStore] in a map, for the tests that care what is remembered.
 *
 * One fake rather than one per test file, and that is not only tidiness: the
 * three wipes below all key off the `classId|openingYear` shape of a signature,
 * so a copy of them in three places is three chances for the rule the shipped
 * store follows and the rule the tests believe to drift apart.
 *
 * The separator is carried into [forgetClass] on purpose: without it, forgetting
 * class 1 would take class 12 with it, which is the sort of mistake that shows
 * up as one unexplained full download rather than as a failure.
 */
internal class RecordingTagStore : BundleTagStore {

    val written = mutableMapOf<String, String>()

    override suspend fun tagFor(signature: String): String? = written[signature]

    override suspend fun remember(signature: String, etag: String) {
        written[signature] = etag
    }

    override suspend fun forget(signature: String) {
        written.remove(signature)
    }

    override suspend fun forgetClass(classId: Long) {
        written.keys.removeAll { it.startsWith("$classId|") }
    }

    override suspend fun forgetClassesOtherThan(keep: Collection<Long>) {
        val kept = keep.map { "$it|" }
        written.keys.removeAll { signature -> kept.none { signature.startsWith(it) } }
    }
}
