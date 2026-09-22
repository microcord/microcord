import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonOutput
import java.security.MessageDigest

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
val headCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "--verify", "HEAD")
}.standardOutput.asText.get().trim().lowercase()
val commitHash = providers.environmentVariable("MANTICORD_COMMIT_SHA").orElse(headCommit).get().lowercase()
require(Regex("[0-9a-f]{40}").matches(commitHash) && commitHash == headCommit) {
    "MANTICORD_COMMIT_SHA must be the full SHA of the checked-out HEAD"
}
val commitTimestamp = providers.exec {
    workingDir(rootDir)
    commandLine("git", "show", "--no-patch", "--format=%ct", commitHash)
}.standardOutput.asText.get().trim().toLong()
// Commit time, not CI run order or wall clock, keeps local and CI APKs on the same scale.
val updateVersionCode = (commitTimestamp - 1_577_836_800L).also {
    require(it in 1L..Int.MAX_VALUE.toLong()) { "Commit timestamp is outside the Android versionCode range" }
}.toInt()

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
        versionCode = updateVersionCode
        versionName = "1.0"
        resValue("string", "manticord_build_label", "Dev ${buildId.get()} ${commitHash.take(8)}")
        buildConfigField("String", "UPDATE_COMMIT_SHA", JsonOutput.toJson(commitHash))
        buildConfigField("long", "UPDATE_COMMIT_TIMESTAMP", "${commitTimestamp}L")
        buildConfigField("String", "UPDATE_BUILD_ID", JsonOutput.toJson(buildId.get()))

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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        val manifestFile = layout.buildDirectory.file("outputs/update/${variant.name}/update-manifest.json")
        val sourceSha = commitHash
        val sourceTimestamp = commitTimestamp
        val expectedVersionCode = updateVersionCode
        tasks.register("generateReleaseUpdateManifest") {
            group = "build"
            description = "Generate updater metadata for the exact signed release APK"
            dependsOn("assembleRelease")
            inputs.dir(apkDirectory)
            inputs.property("commitSha", sourceSha)
            inputs.property("commitTimestamp", sourceTimestamp)
            inputs.property("versionCode", expectedVersionCode)
            outputs.file(manifestFile)
            doLast {
                val directory = apkDirectory.get().asFile.canonicalFile
                val artifacts = requireNotNull(artifactsLoader.load(apkDirectory.get())) {
                    "Release APK output metadata is missing"
                }
                val output = artifacts.elements.single()
                val apk = File(output.outputFile).canonicalFile
                require(apk.parentFile == directory && apk.name == "app-release.apk" && apk.isFile) {
                    "Expected exactly one produced app-release.apk"
                }
                require(artifacts.applicationId == "dev.mantikafasi.manticord" &&
                    output.versionCode == expectedVersionCode && !output.versionName.isNullOrBlank()) {
                    "Release APK metadata does not match this build"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var count = input.read(buffer)
                    while (count != -1) {
                        digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                val manifest = linkedMapOf(
                    "commitSha" to sourceSha,
                    "commitTimestamp" to sourceTimestamp,
                    "versionCode" to output.versionCode,
                    "versionName" to output.versionName,
                    "sha256" to digest.digest().joinToString("") { "%02x".format(it) },
                    "size" to apk.length(),
                    "packageName" to artifacts.applicationId,
                )
                manifestFile.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(JsonOutput.toJson(manifest) + "\n")
                }
            }
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
    implementation(libs.fresco.animated.base)
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
    implementation(libs.androidndkgif)
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


