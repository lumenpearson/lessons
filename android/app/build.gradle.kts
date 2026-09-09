import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The application module. It owns nothing but glue: the Application object, the
 * single Activity, navigation and the feature screens. Everything durable
 * (models, storage, sync, design tokens, widget) lives in the modules below it,
 * which is what keeps this file short and the build incremental.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Material 3 Expressive is still gated behind opt-in annotations; the app
        // is built against it deliberately, so opt in once here instead of
        // sprinkling @OptIn over every composable.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.text.ExperimentalTextApi",
        )
    }
}

android {
    namespace = "com.lumenpearson.lessons"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumenpearson.lessons"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    androidResources {
        // The app is Russian-first with an English fallback; shipping only these
        // two keeps the APK free of the transitive AndroidX translations.
        localeFilters += listOf("ru", "en")
    }

    buildTypes {
        debug {
            // Suffixed so a debug build can sit next to a release install — the
            // widget is much easier to develop with both present.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is what the "О приложении" section renders.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    // Brought in so the widget's receiver is merged into the app manifest and
    // the widget ships in the same APK; the app never calls into it directly.
    implementation(project(":widget"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    // material3 carries its own version in the catalog on purpose: the BOM would
    // otherwise pin a non-Expressive build.
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // WorkManager is scheduled from Application.onCreate through SyncScheduler.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
