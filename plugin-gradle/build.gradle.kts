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
    compileOnly(libs.android.gradle.plugin)
}

publishing {
    repositories {
        maven {
            name = "GitHubEnterprise"
            url = uri("https://maven.git.hoodnet.work/microcord/microcord")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
