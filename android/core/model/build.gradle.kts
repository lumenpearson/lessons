plugins {
    alias(libs.plugins.kotlin.jvm)
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
