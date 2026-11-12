package com.lumenpearson.lessons

import com.lumenpearson.lessons.core.data.github.GithubProject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every build property the app reads is passed by the workflow that builds it.
 *
 * A `signingSecret(...)` that nobody sets is not a build failure and not a
 * warning: it is an empty string, and downstream that is a feature switched
 * off. `LESSONS_GITHUB_CLIENT_ID` was read by `app/build.gradle.kts` from the
 * day the GitHub account landed and passed by `apk.yml` on no day at all, so
 * **every APK that workflow ever built had the whole GitHub half of the app
 * missing** — «Войти через GitHub» hidden by `githubConfigured`, filing a bug
 * report from inside the app gone with it, and the corrections pull request
 * unreachable. `LESSONS_CONTACT_EMAIL` was the same story one button along.
 * The first anybody knew was looking on a phone for a row that had never been
 * built.
 *
 * Nothing in the app can catch that — from the app's side an unconfigured
 * feature is indistinguishable from one the builder chose not to switch on,
 * and hiding the row is the deliberate decision (`docs/design.md`, "Signing in
 * through GitHub is for one thing"). What can catch it is the pair of files,
 * read together, which is what this does.
 *
 * It says nothing about whether a secret is *set* — that is the owner's, and
 * the run summary reports it. What it holds is that the wire exists.
 */
class BuildPropertyReachTest {

    @Test
    fun `native checks target release with the tested signing configuration and exact source`() {
        assertTrue(buildScript.contains("providers.gradleProperty(\"lessons.nativeCheck\")"))
        assertTrue(buildScript.contains("testBuildType = if (nativeCheck) \"release\" else \"debug\""))
        assertTrue(buildScript.contains("checkNotNull(androidTest.signingConfig).from(releaseSigning)"))
        assertTrue(workflow.contains("native_check:"))
        assertTrue(workflow.contains("Native checks require master, release and an expected commit"))
        assertTrue(workflow.contains("Native checks require release signing"))
        assertTrue(workflow.contains(":app:assembleReleaseAndroidTest"))
        assertTrue(workflow.contains("-Plessons.nativeCheck=true"))
        val source = File(root, "android/app/src/androidTest/kotlin/com/lumenpearson/lessons/TransportReleaseTest.kt")
        assertTrue("The native harness must be checked in", source.isFile)
    }

    @Test
    fun `the native harness is isolated from the ordinary APK artifact`() {
        assertTrue(workflow.contains("name: lessons-native-check"))
        assertTrue(workflow.contains("for variant in debug release; do"))
        assertFalse(workflow.contains("find app/build/outputs/apk -name '*.apk'"))
    }

    @Test
    fun `only the native target preserves the measured external harness declarations`() {
        val file = File(root, "android/app/native-check-target-proguard-rules.pro")
        assertTrue("The separately optimized test APK needs its target declarations", file.isFile)
        val rules = file.readLines().joinToString("\n") { it.substringBefore('#') }
        val block = Regex(
            """-keep,allowoptimization,allowobfuscation\s+(?:class|interface)\s+([\w.]+)\s*\{([^}]*)}""",
        )
        val blocks = block.findAll(rules).toList()
        assertEquals("No duplicate roots can hide a broader earlier rule", nativeBridgeMembers.size, blocks.size)
        val actual = blocks.associate { match ->
            match.groupValues[1] to match.groupValues[2].split(';')
                .filter(String::isNotBlank).map { it.replace(Regex("\\s+"), "") }.toSet()
        }
        val expected = nativeBridgeMembers.mapValues { (_, members) ->
            members.map { it.replace(Regex("\\s+"), "") }.toSet()
        }
        assertEquals("Keep declarations, never codec or repository implementations", expected, actual)
        val callback = Regex("""-keepclassmembers,allowoptimization\s+class\s+""" +
            """kotlin\.coroutines\.jvm\.internal\.BaseContinuationImpl\s*\{([^}]*)}""")
        val callbacks = callback.findAll(rules).toList()
        assertEquals("One shared coroutine callback ABI", 1, callbacks.size)
        val callbackMembers = callbacks.single().groupValues[1].split(';')
            .filter(String::isNotBlank).map { it.replace(Regex("\\s+"), "") }.toSet()
        assertEquals(nativeCallbackMembers.map { it.replace(Regex("\\s+"), "") }.toSet(), callbackMembers)
        assertTrue("No extra keep or optimizer directives",
            block.replace(callback.replace(rules, ""), "").isBlank())
        val registration = "proguardFiles(\"native-check-target-proguard-rules.pro\")"
        assertEquals("One native-only registration", 1, buildScript.windowed(registration.length).count {
            it == registration
        })
        assertTrue(Regex("""if\s*\(nativeCheck\)\s*\{\s*${Regex.escape(registration)}\s*}""")
            .containsMatchIn(buildScript))
        assertTrue(buildScript.contains("inputs.file(layout.projectDirectory.file(" +
            "\"native-check-target-proguard-rules.pro\"))"))
    }

    @Test
    fun `transport streaming and version reach the application owned graph`() {
        assertTrue(buildScript.contains("signingSecret(\"LESSONS_TRANSPORT\", \"lessons.transport\")"))
        assertTrue(buildScript.contains("signingSecret(\"LESSONS_APP_STREAMING\", \"lessons.streaming\")"))
        assertFalse(buildScript.contains("signingSecret(\"LESSONS_STREAMING\""))
        assertTrue(buildScript.contains("buildConfigField(\"String\", \"TRANSPORT\""))
        assertTrue(buildScript.contains("buildConfigField(\"boolean\", \"APP_STREAMING\""))
        assertTrue(passed.containsAll(listOf("LESSONS_TRANSPORT", "LESSONS_APP_STREAMING")))
        assertTrue(application.contains("GraphOwner"))
        assertTrue(application.contains("transport = BuildConfig.TRANSPORT"))
        assertTrue(application.contains("clientVersion = BuildConfig.VERSION_CODE"))
        assertTrue(application.contains("appStreaming = BuildConfig.APP_STREAMING"))
    }

    @Test
    fun `the walk found both files`() {
        assertTrue("app/build.gradle.kts is empty or missing", buildScript.length > 100)
        assertTrue("apk.yml is empty or missing", workflow.length > 100)
    }

    @Test
    fun `the build script really declares the properties`() {
        assertTrue(
            "This test reads `signingSecret(\"LESSONS_…\")` out of the build " +
                "script, so a rename of that helper turns it into a silent pass. " +
                "Found: $declared",
            declared.size >= 6,
        )
    }

    @Test
    fun `every property the app reads is passed by the APK workflow`() {
        val missing = declared.filterNot { name -> passed.contains(name) }

        assertTrue(
            "Read by app/build.gradle.kts and never passed by " +
                ".github/workflows/apk.yml, so every APK it builds is missing " +
                "whatever this one switches on — silently, because an unset " +
                "property is an empty string and not an error:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /**
     * The repository the app files its reports in, lists its updates from and
     * reads its guide from is the one the workflow ran in (#469).
     *
     * The property was already passed, for the about page's badge; what was
     * missing was the rest of the wire, so a fork's APK named the fork on
     * «О приложении» and offered this repository's releases one row below.
     * Each link is checked: the workflow's value, the build's refusal of a
     * value that is not `owner/name`, and the application handing the result
     * to the graph every GitHub call is built from.
     */
    @Test
    fun `the repository the app talks to is the one the workflow built in`() {
        assertTrue(
            "apk.yml must pass the runner's own repository, so a fork's APK names the fork",
            Regex("""(?m)^\s+LESSONS_BUILD_REPOSITORY: \$\{\{ github\.repository }}\s*$""").containsMatchIn(workflow),
        )
        // CRLF folded first: Git for Windows checks text out that way, and a
        // block cut at a blank line would find none there (#178).
        val script = buildScript.replace("\r\n", "\n")
        val start = script.indexOf("val buildRepository")
        assertTrue("app/build.gradle.kts no longer declares buildRepository", start >= 0)
        val end = script.indexOf("\n\n", start).let { if (it < 0) script.length else it }
        val block = script.substring(start, end)
        assertTrue(
            "a repository that is not owner/name must stop the build, not be spliced into an address: $block",
            "throw GradleException" in block,
        )
        assertTrue(script.contains("""buildConfigField("String", "BUILD_REPOSITORY", "\"${'$'}buildRepository\"")"""))
        assertTrue(
            "LessonsApplication must hand the build's repository to Graph.init, or every GitHub call " +
                "falls back to the upstream repository",
            Regex("""Graph\.init\([^)]*githubProject\s*=\s*BuildProvenance\.current\(\)\.project""")
                .containsMatchIn(application),
        )
    }

    /**
     * The build's refusal is the build's own pattern, run here on the cases
     * that matter, and it agrees with `GithubProject.fromBuild` on every one.
     *
     * Gradle cannot be started from a unit test, so the pattern is read out of
     * the script and compiled. Checking only that a `throw` exists passed just
     * as well for a pattern that let `x/..` through — and that value becomes
     * `/repos/x/..`, which a URL resolver walks up to `/repos/`. The second half
     * is that the two rules cannot drift: a value the build lets through and
     * the app then quietly swaps for the upstream would send a fork's reports
     * here again, with nothing failing anywhere.
     */
    @Test
    fun `the build refuses exactly what the app would not use`() {
        val script = buildScript.replace("\r\n", "\n")
        val declaration = Regex("""val ownerSlashName =\s+Regex\("((?:[^"\\]|\\.)*)"\)""")
        val literal = declaration.find(script)?.groupValues?.get(1)
        assertTrue("app/build.gradle.kts no longer declares ownerSlashName as Regex(\"…\")", literal != null)
        // A Kotlin string literal: the only escape the pattern may need is `\\`.
        val pattern = Regex(checkNotNull(literal).replace("\\\\", "\\"))

        val accepted = listOf(
            "lumenpearson/lessons", "somebody/lessons-fork", "a-b/c_d.e", "x/.github", "x/a.b",
        )
        val refused = listOf(
            "somebody", "a/b/c", "/lessons", "somebody/", "a b/c", "x/y?z", "x/y#z", "x/../y",
            "x/..", "x/.", "a@b/c", "x/%2e%2e", "-a/b", "a-/b", "some/bad\"name",
        )
        accepted.forEach { raw ->
            assertTrue("the build refuses $raw", pattern.matches(raw))
            assertEquals("the app does not use $raw", raw, GithubProject.fromBuild(raw).fullName)
        }
        refused.forEach { raw ->
            assertFalse("the build lets $raw through", pattern.matches(raw))
            assertEquals("the app uses $raw", GithubProject.UPSTREAM, GithubProject.fromBuild(raw))
        }
    }

    private companion object {

        /** `LESSONS_…` as the first argument of `signingSecret`. */
        val NAMES = Regex("""signingSecret\("(LESSONS_[A-Z0-9_]+)"""")

        /** `LESSONS_…:` at the head of a line, which is a YAML key and not prose. */
        val ENV_KEY = Regex("""^(LESSONS_[A-Z0-9_]+):\s""")

        /**
         * The repository root, found by walking up for `settings.gradle.kts`.
         *
         * Gradle runs unit tests from the module directory and an IDE sometimes
         * from the repository root; `ResourceTranslationTest` walks up for the
         * same reason. This one goes one further, to the directory holding
         * `.github`, because the workflow is outside the Gradle tree.
         */
        val root: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    return@lazy directory.parentFile ?: directory
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }

        val buildScript: String by lazy { File(root, "android/app/build.gradle.kts").readText() }

        val workflow: String by lazy { File(root, ".github/workflows/apk.yml").readText() }

        val nativeCallbackMembers = setOf(
            "protected abstract java.lang.Object invokeSuspend(java.lang.Object)",
            "public kotlin.coroutines.Continuation create(java.lang.Object,kotlin.coroutines.Continuation)",
        )

        // These are the separate APK's measured JVM entry points, not its transitive implementations.
        val nativeBridgeMembers = mapOf(
            "androidx.tracing.Trace" to setOf(
                "public static void beginSection(java.lang.String)",
                "public static void endSection()",
                "public static void forceEnableAppTracing()",
            ),
            "com.lumenpearson.lessons.core.data.di.Graph" to setOf(
                "public static final com.lumenpearson.lessons.core.data.di.Graph INSTANCE",
                "public void ensureInitialized(android.content.Context)",
                "public com.lumenpearson.lessons.core.data.di.LessonsContainer getContainer()",
            ),
            "com.lumenpearson.lessons.core.data.di.LessonsContainer" to setOf(
                "public com.lumenpearson.lessons.core.data.repository.SessionRepository getSessionRepository()",
                "public com.lumenpearson.lessons.core.data.repository.SettingsRepository getSettingsRepository()",
            ),
            "com.lumenpearson.lessons.core.data.repository.SessionRepository" to setOf(
                "public java.lang.Object currentAll(kotlin.coroutines.Continuation)",
                "public java.lang.Object join-0E7RQCE(java.lang.String,java.lang.String," +
                    "kotlin.coroutines.Continuation)",
            ),
            "com.lumenpearson.lessons.core.data.repository.SettingsRepository" to setOf(
                "public kotlinx.coroutines.flow.Flow getSettings()",
                "public java.lang.Object update(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation)",
            ),
            "com.lumenpearson.lessons.core.data.repository.AppSettings" to setOf(
                "public java.lang.String getBaseUrl()",
                "public static com.lumenpearson.lessons.core.data.repository.AppSettings copy\$default(...)",
            ),
            "kotlin.Result" to setOf(
                "public static kotlin.Result box-impl(java.lang.Object)",
                "public java.lang.Object unbox-impl()",
            ),
            "kotlin.LazyKt" to emptySet(),
            "kotlin.LazyKt__LazyJVMKt" to setOf(
                "public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0)",
            ),
            "kotlin.ResultKt" to setOf("public static void throwOnFailure(java.lang.Object)"),
            "kotlin.coroutines.intrinsics.IntrinsicsKt" to emptySet(),
            "kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt" to setOf(
                "public static java.lang.Object getCOROUTINE_SUSPENDED()",
            ),
            "kotlin.coroutines.jvm.internal.SpillingKt" to setOf(
                "public static java.lang.Object nullOutSpilledVariable(java.lang.Object)",
            ),
            "kotlinx.coroutines.BuildersKt" to setOf(
                "public static java.lang.Object runBlockingK\$default(kotlin.coroutines.CoroutineContext," +
                    "kotlin.jvm.functions.Function2,int,java.lang.Object)",
                "public static java.lang.Object withContext(kotlin.coroutines.CoroutineContext," +
                    "kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation)",
            ),
            "kotlinx.coroutines.TimeoutKt" to setOf(
                "public static java.lang.Object withTimeout(long,kotlin.jvm.functions.Function2," +
                    "kotlin.coroutines.Continuation)",
            ),
            "kotlinx.coroutines.flow.FlowKt" to setOf(
                "public static java.lang.Object first(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation)",
            ),
        )

        val application: String by lazy {
            File(root, "android/app/src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt").readText()
        }

        /**
         * The names the workflow actually sets, as `NAME:` at the head of a
         * line inside an `env:` block.
         *
         * Searching the file for the bare name passes on a comment mentioning
         * it, and this file's comments name every one of them at length — which
         * is exactly the shape of pass this test exists to refuse. The first
         * version of it did that and had to be caught by breaking the wire on
         * purpose.
         */
        val passed: Set<String> by lazy {
            workflow.lineSequence()
                .mapNotNull { ENV_KEY.find(it.trim())?.groupValues?.get(1) }
                .toSet()
        }

        val declared: List<String> by lazy {
            NAMES.findAll(buildScript).map { it.groupValues[1] }.distinct().sorted().toList()
        }
    }
}
