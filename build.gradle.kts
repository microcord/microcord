// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    `maven-publish`
}

val pluginSdkJar by tasks.registering(Jar::class) {
    dependsOn(":app:bundleDebugClassesToCompileJar", ":app:processDebugResources", ":plugin-api:bundleLibCompileToJarDebug")
    archiveBaseName.set("manticord-plugin-api")
    archiveVersion.set("1.0.0")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-sdk"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ zipTree(file("app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar")) })
    from({ zipTree(file("app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar")) })
    from({ zipTree(file("plugin-api/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar")) })
}

publishing {
    publications {
        create<MavenPublication>("pluginSdk") {
            groupId = "dev.mantikafasi.manticord"
            artifactId = "plugin-api"
            version = "1.0.0"
            artifact(pluginSdkJar)
            pom.withXml {
                asNode().appendNode("dependencies").appendNode("dependency").apply {
                    appendNode("groupId", "io.reactivex")
                    appendNode("artifactId", "rxjava")
                    appendNode("version", "1.3.8")
                    appendNode("scope", "compile")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubEnterprise"
            url = uri("https://git.hoodnet.work/_registry/maven/discord-android-rebuild/nullcord")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
