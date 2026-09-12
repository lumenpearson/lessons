import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The application module. It owns nothing but glue: the Application object, the
 * single Activity, navigation and the feature screens. Everything durable
 * (models, storage, sync, design tokens, widget) lives in the modules below it,
 * which is what keeps this file short and the build incremental.
 */
plugins {
    alias(libs.plugins.android.application)
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

/*
 * Release signing.
 *
 * Credentials are read from environment variables (what CI sets) or Gradle
 * properties (what `~/.gradle/gradle.properties` holds locally). Both are
 * configuration-cache-tracked providers, and neither lives in the repository,
 * so there is no keystore or password to leak in a commit.
 *
 * When nothing is configured the release build is signed with the debug key
 * instead of being left unsigned. An unsigned APK cannot be installed at all,
 * which makes "build me an APK" fail for the common case of a fresh clone with
 * no secrets set up. A debug-signed release is installable and still gets the
 * real R8 treatment; it simply must not be published, which the build warns
 * about and the workflow repeats in its summary.
 */
fun signingSecret(env: String, property: String): String? =
    (providers.environmentVariable(env).orNull ?: providers.gradleProperty(property).orNull)
        ?.takeIf { it.isNotBlank() }

val keystorePath = signingSecret("LESSONS_KEYSTORE_FILE", "lessons.keystore.file")
val keystorePassword = signingSecret("LESSONS_KEYSTORE_PASSWORD", "lessons.keystore.password")
val keystoreKeyAlias = signingSecret("LESSONS_KEY_ALIAS", "lessons.key.alias")
val keystoreKeyPassword = signingSecret("LESSONS_KEY_PASSWORD", "lessons.key.password")

val hasReleaseSigning: Boolean =
    keystorePath != null &&
        keystorePassword != null &&
        keystoreKeyAlias != null &&
        keystoreKeyPassword != null &&
        File(keystorePath).exists()

// Version identity can be overridden per build so a tagged release APK is
// distinguishable from a nightly one. Both fall back to the values below.
val appVersionName = signingSecret("LESSONS_VERSION_NAME", "lessons.versionName") ?: "0.1.0"
val appVersionCode = signingSecret("LESSONS_VERSION_CODE", "lessons.versionCode")?.toIntOrNull() ?: 1

// The GitHub OAuth App the "sign in with GitHub" row talks to. Whoever builds
// the app registers one (Settings → Developer settings → OAuth Apps, with
// "Enable Device Flow" ticked) and passes its client id here. There is no
// secret: the device flow does not use one, and an APK could not keep it
// anyway. Empty means the row does not appear.
val githubClientId = signingSecret("LESSONS_GITHUB_CLIENT_ID", "lessons.github.clientId") ?: ""

// Where "отправить письмом" on the bug-report sheet goes. Not in the source
// for the same reason the keystore is not: an address in a public repository
// is an address on every spam list, and it is the builder's to give. Empty
// hides the button.
val contactEmail = signingSecret("LESSONS_CONTACT_EMAIL", "lessons.contactEmail") ?: ""

android {
    namespace = "com.lumenpearson.lessons"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumenpearson.lessons"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "CONTACT_EMAIL", "\"$contactEmail\"")
    }

    androidResources {
        // The app is Russian-first with an English fallback; shipping only these
        // two keeps the APK free of the transitive AndroidX translations.
        localeFilters += listOf("ru", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
                // Both schemes: v1 for API 26-27 devices, v2+ for everything since.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release keystore configured; signing the release build with the " +
                        "debug key so the APK is installable. Do not publish this artifact - " +
                        "see docs/build.md to set up real signing.",
                )
                signingConfigs.getByName("debug")
            }
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
    // WorkManager is scheduled from Application.onCreate through SyncScheduler.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // The management view model is a coroutine state holder: its tests need a
    // main dispatcher and a scheduler they can step, which is all this is for.
    testImplementation(libs.kotlinx.coroutines.test)
}
