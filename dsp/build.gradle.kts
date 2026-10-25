// DeanWave DSP module: pure JVM Kotlin, no Android dependencies.
// That is deliberate — the sound engine stays unit-testable
// and independent of any playback source.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
