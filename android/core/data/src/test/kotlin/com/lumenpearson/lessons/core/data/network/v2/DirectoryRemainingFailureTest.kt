package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.catalog.DirectoryProblem
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryRemainingFailureTest {
    @Test
    fun `directory reasons distinguish daily allowance from per caller throttling`() {
        val cases = listOf(
            "DIRECTORY_DISABLED" to DirectoryProblem.Disabled,
            "DIRECTORY_SPENT" to DirectoryProblem.Spent(null),
            "DIRECTORY_UNAVAILABLE" to DirectoryProblem.Upstream,
            "THROTTLED" to DirectoryProblem.Throttled(null),
            "VALIDATION_FAILED" to DirectoryProblem.TooShort,
        )
        for ((reason, want) in cases) assertEquals(reason, want, DirectoryProblem.of(remainingRefusal(reason)))
        assertEquals(DirectoryProblem.Upstream, DirectoryProblem.of(remainingRefusal(null, status = 504)))
    }
}
