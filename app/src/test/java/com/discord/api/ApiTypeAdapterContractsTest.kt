package com.discord.api

import com.discord.api.application.ApplicationType
import com.discord.api.application.ApplicationTypeAdapter
import com.discord.api.auth.OAuthScope
import com.discord.api.auth.OAuthScopeTypeAdapter
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandTypeAdapter
import com.discord.api.friendsuggestions.AllowedInSuggestionsType
import com.discord.api.friendsuggestions.AllowedInSuggestionsTypeAdapter
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitTypeAdapter
import com.discord.api.guildrolesubscription.PayoutGroupStatus
import com.discord.api.guildrolesubscription.PayoutGroupStatusTypeAdapter
import com.discord.api.guildrolesubscription.PayoutGroupType
import com.discord.api.guildrolesubscription.PayoutGroupTypeAdapter
import com.discord.api.guildrolesubscription.PayoutStatus
import com.discord.api.guildrolesubscription.PayoutStatusAdapter
import com.discord.api.localizedstring.LocalizedString
import com.discord.api.localizedstring.LocalizedStringTypeAdapter
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.activity.MessageActivityTypeTypeAdapter
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedTypeTypeAdapter
import com.discord.api.premium.PriceTierType
import com.discord.api.premium.PriceTierTypeAdapter
import com.discord.api.report.ReportNodeBottomButton
import com.discord.api.report.ReportNodeBottomButtonTypeAdapter
import com.discord.api.report.ReportNodeChild
import com.discord.api.report.ReportNodeChildTypeAdapter
import com.discord.api.report.ReportNodeElementData
import com.discord.api.report.ReportNodeElementDataTypeAdapter
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every enum-valued wire field the API surface registers an adapter for, checked in both
 * directions plus the value the adapter must fall back to when the server sends something this
 * build predates. A silent `null` here is what makes an embed or a command render blank.
 */
class ApiTypeAdapterContractsTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(EmbedType::class.java, EmbedTypeTypeAdapter())
        .registerTypeAdapter(ApplicationCommandType::class.java, CommandTypeAdapter())
        .registerTypeAdapter(AllowedInSuggestionsType::class.java, AllowedInSuggestionsTypeAdapter())
        .registerTypeAdapter(ApplicationType::class.java, ApplicationTypeAdapter())
        .registerTypeAdapter(PriceTierType::class.java, PriceTierTypeAdapter())
        .registerTypeAdapter(
            GuildRoleSubscriptionBenefitType::class.java,
            GuildRoleSubscriptionBenefitTypeAdapter(),
        )
        .registerTypeAdapter(PayoutStatus::class.java, PayoutStatusAdapter())
        .registerTypeAdapter(PayoutGroupStatus::class.java, PayoutGroupStatusTypeAdapter())
        .registerTypeAdapter(PayoutGroupType::class.java, PayoutGroupTypeAdapter())
        .registerTypeAdapter(LocalizedString::class.java, LocalizedStringTypeAdapter())
        .registerTypeAdapter(MessageActivityType::class.java, MessageActivityTypeTypeAdapter())
        .registerTypeAdapter(OAuthScope::class.java, OAuthScopeTypeAdapter())
        .registerTypeAdapter(ReportNodeChild::class.java, ReportNodeChildTypeAdapter())
        .registerTypeAdapter(ReportNodeElementData::class.java, ReportNodeElementDataTypeAdapter())
        .registerTypeAdapter(ReportNodeBottomButton::class.java, ReportNodeBottomButtonTypeAdapter())
        .create()

    @Test
    fun embedTypeResolvesEveryWireValueAndNeverYieldsNull() {
        for (type in EmbedType.values()) {
            assertEquals(type, gson.fromJson("\"${type.apiValue}\"", EmbedType::class.java))
        }
        assertEquals(EmbedType.RICH, gson.fromJson("\"rich\"", EmbedType::class.java))
        assertEquals(EmbedType.UNKNOWN, gson.fromJson("\"post_preview\"", EmbedType::class.java))
        assertEquals("\"gifv\"", gson.toJson(EmbedType.GIFV, EmbedType::class.java))
    }

    @Test
    fun applicationCommandTypeRoundTrips() {
        assertEquals(ApplicationCommandType.STRING, gson.fromJson("3", ApplicationCommandType::class.java))
        assertEquals(ApplicationCommandType.ATTACHMENT, gson.fromJson("11", ApplicationCommandType::class.java))
        assertEquals(ApplicationCommandType.UNKNOWN, gson.fromJson("99", ApplicationCommandType::class.java))
        assertEquals("8", gson.toJson(ApplicationCommandType.ROLE, ApplicationCommandType::class.java))
    }

    @Test
    fun unknownFriendDiscoveryValueFallsBackToTheLeastSharingSetting() {
        assertEquals(
            AllowedInSuggestionsType.ANYONE_WITH_CONTACT_INFO,
            gson.fromJson("2", AllowedInSuggestionsType::class.java),
        )
        assertEquals(AllowedInSuggestionsType.DISABLED, gson.fromJson("42", AllowedInSuggestionsType::class.java))
        assertEquals("-1", gson.toJson(AllowedInSuggestionsType.DISABLED, AllowedInSuggestionsType::class.java))
    }

    @Test
    fun applicationTypeTreatsNullAndUnknownAlike() {
        assertEquals(ApplicationType.GAME, gson.fromJson("1", ApplicationType::class.java))
        assertEquals(
            ApplicationType.GUILD_ROLE_SUBSCRIPTIONS,
            gson.fromJson("4", ApplicationType::class.java),
        )
        assertEquals(ApplicationType.UNKNOWN, gson.fromJson("77", ApplicationType::class.java))
        assertEquals(ApplicationType.UNKNOWN, gson.fromJson("null", ApplicationType::class.java))
        assertEquals("null", gson.toJson(ApplicationType.UNKNOWN, ApplicationType::class.java))
        assertEquals("2", gson.toJson(ApplicationType.MUSIC, ApplicationType::class.java))
    }

    @Test
    fun premiumAndPayoutEnumsFallBackToUnknown() {
        assertEquals(
            PriceTierType.GUILD_ROLE_SUBSCRIPTIONS,
            gson.fromJson("1", PriceTierType::class.java),
        )
        assertEquals(PriceTierType.UNKNOWN, gson.fromJson("5", PriceTierType::class.java))

        assertEquals(
            GuildRoleSubscriptionBenefitType.CHANNEL,
            gson.fromJson("1", GuildRoleSubscriptionBenefitType::class.java),
        )
        assertEquals(
            GuildRoleSubscriptionBenefitType.UNKNOWN,
            gson.fromJson("9", GuildRoleSubscriptionBenefitType::class.java),
        )

        assertEquals(PayoutStatus.PENDING_FUNDS, gson.fromJson("13", PayoutStatus::class.java))
        assertEquals(PayoutStatus.UNKNOWN, gson.fromJson("99", PayoutStatus::class.java))
        assertEquals("2", gson.toJson(PayoutStatus.PAID, PayoutStatus::class.java))

        assertEquals(PayoutGroupStatus.PAYOUT_CREATED, gson.fromJson("2", PayoutGroupStatus::class.java))
        assertEquals(PayoutGroupStatus.UNKNOWN, gson.fromJson("9", PayoutGroupStatus::class.java))

        assertEquals(PayoutGroupType.MARKETPLACE, gson.fromJson("\"marketplace\"", PayoutGroupType::class.java))
        assertEquals(
            PayoutGroupType.SERVER_ROLE_SUBSCRIPTION,
            gson.fromJson("\"server_role_subscription\"", PayoutGroupType::class.java),
        )
        assertEquals(PayoutGroupType.UNKNOWN, gson.fromJson("\"future_thing\"", PayoutGroupType::class.java))
        assertEquals("\"stage_event_sku\"", gson.toJson(PayoutGroupType.STAGE_EVENT_SKU, PayoutGroupType::class.java))
    }

    @Test
    fun localizedStringAcceptsBothWireShapes() {
        assertEquals(
            LocalizedString("Hello", emptyMap()),
            gson.fromJson("\"Hello\"", LocalizedString::class.java),
        )
        val translated = gson.fromJson(
            """{"default":"Hello","localizations":{"fr":"Bonjour","de":"Hallo"}}""",
            LocalizedString::class.java,
        )
        assertEquals(LocalizedString("Hello", mapOf("fr" to "Bonjour", "de" to "Hallo")), translated)
        assertEquals(translated, gson.fromJson(gson.toJson(translated, LocalizedString::class.java), LocalizedString::class.java))
    }

    @Test
    fun messageActivityTypeReadsIntegers() {
        assertEquals(MessageActivityType.SPECTATE, gson.fromJson("2", MessageActivityType::class.java))
        assertEquals(MessageActivityType.JOIN, gson.fromJson("null", MessageActivityType::class.java))
        assertEquals("4", gson.toJson(MessageActivityType.WATCH, MessageActivityType::class.java))
    }

    @Test
    fun oauthScopeKeepsUnknownNamesInsteadOfDroppingThem() {
        assertEquals(OAuthScope.Identify, gson.fromJson("\"identify\"", OAuthScope::class.java))
        assertEquals(OAuthScope.ApplicationsCommandsUpdate, gson.fromJson("\"applications.commands.update\"", OAuthScope::class.java))
        assertEquals(OAuthScope.Invalid("teleport"), gson.fromJson("\"teleport\"", OAuthScope::class.java))
        assertNull(gson.fromJson("null", OAuthScope::class.java))
        assertEquals("\"guilds.join\"", gson.toJson(OAuthScope.GuildsJoin, OAuthScope::class.java))
    }

    @Test
    fun reportMenuAdaptersReadTheirCompactArrayForms() {
        assertEquals(ReportNodeChild("Spam", 3), gson.fromJson("""["Spam",3]""", ReportNodeChild::class.java))
        assertEquals("""["Spam",3]""", gson.toJson(ReportNodeChild("Spam", 3), ReportNodeChild::class.java))

        assertEquals(
            ReportNodeElementData("message_id", "1"),
            gson.fromJson("""["message_id","1"]""", ReportNodeElementData::class.java),
        )

        assertEquals(
            ReportNodeBottomButton.Next(7),
            gson.fromJson("""{"type":"next","target":7}""", ReportNodeBottomButton::class.java),
        )
        assertEquals(
            ReportNodeBottomButton.Next(7),
            gson.fromJson("""{"type":"next","target":"7"}""", ReportNodeBottomButton::class.java),
        )
        assertEquals(
            ReportNodeBottomButton.Submit,
            gson.fromJson("""{"type":"submit","target":null}""", ReportNodeBottomButton::class.java),
        )
        assertEquals(
            ReportNodeBottomButton.Cancel,
            gson.fromJson("""{"type":"cancel"}""", ReportNodeBottomButton::class.java),
        )
        assertNull(gson.fromJson("""{"type":"teleport"}""", ReportNodeBottomButton::class.java))
    }
}
