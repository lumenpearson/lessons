import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
}
