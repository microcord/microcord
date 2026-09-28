import java.util.Properties

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.mantikafasi.manticord.baselineprofile"
    compileSdk {
        version = release(37)
    }
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The profiled build has its own package (see app's androidComponents) so it never
        // replaces the installed app.
        testInstrumentationRunnerArguments["targetAppId"] = "dev.mantikafasi.manticord.benchmark"
        // Optional: lets the generator log in so the profile covers the authenticated startup. Never
        // commit it; set `profileToken` in local.properties or the ORG_GRADLE_PROJECT_profileToken
        // environment variable.
        (providers.gradleProperty("profileToken").orNull ?: localProperty("profileToken"))
            ?.takeIf(String::isNotBlank)
            ?.let { testInstrumentationRunnerArguments["profileToken"] = it }
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}

fun localProperty(name: String): String? = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty(name) }
