package com.lumenpearson.lessons.widget

import android.content.Context
import android.content.ContextWrapper
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.di.GraphOwner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uses this module's existing Android stubs; no database or preference is opened. */
class GraphInitializationTest {
    @Before fun before() = clearGraph()
    @After fun after() = clearGraph()

    @Test
    fun `a background caller uses the application owner's actual configuration`() {
        val application = Owner()
        val receiver = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = application
        }
        Graph.ensureInitialized(receiver)
        val first = Graph.container
        application.initializeLessonsGraph()
        assertSame(first, Graph.container)
        assertConfiguration(first)
    }

    @Test
    fun `foreground first and repeated background calls keep one container`() {
        val application = Owner()
        application.initializeLessonsGraph()
        val first = Graph.container
        repeat(3) { Graph.ensureInitialized(application) }
        assertSame(first, Graph.container)
        assertConfiguration(first)
    }

    @Test
    fun `concurrent first callers publish one container with the required configuration`() {
        val application = Owner()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val containers = pool.invokeAll(List(16) { Callable {
                Graph.ensureInitialized(application)
                Graph.container
            } }).map { it.get() }
            assertTrue(containers.all { it === containers.first() })
            assertConfiguration(containers.first())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an application without the owner fails instead of inventing defaults`() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        assertTrue(runCatching { Graph.ensureInitialized(context) }.exceptionOrNull() is IllegalStateException)
    }

    private fun assertConfiguration(container: Any) {
        fun field(name: String): Any? = container.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(container)
        }
        assertEquals("grpc", field("transport"))
        assertEquals(73, field("clientVersion"))
        assertEquals(true, field("appStreaming"))
        assertEquals("synthetic-client", field("githubClientId"))
    }

    private fun clearGraph() {
        Graph.javaClass.getDeclaredField("instance").apply { isAccessible = true }.set(Graph, null)
    }

    private class Owner : ContextWrapper(null), GraphOwner {
        override fun getApplicationContext(): Context = this
        override fun initializeLessonsGraph() {
            Graph.init(this, transport = "grpc", clientVersion = 73, appStreaming = true,
                githubClientId = "synthetic-client")
        }
    }
}
