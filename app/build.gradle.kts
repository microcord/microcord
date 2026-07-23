plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.discord"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.exoplayer.ui)
    implementation(libs.cameraview)
    implementation(libs.colorpicker)
    implementation(libs.flexbox)
    implementation(libs.flexinput) {
        exclude(group = "com.camerakit", module = "camerakit")
        exclude(group = "com.camerakit", module = "jpegkit")
    }
    implementation(libs.fresco)
    implementation(libs.fresco.drawee.span)
    implementation(libs.lottie)
    implementation(libs.material)
    implementation(libs.rxjava)
    implementation(libs.simpleast)
    implementation(libs.ucrop)
    implementation(libs.zxing.scanner) {
        exclude(group = "com.android.support")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
