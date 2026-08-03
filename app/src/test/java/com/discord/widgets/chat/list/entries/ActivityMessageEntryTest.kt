package com.discord.widgets.chat.list.entries

import com.discord.api.application.Application
import com.discord.api.message.Message as ApiMessage
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelInvite
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityMessageEntryTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()
    private val fixture = javaClass.getResourceAsStream("/fixtures/activity_message_live.json")!!
        .reader().use { gson.fromJson(it, JsonObject::class.java) }
    private val apiMessage = gson.fromJson(fixture["message"], ApiMessage::class.java)
    private val application = apiMessage.application!!

    @Test fun liveMessageProjectsAndRoutesToDedicatedRow() {
        val message = Message(apiMessage)
        val row = ChatListEntry.createActivityMessageEntries(message).single() as ActivityMessageEntry

        assertEquals(ChatListEntry.ACTIVITY_MESSAGE, row.type)
        assertEquals("gcm-120000000000000010-880218394199220334", row.instanceId)
        assertEquals("Watch Together", row.application!!.name)
        assertTrue(ChatListEntry.createActivityMessageEntries(message.copy(type = 7, activityInstance = null)).isEmpty())
    }

    @Test fun resolvedEmbeddedInviteRoutesToActivityCard() {
        val invite = gson.fromJson(fixture["invite"], ModelInvite::class.java)
        val message = Message(apiMessage).copy(
            activityInstance = null,
            content = "https://discord.gg/sanitizedInvite",
        )
        val resolver = object : InviteEmbedResolver {
            override fun resolveInvite(inviteCode: String, eventId: String?) = invite
        }

        val row = ChatListEntry.createInviteEntries(message, resolver).single() as ActivityMessageEntry

        assertEquals("sanitizedInvite", row.inviteCode)
        assertEquals(application.id, row.application!!.id)
        assertEquals(apiMessage.author!!.id, row.inviter!!.id)
        assertFalse(invite.isExpired(2_000_000_000_000L))
    }

    @Test fun buttonStateCoversStartJoinAndOpen() {
        val entry = entry(application = application, instanceId = null)

        assertEquals(ActivityCardState.Action.START, state(entry).action)
        assertEquals(ActivityCardState.Action.JOIN, state(entry.copy(instanceId = "instance")).action)
        assertEquals(
            ActivityCardState.Action.OPEN,
            state(entry.copy(instanceId = "instance"), participantIds = setOf(me.id)).action,
        )
    }

    @Test fun unavailableStateCoversNoAppPermissionAgePlatformNitroAndExpiry() {
        assertEquals(ActivityCardState.UnavailableReason.NO_APPLICATION, state(entry(null)).reason)
        assertEquals(ActivityCardState.UnavailableReason.PERMISSION, state(entry(application), permissions = 0L).reason)

        val config = application.embeddedActivityConfig!!
        assertEquals(
            ActivityCardState.UnavailableReason.AGE,
            state(entry(application.copy(embeddedActivityConfig = config.copy(requiresAgeGate = true))), me.copy(nsfwAllowance = NsfwAllowance.UNKNOWN)).reason,
        )
        assertEquals(
            ActivityCardState.UnavailableReason.PLATFORM,
            state(entry(application.copy(embeddedActivityConfig = config.copy(supportedPlatforms = listOf("web"))))).reason,
        )
        assertEquals(
            ActivityCardState.UnavailableReason.NITRO,
            state(entry(application.copy(embeddedActivityConfig = config.copy(premiumTierRequirement = 4)))).reason,
        )

        val expired = gson.fromJson(
            """{"code":"expired","target_type":2,"expires_at":"2020-01-01T00:00:00Z"}""",
            ModelInvite::class.java,
        )
        assertEquals(ActivityCardState.UnavailableReason.ENDED, state(entry(application).copy(invite = expired)).reason)
    }

    @Test fun unknownJoinSecretsAreNotProjectedOrRendered() {
        assertNull(apiMessage.activityInstance!!::class.java.declaredFields.singleOrNull { it.name.contains("secret", true) })
        val row = ChatListEntry.createActivityMessageEntries(Message(apiMessage)).single()
        assertFalse(row.toString().contains("must-not-project"))
        assertFalse(row.key.contains("must-not-project"))
    }

    private fun entry(application: Application?, instanceId: String? = null) = ActivityMessageEntry(
        messageId = apiMessage.id,
        channelId = apiMessage.channelId,
        guildId = apiMessage.guildId!!,
        application = application,
        instanceId = instanceId,
    )

    private fun state(
        entry: ActivityMessageEntry,
        me: MeUser = this.me,
        participantIds: Set<Long> = emptySet(),
        permissions: Long? = Permission.USE_EMBEDDED_ACTIVITIES,
    ) = ActivityCardState.evaluate(entry, me, entry.instanceId, participantIds, permissions, now = 2_000_000_000_000L)

    private val me = MeUser(
        id = 42L,
        username = "viewer",
        avatar = null,
        banner = null,
        isBot = false,
        isSystemUser = false,
        discriminator = 0,
        premiumTier = PremiumTier.NONE,
        email = null,
        mfaEnabled = false,
        isVerified = true,
        token = null,
        flags = 0,
        publicFlags = 0,
        phoneNumber = null,
        nsfwAllowance = NsfwAllowance.ALLOWED,
        bio = null,
        bannerColor = null,
    )
}
