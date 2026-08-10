package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.embeddedactivities.EmbeddedActivityInGuild
import com.discord.api.emoji.GuildEmoji
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.Sticker
import com.discord.api.voice.state.VoiceState
import com.discord.api.utcdatetime.UtcDateTime
import com.google.gson.annotations.SerializedName

/** CLIENT_STATE_V2 guild envelope used by current React Native Gateway clients. */
data class ReadyGuild(
    val id: Long = 0,
    @SerializedName("data_mode") val dataMode: String = DATA_MODE_FULL,
    val version: Long = 0,
    val properties: Guild? = null,
    val roles: List<GuildRole>? = null,
    val emojis: List<GuildEmoji>? = null,
    val stickers: List<Sticker>? = null,
    val channels: List<Channel>? = null,
    @SerializedName("partial_updates") val partialUpdates: PartialUpdates? = null,
    @SerializedName("channel_updates") val channelUpdates: List<ChannelUnreadUpdate>? = null,
    val presences: List<Presence>? = null,
    val members: List<GuildMember>? = null,
    @SerializedName("voice_states") val voiceStates: List<VoiceState>? = null,
    val threads: List<Channel>? = null,
    @SerializedName("stage_instances") val stageInstances: List<StageInstance>? = null,
    @SerializedName("guild_scheduled_events") val guildScheduledEvents: List<GuildScheduledEvent>? = null,
    @SerializedName("embedded_activities") val embeddedActivities: List<EmbeddedActivityInGuild>? = null,
    @SerializedName("joined_at") val joinedAt: String? = null,
    @SerializedName("member_count") val memberCount: Int? = null,
    @SerializedName("premium_subscription_count") val premiumSubscriptionCount: Int? = null,
    @SerializedName("unable_to_sync_deletes") val unableToSyncDeletes: Boolean = false,
    /**
     * READY reports an unavailable guild as a bare `{id, unavailable}` stub rather than giving it a
     * [dataMode], so this and not [dataMode] is what identifies one. Only the HTTP background sync
     * normalises them to [DATA_MODE_UNAVAILABLE].
     */
    val unavailable: Boolean = false,
) {
    data class PartialUpdates(
        val roles: List<GuildRole>? = null,
        @SerializedName("deleted_role_ids") val deletedRoleIds: List<Long>? = null,
        val emojis: List<GuildEmoji>? = null,
        @SerializedName("deleted_emoji_ids") val deletedEmojiIds: List<Long>? = null,
        val stickers: List<Sticker>? = null,
        @SerializedName("deleted_sticker_ids") val deletedStickerIds: List<Long>? = null,
        val channels: List<Channel>? = null,
        @SerializedName("deleted_channel_ids") val deletedChannelIds: List<Long>? = null,
    )

    companion object {
        const val DATA_MODE_FULL = "full"
        const val DATA_MODE_PARTIAL = "partial"
        const val DATA_MODE_UNAVAILABLE = "unavailable"
    }
}

data class ChannelUnreadUpdate(
    val id: Long,
    @SerializedName("last_message_id") val lastMessageId: Long = 0,
    @SerializedName("last_pin_timestamp") val lastPinTimestamp: UtcDateTime? = null,
)

data class BackgroundSync(val guilds: List<ReadyGuild> = emptyList())
