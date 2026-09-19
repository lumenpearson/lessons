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

android {
    namespace = "com.lumenpearson.lessons.widget"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // No targetSdk here on purpose: AGP takes it from the consuming :app
        // module, and declaring it in a library only ever drifts out of sync.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // Glance is Compose, so the compiler plugin has to run over this module
        // even though nothing here is a `androidx.compose.ui` composable.
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // Nothing under src/test touches the framework — the tick cadence and the
        // day-naming helper are deliberately pure — but default-returning stubs
        // keep an accidental android.* touch from failing with a confusing
        // "not mocked" error instead of an assertion.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Toolchain comes from AGP; compileOptions pins 21.
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    // The BOM only aligns versions; Glance pulls the individual artifacts.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.graphics)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.core.ktx)
    // WorkManager is what :core:data's SyncScheduler enqueues into; the widget
    // never builds a Worker itself, but it must resolve the API on the classpath
    // when it asks for a sync from onEnabled/onUpdate.
    implementation(libs.androidx.work.runtime.ktx)
    // Flow.first() when reading AppSettings before provideContent.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
