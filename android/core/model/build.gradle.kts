import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

abstract class DetektBaselineInventory : ValueSource<List<String>, DetektBaselineInventory.Parameters> {
    interface Parameters : ValueSourceParameters {
        val rootDirectory: DirectoryProperty
    }

    override fun obtain(): List<String> {
        val root = parameters.rootDirectory.get().asFile.toPath().toRealPath()
        val entries = mutableListOf<String>()
        val names = setOf("detekt-baseline.xml", "detekt-baseline-budget.xml")
        fun relative(path: Path) = root.relativize(path).joinToString("/")
        // Gradle's file tree follows directory links; Windows junctions are
        // isOther directories even though isSymbolicLink is false.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (path.fileName.toString() in setOf("build", ".gradle")) return FileVisitResult.SKIP_SUBTREE
                if (attributes.isSymbolicLink || attributes.isOther) {
                    entries += "link-directory:${relative(path)}"
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (path.fileName.toString() in names) {
                    entries += "rejected-directory:${relative(path)}"
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                when {
                    attributes.isSymbolicLink -> entries += "link:${relative(path)}"
                    path.fileName.toString() !in names -> Unit
                    attributes.isRegularFile && !attributes.isOther && path.toRealPath().startsWith(root) ->
                        entries += "regular:${relative(path)}"
                    else -> entries += "rejected-other:${relative(path)}"
                }
                return FileVisitResult.CONTINUE
            }
        })
        return entries.sorted()
    }
}

detekt {
    // What is switched off, and why so little, is written at the top of the
    // config; today's findings are this module's `detekt-baseline.xml`. The
    // config is named although it sits where the plugin looks by default,
    // because the default falls back to detekt's own rules in silence if the
    // file ever moves. Named in every module rather than from the root build:
    // cross-configuring subprojects is what this build has always avoided.
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

// Pure JVM on purpose: the schedule engine is the most logic-dense part of the
// app and this keeps its test suite running in milliseconds with no emulator.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

// `StabilityPromiseTest` reads this module's Kotlin as text, and Gradle otherwise
// hashes only the classes compiled from it, which not every edit changes (#427).
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/kotlin"))
        .withPropertyName("mainSourcesAsText")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val recordRoot = rootProject.layout.projectDirectory
    // Reading a ValueSource during configuration makes its returned metadata a
    // configuration-cache input, rechecked on every build without reading targets.
    val records = providers.of(DetektBaselineInventory::class) {
        parameters.rootDirectory.set(recordRoot)
    }.get()
    inputs.property("detektBaselineInventory", records)
    inputs.files(records.filter { it.startsWith("regular:") }.map {
        recordRoot.file(it.removePrefix("regular:")).asFile
    })
        .withPropertyName("detektBaselineRecords")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("testInputDeclaration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
}

dependencies {
    testImplementation(libs.junit)
}
