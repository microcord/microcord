// Compile-time generator for the hand-tuned cache formats; see CacheFormatProcessor.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(libs.ksp.api)
}
