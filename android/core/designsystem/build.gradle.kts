import java.io.OutputStream
import javax.inject.Inject
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

composeCompiler {
    // Why this file exists, and what is deliberately left out of it, is written
    // at the top of `compose-stability.conf`. Named in every Compose module
    // rather than from the root build: cross-configuring subprojects is what
    // this build has always avoided.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose-stability.conf"),
    )
}

// -- the bundled typeface -----------------------------------------------------
//
// `fonts/google_sans_flex.ttf` is the file as it was downloaded, and it is a
// source rather than a resource: the task below instances it into the
// variant's generated resources on every build, and that copy is what ships.
//
// The saving is the reason. A variable font pays for an axis in `gvar`, which
// holds one set of outline deltas per axis per glyph; at six axes that table
// was 3.41 MB of a 3.81 MB file, against 31 KB of outlines. The app moves two
// of the six, so freezing the other four takes the font to 0.29 MB and the
// release APK from 5.71 MB to 3.60 MB — measured, both ways round, on this
// commit.
//
// This module committed the instanced file for one batch, and that is the
// arrangement this replaces: the obvious way to update a typeface is to
// download it again, the download is the six-axis file, and the saving leaves
// with it without anything failing. What is in the repository is now the thing
// you would download, and the compression is a step of the build.

/** Axis tags left variable; `all` ships the file as it came. See `FontAxisTest`. */
val fontAxes: String = providers.gradleProperty("lessons.font.axes").getOrElse("wght,ROND")

/**
 * The value a frozen axis is frozen at, where that is not the font's default.
 *
 * `ROND` is 0 in the file and 100 everywhere this app asks for it, so freezing
 * it at the default would quietly un-round `GoogleSansFlexRounded`. Unused
 * while `ROND` is kept variable, which it is by default.
 * `FontAxisTest` fails if this map ever disagrees with `Type.kt` and
 * `res/font/google_sans_flex_round.xml`.
 */
val fontAxisPins: Map<String, String> = mapOf("ROND" to "100")

/**
 * Freezes the axes [fontAxes] does not name, by running `fonts/instance.py`.
 *
 * Not an `Exec` task, because the interpreter is not known until it is looked
 * for: a machine without fonttools has to be told the one flag that gets it a
 * build, rather than a stack trace out of a missing module.
 */
@CacheableTask
abstract class InstanceFont : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:Input
    abstract val keptAxes: Property<String>

    @get:Input
    abstract val pinnedAxes: MapProperty<String, String>

    /** A res directory, so the font lands at `font/` inside it and AGP merges it. */
    @get:OutputDirectory
    abstract val resDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun instance() {
        val original = source.get().asFile
        val target = resDirectory.get().dir("font").file(original.name).asFile
        target.parentFile.mkdirs()

        if (keptAxes.get() == KEEP_EVERYTHING) {
            original.copyTo(target, overwrite = true)
            return
        }

        val python = INTERPRETERS.firstOrNull(::carriesFontTools) ?: throw GradleException(
            "Shrinking the bundled typeface needs Python with fonttools, and neither " +
                INTERPRETERS.joinToString(" nor ") { "`$it`" } + " on this machine has " +
                "it. Either install it — `python3 -m pip install fonttools` — or build " +
                "with the font as it came: `-Plessons.font.axes=all`, which is correct " +
                "on every screen and 2.1 MB more APK. Put `lessons.font.axes=all` " +
                "in `~/.gradle/gradle.properties` to stop passing it.",
        )

        exec.exec {
            commandLine(
                buildList {
                    add(python)
                    add(script.get().asFile.absolutePath)
                    add("--source")
                    add(original.absolutePath)
                    add("--output")
                    add(target.absolutePath)
                    add("--keep")
                    add(keptAxes.get())
                    pinnedAxes.get().forEach { (axis, value) ->
                        add("--pin")
                        add("$axis=$value")
                    }
                },
            )
        }
    }

    private fun carriesFontTools(interpreter: String): Boolean = try {
        exec.exec {
            commandLine(interpreter, "-c", "import fontTools")
            isIgnoreExitValue = true
            standardOutput = OutputStream.nullOutputStream()
            errorOutput = OutputStream.nullOutputStream()
        }.exitValue == 0
    } catch (_: Exception) {
        // A missing interpreter throws rather than exiting non-zero.
        false
    }

    private companion object {
        const val KEEP_EVERYTHING = "all"

        /** `python3` first: on a machine with both, `python` is the one that is 2.7. */
        val INTERPRETERS = listOf("python3", "python")
    }
}

/**
 * One task per variant, and the variant API rather than a `res.srcDir`.
 *
 * AGP 9 refuses a `Provider` as a source directory and says why: a static
 * directory carries no task dependency, so the font would be merged before it
 * was written, or not at all. `addGeneratedSourceDirectory` is the supported
 * way and it chooses the output path itself, which is why [InstanceFont.resDirectory]
 * is set here by AGP and read back below for the tests.
 *
 * Both variants instance the same bytes from the same source, so the second is
 * a build-cache hit rather than another five seconds of Python.
 */
androidComponents {
    onVariants { variant ->
        val instance = tasks.register<InstanceFont>(
            "instance${variant.name.replaceFirstChar(Char::titlecase)}Font",
        ) {
            description = "Freezes the variation axes the app never moves out of the bundled font."
            source.set(layout.projectDirectory.file("fonts/google_sans_flex.ttf"))
            script.set(layout.projectDirectory.file("fonts/instance.py"))
            keptAxes.set(fontAxes)
            pinnedAxes.set(fontAxisPins)
        }
        variant.sources.res?.addGeneratedSourceDirectory(instance, InstanceFont::resDirectory)

        // `FontAxisTest` and `FontLicenceTest` read the font that ships, which
        // is built rather than committed. An input rather than a `dependsOn`,
        // so that re-instancing at a different `-Plessons.font.axes` re-runs
        // them; the path is handed over because AGP owns it.
        if (variant.name == "debug") {
            val instanced = instance.flatMap { it.resDirectory }
            tasks.withType<Test>().configureEach {
                inputs.dir(instanced)
                    .withPropertyName("instancedFont")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                systemProperty("lessons.font.axes", fontAxes)
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-Dlessons.font.directory=${instanced.get().asFile.absolutePath}")
                    },
                )
            }
        }
    }
}

android {
    namespace = "com.lumenpearson.lessons.core.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // Robolectric needs the module's own resources on the test classpath; a
        // Compose component that resolves a string or a colour is otherwise
        // being tested against a stub.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Expressive Material 3 is the whole point of this module, so opting in
        // once here beats an @OptIn on every second composable.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    // `api` because every component signature below takes domain types directly:
    // a consumer that can call LessonRow must be able to name a Lesson.
    api(project(":core:model"))

    // Declared twice on purpose: `implementation` pins this module's own
    // compilation, `api` forwards the same constraint to :app and :widget so the
    // whole app resolves one Compose version.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    api(composeBom)

    // Exposed as `api`: these types appear in this module's public signatures
    // (Modifier, Color, ImageVector, TopAppBarScrollBehavior) and consumers
    // build their screens out of the same primitives.
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui.tooling.preview)

    // Only used internally, by the expressive MaterialShapes badge in StateHeroCard.
    implementation(libs.androidx.graphics.shapes)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // The rows in this module are what every tap in the app lands on, and until
    // now nothing could press one without a phone in somebody's hand — which is
    // exactly how a settings screen shipped with six rows that did not respond.
    // Robolectric runs the Compose test harness on the JVM, so that fails a
    // build now instead of a screenshot.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
