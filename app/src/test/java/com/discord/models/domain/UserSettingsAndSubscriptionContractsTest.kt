package com.discord.models.domain

import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.domain.billing.ModelBillingAddress
import com.discord.models.domain.premium.SubscriptionPlanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsAndSubscriptionContractsTest {
    @Test
    fun `user settings preserve legacy folders and contact sync wire names`() {
        val settings = InboundGatewayGsonParser.gatewayGsonInstance.fromJson(
            """
            {
              "contact_sync_enabled": true,
              "guild_positions": [1, 2],
              "friend_source_flags": {"all": false, "mutual_friends": true, "mutual_guilds": true},
              "locale": "en-US"
            }
            """.trimIndent(),
            ModelUserSettings::class.java,
        )

        assertEquals(true, settings.contactSyncUpsellShown)
        assertEquals(listOf(1L, 2L), settings.guildFolders!!.flatMap { it.guildIds })
        assertEquals(true, settings.friendSourceFlags!!.mutualFriends)
        assertEquals("en", ModelUserSettings.getLocaleObject(settings.locale!!).language)
        assertEquals("US", ModelUserSettings.getLocaleObject(settings.locale!!).country)
    }

    @Test
    fun `explicit guild folders win over legacy positions`() {
        val settings = InboundGatewayGsonParser.gatewayGsonInstance.fromJson(
            """{"guild_folders":[{"id":"9","guild_ids":["3"]}],"guild_positions":["1","2"]}""",
            ModelUserSettings::class.java,
        )

        assertEquals(9L, settings.guildFolders!!.single().id)
        assertEquals(listOf(3L), settings.guildFolders!!.single().guildIds)
    }

    @Test
    fun `subscription exposes reference plan helpers`() {
        val none = subscription(SubscriptionPlanType.NONE_MONTH.planId)
        val nitro = subscription(SubscriptionPlanType.PREMIUM_MONTH_TIER_2.planId)

        assertEquals(SubscriptionPlanType.NONE_MONTH, none.getPlanType())
        assertTrue(none.isNonePlan())
        assertEquals(SubscriptionPlanType.PREMIUM_MONTH_TIER_2, nitro.getPlanType())
        assertFalse(nitro.isNonePlan())
        assertEquals(5_270_400_000L, none.getAccountHoldEstimatedExpirationTimestamp())
    }

    @Test
    fun `payment source wrapper validates its type-specific fields`() {
        val raw = PaymentSourceRaw(
            type = 1,
            id = "fixture",
            invalid = false,
            billingAddress = ModelBillingAddress("", "", "", "", "", "", ""),
            default = true,
            email = null,
            brand = "visa",
            last_4 = "4242",
            expiresMonth = 1,
            expiresYear = 2030,
        )

        val card = ModelPaymentSource.wrap(raw) as ModelPaymentSource.ModelPaymentSourceCard
        assertEquals("visa", card.brand)
        assertEquals("4242", card.last4)
        assertFalse(card.toString().contains("4242"))
        try {
            ModelPaymentSource.wrap(raw.copy(type = 99))
            throw AssertionError("unsupported payment source accepted")
        } catch (_: ModelPaymentSource.UnableToWrapException) {
            // Expected contract: every malformed or unsupported raw source is wrapped uniformly.
        }
    }

    private fun subscription(planId: Long) = ModelSubscription(
        id = "fixture",
        createdAt = "1970-01-01T00:00:00+00:00",
        canceledAt = null,
        currentPeriodStart = "1970-01-01T00:00:00+00:00",
        currentPeriodEnd = "1970-02-01T00:00:00+00:00",
        paymentSourceId = null,
        paymentGateway = null,
        trialId = null,
        trialEndsAt = null,
        renewalMutations = null,
        items = listOf(ModelSubscription.SubscriptionItem(1, planId, 1)),
        paymentGatewayPlanId = null,
        statusValue = ModelSubscription.Status.ACTIVE.intRepresentation,
        typeValue = 1,
    )
}
