package com.discord.models.user

import com.discord.api.activity.ActivityActionConfirmation
import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivitySecrets
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.premium.PremiumTier
import com.discord.api.user.User
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelUserRelationship
import com.discord.nullserializable.NullSerializable
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadySocialDtoFixtureTest {
    private data class Fixture(
        val user: User,
        val relationships: List<ModelUserRelationship>,
        val guildMembers: List<GuildMember>,
        val presences: List<Presence>,
        val sessions: List<ModelSession>,
        val connectedAccounts: List<ConnectedAccount>,
        val customStatus: ModelCustomStatusSetting,
    )

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun parsesCompleteReadySocialClosure() {
        val json = requireNotNull(javaClass.getResource("/fixtures/gateway/ready_social.json")).readText()
        val fixture = gson.fromJson(json, Fixture::class.java)

        assertEquals(NullSerializable.Value("avatar-hash"), fixture.user.avatar)
        assertTrue(fixture.user.banner is NullSerializable.Null)
        assertEquals(PremiumTier.TIER_2, fixture.user.premiumType)
        assertEquals("+10000000000", (fixture.user.phone as com.discord.api.user.Phone.PhoneNumber).number)
        assertEquals(42, MeUser(fixture.user).discriminator)
        assertFalse(MeUser(fixture.user).toString().contains("fixture-token"))

        val relationship = fixture.relationships.single()
        assertEquals(ModelUserRelationship.TYPE_FRIEND, relationship.type)
        val hydrated = relationship.hydrate(mapOf(101L to fixture.presences.single().user!!))
        assertEquals("friend", hydrated.user?.username)
        assertNull(hydrated.userId)

        val member = fixture.guildMembers.single()
        assertEquals(listOf(7L, 8L), member.roles)
        assertEquals("member-avatar", member.avatar)
        assertEquals(101L, member.presence?.userId)
        assertTrue(member.joinedAt!!.dateTimeMillis > 0)

        val activity = fixture.presences.single().activities!!.single()
        assertEquals(ActivityPlatform.SPOTIFY, activity.platform)
        assertEquals(1_000_000L, activity.timestamps?.startMs)
        assertEquals(1_650_000_000_000L, activity.timestamps?.endMs)
        assertEquals("Android", fixture.sessions.single().clientInfo?.os)
        assertTrue(fixture.sessions.single().active)
        assertFalse(fixture.sessions.single().toString().contains("session-fixture"))

        val account = fixture.connectedAccounts.single()
        assertEquals("nested-id", account.integrations?.single()?.account?.id)
        assertEquals(77L, account.integrations?.single()?.guild?.id)
        assertEquals(account, account.copy())
        assertEquals("fixture status", fixture.customStatus.text)
        assertEquals(9L, fixture.customStatus.emojiId)

        assertEquals("ActivitySecrets(match=<redacted>, join=<redacted>)", ActivitySecrets("match", "join").toString())
        assertEquals("ActivityActionConfirmation(secret=<redacted>)", ActivityActionConfirmation("secret").toString())
        assertTrue(fixture.user.toString().contains("token=<redacted>"))
    }
}
