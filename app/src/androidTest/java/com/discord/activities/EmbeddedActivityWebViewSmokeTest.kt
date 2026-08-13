package com.discord.activities

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityConfig
import com.discord.api.embeddedactivities.EmbeddedActivityDiscordConfig
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.embeddedactivities.EmbeddedActivityLocation
import com.discord.api.embeddedactivities.EmbeddedActivityPlatformConfig
import com.discord.api.embeddedactivities.EmbeddedActivityShelfApplication
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedActivityWebViewSmokeTest {
    @Test
    fun webViewStartsHardenedWithoutNetwork() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val webView = EmbeddedActivityWebView(
                ApplicationProvider.getApplicationContext(),
                session(),
                onExternalUrl = { _, result -> result(false) },
                onPermissionRequest = { it.deny() },
                onAuthorizeRequest = { _, _, deny -> deny() },
                onCrash = {},
                onLoadError = {},
            )
            assertTrue(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(WebView.OVER_SCROLL_NEVER, webView.overScrollMode)
            webView.loadDataWithBaseURL(
                "https://discord.com/",
                "<html><body>offline smoke</body></html>",
                "text/html",
                "utf-8",
                null,
            )
            webView.destroySafely()
        }
    }

    private fun session(): EmbeddedActivitySession {
        val config = EmbeddedActivityConfig(
            applicationId = 12,
            activityPreviewVideoAssetId = null,
            supportedPlatforms = listOf("android"),
            defaultOrientationLockState = 1,
            tabletDefaultOrientationLockState = 1,
            requiresAgeGate = false,
            legacyResponsiveAspectRatio = false,
            premiumTierRequirement = null,
            freePeriodStartsAt = null,
            freePeriodEndsAt = null,
            clientPlatformConfig = mapOf("android" to EmbeddedActivityPlatformConfig(0, releasePhase = "global_launch")),
            shelfRank = 1,
            hasCspException = false,
            displaysAdvertisements = false,
        )
        val instance = EmbeddedActivityInstance(
            12,
            "i-34-gc-1-2",
            34,
            35,
            EmbeddedActivityLocation("gc-1-2", "gc", 2, 1),
            emptyList(),
        )
        val channel = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create().fromJson("{\"id\":2,\"guild_id\":1,\"type\":2,\"name\":\"Voice\"}", Channel::class.java)
        return EmbeddedActivitySession(
            EmbeddedActivityShelfApplication(12, "Activity"),
            config,
            instance,
            channel,
            EmbeddedActivityDiscordConfig("//discord.com/api", "production", "cdn.discordapp.com"),
            "redacted",
            "https://12.discordsays.com/?frame_id=smoke",
            "smoke",
        )
    }
}
