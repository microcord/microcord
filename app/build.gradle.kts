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
    bundle {
        language.enableSplit = false
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
    testOptions {
        // Android framework stubs return defaults instead of throwing, so plain JUnit tests can
        // construct framework types (e.g. View) when only the surrounding Kotlin logic is asserted.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.apng)
    implementation(libs.billing)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime)
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)
    implementation(libs.camerakit)
    implementation(libs.cameraview)
    implementation(libs.colorpicker)
    implementation(libs.flexbox)
    implementation(libs.flexinput) {
        exclude(group = "com.camerakit", module = "camerakit")
        exclude(group = "com.camerakit", module = "jpegkit")
    }
    implementation(libs.fresco)
    implementation(libs.fresco.drawee.span)
    implementation(libs.photo.drawee.view)
    implementation(libs.gson)
    implementation(libs.kronos.android)
    implementation(libs.kryo)
    implementation(libs.lottie)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.persistent.cookie.jar)
    implementation(libs.play.services.ads.identifier)
    implementation(libs.retrofit)
    implementation(libs.retrofit.adapter.rxjava)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.realtime.blur.view)
    implementation(libs.simpleast)
    implementation(libs.ucrop)
    implementation(files("../third_party/webrtc-discord-126.21/webrtc-discord-126.21.jar"))
    implementation(libs.zxing.scanner) {
        exclude(group = "com.android.support")
    }
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
