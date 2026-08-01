package com.discord.release

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProguardRulesContractTest {
    @Test
    fun `release rules preserve native and reflective contracts`() {
        val rules = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .map { File(it, "app/proguard-rules.pro") to File(it, "proguard-rules.pro") }
            .flatMap { (fromRoot, fromModule) -> sequenceOf(fromModule, fromRoot) }
            .first { it.isFile }
            .readText()

        listOf(
            "-keep class com.hammerandchisel.libdiscord.** { *; }",
            "-keep class co.discord.media_engine.** { *; }",
            "-keep class org.webrtc.** { *; }",
            "native <methods>;",
            "class com.discord.api.** { <fields>; }",
            "class com.discord.models.** { <fields>; }",
            "class com.discord.restapi.RestAPIParams$** { <fields>; }",
        ).forEach { rule -> assertTrue("Missing release rule: $rule", rule in rules) }
    }
}
