package com.discord.dialogs

import com.discord.utilities.premium.BlockRussianPurchasesUtils
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogEntryPointReachabilityTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun `reconstructed dialogs have production entry points`() {
        assertWired(
            "com/discord/widgets/accessibility/AccessibilityDetectionNavigator.kt",
            "WidgetAccessibilityDetectionDialog.show(activity.supportFragmentManager)",
            "persist = true",
        )
        assertWired(
            "com/discord/widgets/home/WidgetHome.kt",
            "WidgetNoticeNuxSamsungLink.enqueue(requireContext())",
            "AccessibilityDetectionNavigator.enqueueNoticeWhenEnabled(this)",
        )
        assertWired(
            "com/discord/widgets/user/usersheet/WidgetUserSheet.kt",
            "UserActionsDialog.show(",
        )
        assertWired(
            "com/discord/widgets/voice/sheet/WidgetVoiceBottomSheet.kt",
            "AudioOutputSelectionDialog.show(parentFragmentManager)",
            "CameraCapacityDialog.show(parentFragmentManager, event.maxVideoChannelUsers)",
            "GuildVideoAtCapacityDialog.show(parentFragmentManager)",
        )
        assertWired(
            "com/discord/widgets/voice/fullscreen/WidgetCallFullscreen.kt",
            "AudioOutputSelectionDialog.show(parentFragmentManager)",
            "CameraCapacityDialog.show(parentFragmentManager, event.guildMaxVideoChannelUsers)",
            "GuildVideoAtCapacityDialog.show(parentFragmentManager)",
        )
        assertWired(
            "com/discord/widgets/voice/fullscreen/WidgetCallPreviewFullscreen.kt",
            "GuildVideoAtCapacityDialog.show(parentFragmentManager)",
        )
        assertWired(
            "com/discord/widgets/voice/fullscreen/WidgetGuildCallOnboardingSheet.kt",
            "GuildVideoAtCapacityDialog.show(parentFragmentManager)",
        )
        assertWired(
            "com/discord/widgets/notice/WidgetNoticeNuxOverlay.kt",
            "R.id.settings_voice_overlay_toggle",
            "isTargetAutoToggle = true",
            "persist = true",
        )
        assertWired(
            "com/discord/widgets/notice/WidgetNoticeNuxSamsungLink.kt",
            "persist = true",
            "sinceLastPeriodMs = Long.MAX_VALUE",
        )
        assertWired(
            "com/discord/widgets/settings/WidgetSettingsVoice.kt",
            "ARG_TARGET_RES_ID",
            "smoothScrollTo(0, target.top)",
            "overlayToggle.setChecked(true, true)",
        )
    }

    @Test
    fun `russian purchase experiment gates every reconstructed premium launcher`() {
        assertFalse(BlockRussianPurchasesUtils.isBlockedBucket(null))
        assertFalse(BlockRussianPurchasesUtils.isBlockedBucket(0))
        assertTrue(BlockRussianPurchasesUtils.isBlockedBucket(1))
        assertWired(
            "com/discord/utilities/premium/BlockRussianPurchasesUtils.kt",
            "autoMarkSeen = true",
            "WidgetBlockRussianPurchasesDialog.show(activity.supportFragmentManager)",
        )
        listOf(
            "com/discord/widgets/settings/premium/WidgetSettingsPremium.kt",
            "com/discord/widgets/settings/premium/WidgetSettingsGifting.kt",
            "com/discord/widgets/settings/premium/WidgetChoosePlan.kt",
        ).forEach { assertWired(it, "BlockRussianPurchasesUtils.showDialog()") }
    }

    private fun assertWired(path: String, vararg snippets: String) {
        val source = File(root, "app/src/main/java/$path").readText()
        snippets.forEach { assertTrue("$path must contain $it", source.contains(it)) }
    }
}
