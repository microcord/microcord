plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    // Applied below only when a Firebase config is present, so a checkout without one builds.
    alias(libs.plugins.google.services) apply false
}

val firebaseConfig = file("google-services.json")
if (firebaseConfig.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
} else {
    logger.lifecycle("google-services.json missing: push notifications will be inert in this build")
}

val buildId = providers.environmentVariable("MANTICORD_BUILD_ID").orElse("local")
val commitHash = providers.environmentVariable("MANTICORD_COMMIT_SHA").orElse(
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
    }.standardOutput.asText.map { it.trim() }
)

android {
    namespace = "com.discord"
    // The engine opens the Krisp models with openFd, which only works on stored entries.
    androidResources {
        noCompress += "kw"
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        resValue("string", "manticord_build_label", "Dev ${buildId.get()} ${commitHash.get().take(8)}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("manticord") {
            // Public development identity: consistent installs, not production trust.
            storeFile = file("manticord-dev.keystore")
            storePassword = "manticord"
            keyAlias = "manticord"
            keyPassword = "manticord"
        }
    }

    sourceSets {
        // Checked-in protoc output: no protobuf Gradle plugin supports this AGP yet, so the
        // generated Java is vendored alongside the .proto it came from. See src/main/proto.
        getByName("main").java.directories.add("src/main/generated-proto")
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/*.so"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("manticord")
        }
        release {
            lint {
                checkReleaseBuilds = false
            }
            signingConfig = signingConfigs.getByName("manticord")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        resValues = true
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
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    splits {
        abi {
            isEnable = false
        }
    }
}

dependencies {
    implementation(project(":plugin-api"))
    compileOnly(libs.infer.annotation)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.apng)
    implementation(libs.photoeditor)
    implementation(libs.androidsvg)
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
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
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
    implementation(libs.fresco.vito.source)
    implementation(libs.fresco.vito.options)
    implementation(libs.fresco.vito.core)
    implementation(libs.fresco.vito.core.java.impl)
    implementation(libs.fresco.vito.provider)
    implementation(libs.fresco.vito.view)
    compileOnly(libs.fresco.middleware)
    implementation(libs.fresco.animated.gif)
    implementation(libs.fresco.animated.webp)
    implementation(libs.firebase.messaging)
    implementation(libs.protobuf.java)
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
    implementation(files("../third_party/webrtc-discord-333.5/webrtc-discord-333.5.aar"))
    implementation(libs.zxing.scanner) {
        exclude(group = "com.android.support")
    }
    implementation(variantOf(libs.zstd) { artifactType("aar") })
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestUtil(libs.androidx.test.orchestrator)
    baselineProfile(project(":baselineprofile"))
    //debugImplementation(libs.leakcanary)
}


