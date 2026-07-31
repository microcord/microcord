package com.discord.widgets.guild_role_subscriptions

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.widgets.guild_role_subscriptions.tier.GuildRoleSubscriptionTierAdapterItem
import com.discord.widgets.guild_role_subscriptions.tier.generateGuildRoleSubscriptionReviewListItems
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.GuildRoleSubscriptionBenefitAdapterItem
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.GuildRoleSubscriptionTierBenefitListType
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.generateGuildRoleSubscriptionBenefitListItems
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildRoleSubscriptionExactPathContractsTest {
    @Test
    fun exactGeneratorPathsAndOrderingMatch12621() {
        val paths = listOf(
            "widgets/guild_role_subscriptions/tier/GuildRoleSubscriptionTierListItemGeneratorKt.kt",
            "widgets/guild_role_subscriptions/tier/create/benefits/GuildRoleSubscriptionBenefitListItemGeneratorKt.kt",
        )
        assertEquals(paths, paths.filter { File("src/main/java/com/discord/$it").isFile })

        assertEquals(
            listOf(GuildRoleSubscriptionTierAdapterItem.PlanDetails("description", "cover")),
            generateGuildRoleSubscriptionReviewListItems(null, null, planDescription = "description", planCoverImage = "cover"),
        )
        assertEquals(
            listOf(
                GuildRoleSubscriptionBenefitAdapterItem.Header(GuildRoleSubscriptionBenefitType.INTANGIBLE),
                GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem(
                    GuildRoleSubscriptionBenefitType.INTANGIBLE,
                    7L,
                    "Tier",
                ),
            ),
            generateGuildRoleSubscriptionBenefitListItems(
                emptyList(),
                emptyList(),
                GuildRoleSubscriptionTierBenefitListType.INTANGIBLE,
                7L,
                "Tier",
                false,
                false,
                null,
                null,
            ),
        )
    }
}
