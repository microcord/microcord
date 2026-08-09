package com.discord.api.guild

import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityInGuild
import com.discord.api.emoji.GuildEmoji
import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.api.guildhash.GuildHashes
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.Sticker
import com.discord.api.voice.state.VoiceState

data class Guild(
    val roles: List<GuildRole>?,
    val emojis: List<GuildEmoji>?,
    val stickers: List<Sticker>?,
    val name: String,
    val description: String?,
    val defaultMessageNotifications: Int?,
    val id: Long,
    val region: String?,
    val ownerId: Long,
    val icon: String?,
    val verificationLevel: GuildVerificationLevel?,
    val explicitContentFilter: GuildExplicitContentFilter?,
    val presences: List<Presence>?,
    val channels: List<Channel>?,
    val members: List<GuildMember>?,
    val voiceStates: List<VoiceState>?,
    val unavailable: Boolean,
    val mfaLevel: Int,
    val afkTimeout: Int,
    val afkChannelId: Long?,
    val systemChannelId: Long?,
    val features: List<GuildFeature>,
    val memberCount: Int,
    val banner: String?,
    val splash: String?,
    val premiumTier: Int,
    val premiumSubscriptionCount: Int,
    val systemChannelFlags: Int,
    val joinedAt: String?,
    val rulesChannelId: Long?,
    val publicUpdatesChannelId: Long?,
    val preferredLocale: String?,
    val welcomeScreen: GuildWelcomeScreen?,
    val maxVideoChannelUsers: GuildMaxVideoChannelUsers?,
    val vanityUrlCode: String?,
    val approximateMemberCount: Int,
    val approximatePresenceCount: Int,
    val permissions: Long?,
    val guildHashes: GuildHashes?,
    val channelUpdates: List<Channel>?,
    val threads: List<Channel>?,
    val nsfw: Boolean,
    val stageInstances: List<StageInstance>?,
    val guildScheduledEvents: List<GuildScheduledEvent>?,
    val embeddedActivities: List<EmbeddedActivityInGuild>?,
    val hubType: GuildHubType?,
    val safetyAlertsChannelId: Long? = null,
    val premiumProgressBarEnabled: Boolean = false,
    val nsfwLevel: GuildNsfwLevel = GuildNsfwLevel.DEFAULT,
    val ownerConfiguredContentLevel: GuildNsfwLevel? = null,
    val incidentsData: GuildIncidentsData? = null,
    val officialMessageColor: Int? = null,
) {
    @Transient var version: Long = 0
}
