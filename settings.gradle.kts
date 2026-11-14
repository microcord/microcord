pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack no longer serves RealtimeBlurView 82df3520b4 (v1.2.1), and a clean machine cannot
        // build without it; this is the copy of that build, Apache 2.0.
        maven(file("third_party/maven")) {
            content {
                includeGroup("com.github.mmin18")
            }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.lytefast")
                includeGroup("com.github.yalantis")
                includeGroup("com.github.discord")
                includeGroup("com.github.franmontiel")
                includeGroup("com.github.CameraKit")
            }
        }
    }
}

rootProject.name = "Discord"
include(":app")
include(":baselineprofile")
include(":plugin-api")
include(":plugin-gradle")
include(":cache-codegen")
