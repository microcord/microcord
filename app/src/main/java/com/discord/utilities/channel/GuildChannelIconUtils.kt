package com.discord.utilities.channel

import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.permission.Permission
import com.discord.utilities.denies

/**
 * The icon a guild channel shows in the channel list, the topic bar and every channel picker.
 *
 * Split into a type and a type→drawable mapping because callers on the topic bar keep the
 * *type* in their view state (it is cheap to compare) and only resolve a drawable at bind time.
 *
 * "Locked" is not a channel property: it is derived from the `@everyone` overwrite denying
 * `VIEW_CHANNEL`, which is how the reference decides whether to draw the padlock variant.
 */
sealed class GuildChannelIconType {
    object Text : GuildChannelIconType()
    object Thread : GuildChannelIconType()
    object Voice : GuildChannelIconType()
    object Announcements : GuildChannelIconType()
    object Forum : GuildChannelIconType()
    object ForumPost : GuildChannelIconType()
    object Directory : GuildChannelIconType()

    sealed class NSFW {
        object Text : GuildChannelIconType()
        object Thread : GuildChannelIconType()
        object Voice : GuildChannelIconType()
        object Announcements : GuildChannelIconType()
        object Forum : GuildChannelIconType()
        object ForumPost : GuildChannelIconType()
    }

    sealed class Locked {
        object Text : GuildChannelIconType()
        object Thread : GuildChannelIconType()
        object Announcements : GuildChannelIconType()
        object Forum : GuildChannelIconType()
        object ForumPost : GuildChannelIconType()
    }
}

/**
 * True when the guild's `@everyone` overwrite on this channel denies `VIEW_CHANNEL`, i.e. the
 * channel is visible to the viewer only through a role grant.
 */
private fun Channel.isEveryoneDeniedView(): Boolean =
    permissionOverwrites?.firstOrNull { it.id == guildId }?.denies(Permission.VIEW_CHANNEL) == true

/**
 * Classifies [channel] for icon purposes. A null channel is treated as a plain text channel,
 * matching the reference — the topic bar renders that placeholder before the first store emission.
 *
 * Note the reference resolves forum posts with a null parent channel, so `isGuildForumPost` is
 * effectively false here; the branch is preserved for fidelity.
 */
fun getChannelType(channel: Channel?): GuildChannelIconType {
    if (channel == null) return GuildChannelIconType.Text

    if (channel.isGuildForumPost(null)) {
        return when {
            channel.nsfw -> GuildChannelIconType.NSFW.ForumPost
            channel.isEveryoneDeniedView() -> GuildChannelIconType.Locked.ForumPost
            else -> GuildChannelIconType.ForumPost
        }
    }

    return when (channel.type) {
        Channel.GUILD_VOICE ->
            if (channel.nsfw) GuildChannelIconType.NSFW.Voice else GuildChannelIconType.Voice

        Channel.GUILD_ANNOUNCEMENT -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Announcements
            channel.isEveryoneDeniedView() -> GuildChannelIconType.Locked.Announcements
            else -> GuildChannelIconType.Announcements
        }

        Channel.DIRECTORY -> GuildChannelIconType.Directory

        Channel.GUILD_FORUM -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Forum
            channel.isEveryoneDeniedView() -> GuildChannelIconType.Locked.Forum
            else -> GuildChannelIconType.Forum
        }

        Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD ->
            if (channel.nsfw) GuildChannelIconType.NSFW.Thread else GuildChannelIconType.Thread

        Channel.PRIVATE_THREAD -> GuildChannelIconType.Locked.Thread

        else -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Text
            channel.isEveryoneDeniedView() -> GuildChannelIconType.Locked.Text
            else -> GuildChannelIconType.Text
        }
    }
}

@DrawableRes
fun guildChannelIcon(channel: Channel?): Int = mapGuildChannelTypeToIcon(getChannelType(channel))

@DrawableRes
fun mapGuildChannelTypeToIcon(guildChannelIconType: GuildChannelIconType): Int =
    when (guildChannelIconType) {
        GuildChannelIconType.Text -> R.drawable.ic_channel_text
        GuildChannelIconType.Thread -> R.drawable.ic_thread
        GuildChannelIconType.Announcements -> R.drawable.ic_channel_announcements
        GuildChannelIconType.Forum -> R.drawable.ic_channel_forum
        GuildChannelIconType.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.NSFW.Text -> R.drawable.ic_channel_text_nsfw
        GuildChannelIconType.NSFW.Announcements -> R.drawable.ic_channel_announcements_nsfw
        GuildChannelIconType.NSFW.Thread -> R.drawable.ic_thread_locked
        GuildChannelIconType.NSFW.Voice -> R.drawable.ic_voice_nsfw
        GuildChannelIconType.NSFW.Forum -> R.drawable.ic_channel_forum_nsfw
        GuildChannelIconType.NSFW.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.Locked.Text -> R.drawable.ic_channel_text_locked
        GuildChannelIconType.Locked.Announcements -> R.drawable.ic_channel_announcements_locked
        GuildChannelIconType.Locked.Thread -> R.drawable.ic_thread_locked
        GuildChannelIconType.Locked.Forum -> R.drawable.ic_channel_forum_locked
        GuildChannelIconType.Locked.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.Directory -> R.drawable.ic_hub_24dp
        GuildChannelIconType.Voice -> R.drawable.ic_channel_voice_grey_header_24dp
    }
