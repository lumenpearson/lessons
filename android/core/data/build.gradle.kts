plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
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

/** A folder of the repository outside `android/`, as the path `directories` takes. */
fun inRepository(path: String): String =
    rootProject.layout.projectDirectory.dir("../$path").asFile.path

android {
    namespace = "com.lumenpearson.lessons.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // No Compose code here, so nothing else needs enabling. There *are*
    // resources — `values/strings.xml`, its `values-en/` twin and one drawable,
    // for the notifications this module posts — which is why
    // `ResourceTranslationTest` covers this module too: a string added here
    // without its English twin is one Russian line in an English app, and
    // nothing logs it.
    //
    // BuildConfig stays off: the base URL is a runtime setting, not a
    // build-time constant.
    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Room writes the schema JSON here on every build. It is committed so that a
    // future v1 -> v2 migration can be diffed and tested against a real v1 file
    // instead of being written blind.
    // fallback: if AGP ever drops the `test` android source set, keeping only the
    // `androidTest` line below is enough for Room's MigrationTestHelper.
    //
    // Every folder below is added through `directories`, a set of paths, rather
    // than `srcDirs(...)`, which AGP 9.4 deprecates (#185); both add to the
    // defaults rather than replace them.
    sourceSets {
        getByName("test").assets.directories += "$projectDir/schemas"
        getByName("androidTest").assets.directories += "$projectDir/schemas"

        // The guide the app draws, shipped in the APK as it is written in the
        // repository — `docs/app/` itself is the asset folder, rather than a
        // copy of it kept in sync by hand or by a task. There is exactly one
        // copy of that text, which is the point: the app fetches these same
        // files from GitHub, and a bundled copy that had drifted from them
        // would show a reader a guide nobody wrote.
        getByName("main").assets.directories += inRepository("docs/app")

        // The region catalog, bundled as the server's generator wrote it — the
        // same one file the server reads, never a copy, because the phone takes
        // every host it will ever send a password to from this file and a
        // second allow-list is a second answer to «may I talk to this host».
        getByName("main").assets.directories += inRepository("server/app/catalog/data")

        // The known answers both sign-in implementations are held to, read in
        // place from the server's tests so there is one file and not two that
        // agree until somebody edits one. The catalog rides along so a JVM test
        // can load it from the classpath the way the app loads it from assets.
        getByName("test").resources.directories += listOf(
            inRepository("server/tests/vectors"),
            inRepository("server/app/catalog/data"),
        )
    }
}

kotlin {
    // No jvmToolchain() here: with AGP's built-in Kotlin the toolchain comes from
    // the Android extension, and compileOptions above already pins 21.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // `api`, not `implementation`: every repository signature in this module is
    // expressed in :core:model types, so consumers need them transitively.
    api(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // WorkManager types leak into the public API only through SyncScheduler /
    // SyncWorker, which callers reference by name, so `implementation` is enough.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    // No `logging-interceptor`, deliberately. It was on this list and
    // `HttpLoggingInterceptor` appears nowhere in the tree — a dependency
    // carried for a reason nobody wrote down — so the APK shipped it for
    // nothing. The reason not to put it back here is that its job is to print
    // request and response bodies, `Authorization: Bearer …` with them, and a
    // line added to this file applies to the release build as much as to the
    // debug one. A debug-only `debugImplementation` is the way to ask for it.
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real socket for the provider sign-ins: the ports are held to what an
    // HTTP exchange actually carries (form bodies, cookies, redirects not
    // followed), which a scripted interceptor would only restate.
    testImplementation(libs.okhttp.mockwebserver3)
}
