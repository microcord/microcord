package com.discord.widgets.servers.guild_role_subscription

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildscheduledevent.GuildRoleSubscriptionRoleBenefits
import com.discord.widgets.servers.guild_role_subscription.model.CurrentMonthEarningMetrics
import com.discord.widgets.servers.guild_role_subscription.model.TotalEarningMetrics
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerGuildRoleSubscriptionContractsTest {
    @Test
    fun `tier rows preserve order and cap creation at three`() {
        val tiers = (1L..3L).map(::tier)
        val rows = generateServerSettingsGuildRoleSubscriptionTierListItems(tiers.take(2), 3)

        assertEquals(listOf("1", "2", "AddTierItem"), rows.map { it.key })
        assertFalse(
            generateServerSettingsGuildRoleSubscriptionTierListItems(tiers, 3)
                .contains(ServerSettingsGuildRoleSubscriptionTierAdapterItem.AddTier),
        )
    }

    @Test
    fun `exact 126 21 server subscription paths and earning defaults exist`() {
        val paths = listOf(
            "ServerSettingsGuildRoleSubscriptionTierListViewModel.kt",
            "WidgetServerSettingsGuildRoleSubscriptionTierList.kt",
            "edit_tier/WidgetServerSettingsGuildRoleSubscriptionEditTier.kt",
            "upsell/WidgetGuildRoleSubscriptionOnboardingUpsellBottomSheet.kt",
            "payments/WidgetServerSettingsGuildRoleSubscriptionEarnings.kt",
            "model/CurrentMonthEarningMetrics.kt",
            "model/TotalEarningMetrics.kt",
        )
        assertTrue(paths.all { File("src/main/java/com/discord/widgets/servers/guild_role_subscription/$it").isFile })
        assertEquals(
            TotalEarningMetrics(CurrentMonthEarningMetrics(), null),
            TotalEarningMetrics(CurrentMonthEarningMetrics(), null, emptyList()),
        )
    }

    private fun tier(id: Long) = GuildRoleSubscriptionTierListing(
        id = id,
        applicationId = 10,
        name = "Tier $id",
        published = id % 2L == 0L,
        description = null,
        imageAsset = null,
        subscriptionPlans = emptyList(),
        roleBenefits = GuildRoleSubscriptionRoleBenefits(0, emptyList()),
        roleId = id,
    )
}
