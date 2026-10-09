package com.lumenpearson.lessons.appicon

import android.content.Intent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The update broadcast reconciles, and reaches the receiver at all; over
 * [TestCatalog], for the reason `LauncherAliasesTest` gives.
 */
@RunWith(RobolectricTestRunner::class)
class PackageReplacedReceiverTest {

    private val context = RuntimeEnvironment.getApplication()
    private val default = TestCatalog.default

    @After
    fun forgetTheStore() = AppIcons.override(null)

    private fun install(components: FakeLauncherComponents) = AppIcons.override(
        AppIconStore(
            TestCatalog.aliases(components),
            io = Dispatchers.Unconfined,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ),
    )

    @Test
    fun `an update reconciles the launcher icons`() {
        val components = TestCatalog.launcher(mapOf(default.alias to false))
        install(components)

        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `any other broadcast is left alone`() {
        val components = TestCatalog.launcher(mapOf(default.alias to false))
        install(components)

        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `the manifest hands it the update broadcast`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = Regex(
            """<receiver\s+android:name="\.appicon\.PackageReplacedReceiver".*?</receiver>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)

        val declared = checkNotNull(receiver) { "no PackageReplacedReceiver in the manifest" }.value
        assertTrue("android.intent.action.MY_PACKAGE_REPLACED" in declared)
    }
}
