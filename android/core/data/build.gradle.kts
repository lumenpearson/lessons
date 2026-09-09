plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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

    // The module holds no resources and no Compose code, so nothing else needs
    // enabling here. BuildConfig stays off: the base URL is a runtime setting,
    // not a build-time constant.
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
    sourceSets {
        getByName("test").assets.srcDirs(files("$projectDir/schemas"))
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
}

kotlin {
    jvmToolchain(21)
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
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
