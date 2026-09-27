plugins {
    alias(libs.plugins.kotlin.jvm)
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

// Pure JVM on purpose: the schedule engine is the most logic-dense part of the
// app and this keeps its test suite running in milliseconds with no emulator.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
