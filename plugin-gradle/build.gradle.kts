plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.mantikafasi.manticord"
version = "1.0.0"

gradlePlugin {
    plugins {
        create("manticordPlugin") {
            id = "dev.mantikafasi.manticord.plugin"
            implementationClass = "dev.mantikafasi.manticord.gradle.ManticordPluginGradlePlugin"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.2.1")
}

publishing {
    repositories {
        maven {
            name = "GitHubEnterprise"
            url = uri("https://maven.git.hoodnet.work/discord-android-rebuild/nullcord")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
