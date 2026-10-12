plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.mantikafasi.manticord.plugins.api"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Shipped inside the app, so it must stay within minSdk too; see app/build.gradle.kts.
    lint {
        checkOnly += setOf("NewApi")
        fatal += "NewApi"
    }
}

kotlin {
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    api(libs.rxjava)
    testImplementation(libs.junit)
}
