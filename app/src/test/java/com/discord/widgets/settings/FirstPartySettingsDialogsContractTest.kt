package com.discord.widgets.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartySettingsDialogsContractTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun `owned screens contain no platform dialogs`() {
        OWNED_SCREENS.map(::source).forEach { contents ->
            assertFalse(contents.contains("AlertDialog"))
            assertFalse(contents.contains("MaterialAlertDialogBuilder"))
        }
    }

    @Test
    fun `recovered dialog layouts have first party owners and result contracts`() {
        listOf(
            "widgets/settings/SettingsPrivacyDialogs.kt",
            "widgets/settings/premium/ChoosePlanDialogs.kt",
            "widgets/settings/premium/WidgetGiftAcceptDialog.kt",
            "widgets/settings/guildboost/SettingsGuildBoostDialogs.kt",
            "widgets/channels/settings/ThreadDeleteDialog.kt",
            "widgets/servers/RoleColorPickerDialog.kt",
        ).map(::source).forEach { assertTrue(it.contains(": AppDialog(")) }

        assertTrue(source("widgets/settings/billing/WidgetPaymentSourceEditDialog.kt").contains("SelectorDialog.show("))
        assertTrue(source("widgets/channels/permissions/WidgetChannelSettingsEditPermissions.kt").contains("DiscardChangesDialog.show("))
        assertTrue(source("widgets/settings/premium/WidgetSettingsGifting.kt").contains("WidgetGiftAcceptDialog.show("))
        assertTrue(source("widgets/settings/guildboost/WidgetSettingsGuildBoost.kt").contains("GuildBoostCancelDialog.show("))
    }

    private fun source(path: String) = File(root, "app/src/main/java/com/discord/$path").readText()

    private companion object {
        val OWNED_SCREENS = listOf(
            "widgets/settings/WidgetSettingsPrivacy.kt",
            "widgets/settings/premium/WidgetSettingsGifting.kt",
            "widgets/settings/premium/WidgetChoosePlan.kt",
            "widgets/settings/guildboost/WidgetSettingsGuildBoost.kt",
            "widgets/settings/billing/WidgetPaymentSourceEditDialog.kt",
            "widgets/channels/settings/WidgetThreadSettings.kt",
            "widgets/channels/permissions/WidgetChannelSettingsEditPermissions.kt",
            "widgets/servers/RoleColorPickerDialog.kt",
        )
    }
}
