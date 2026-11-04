package com.discord.utilities.channel

import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGameInvitesChannel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isModeratorReportChannel
import com.discord.api.channel.isSpoilerChannel
import com.discord.api.permission.Permission
import com.discord.stores.StoreStream
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
    object Media : GuildChannelIconType()
    object ForumPost : GuildChannelIconType()
    object Stage : GuildChannelIconType()
    object Directory : GuildChannelIconType()

    sealed class NSFW {
        object Text : GuildChannelIconType()
        object Thread : GuildChannelIconType()
        object Voice : GuildChannelIconType()
        object Announcements : GuildChannelIconType()
        object Forum : GuildChannelIconType()
        object ForumPost : GuildChannelIconType()
    }

    /** Channels the app marks out with a glyph of their own instead of `#`, megaphone or bubble. */
    sealed class Special : GuildChannelIconType() {
        object Rules : Special()
        object ModeratorReport : Special()
        object SpoilerText : Special()
        object SpoilerAnnouncements : Special()
        object SpoilerForum : Special()
        object SpoilerVoice : Special()
        object GameInvites : Special()
        object LockedGameInvites : Special()
        object NsfwMedia : Special()
        object LockedMedia : Special()
    }

    sealed class Locked {
        object Voice : GuildChannelIconType()
        object Text : GuildChannelIconType()
        object Thread : GuildChannelIconType()
        object Announcements : GuildChannelIconType()
        object Forum : GuildChannelIconType()
        object ForumPost : GuildChannelIconType()
        object Stage : GuildChannelIconType()
    }
}

/**
 * True when the guild's `@everyone` overwrite on this channel denies `VIEW_CHANNEL`, i.e. the
 * channel is visible to the viewer only through a role grant.
 */
private fun Channel.isEveryoneDeniedView(): Boolean =
    permissionOverwrites?.firstOrNull { it.id == guildId }?.denies(Permission.VIEW_CHANNEL) == true

private fun isRulesChannel(channel: Channel?): Boolean =
    channel != null && channel.guildId != 0L &&
        StoreStream.getGuilds().getGuild(channel.guildId)?.rulesChannelId == channel.id

/**
 * Classifies [channel] for icon purposes. A null channel is treated as a plain text channel,
 * matching the reference — the topic bar renders that placeholder before the first store emission.
 *
 * The order is the reference's: the moderator-report and rules channels win outright, then
 * NSFW, spoiler and locked variants of the channel's own type, then the plain icon.
 * [locked] is overridable because the channel list already knows it from the viewer's roles;
 * everywhere else it falls back to the `@everyone` overwrite.
 *
 * Note the reference resolves forum posts with a null parent channel, so `isGuildForumPost` is
 * effectively false here; the branch is preserved for fidelity.
 */
fun getChannelType(
    channel: Channel?,
    locked: Boolean = channel?.isEveryoneDeniedView() == true,
    isRules: Boolean = isRulesChannel(channel),
): GuildChannelIconType {
    if (channel == null) return GuildChannelIconType.Text

    if (channel.isGuildForumPost(null)) {
        return when {
            channel.nsfw -> GuildChannelIconType.NSFW.ForumPost
            locked -> GuildChannelIconType.Locked.ForumPost
            else -> GuildChannelIconType.ForumPost
        }
    }

    if (channel.isModeratorReportChannel()) return GuildChannelIconType.Special.ModeratorReport
    if (isRules) return GuildChannelIconType.Special.Rules

    val spoiler = channel.isSpoilerChannel()
    return when (channel.type) {
        Channel.GUILD_VOICE -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Voice
            spoiler -> GuildChannelIconType.Special.SpoilerVoice
            locked -> GuildChannelIconType.Locked.Voice
            else -> GuildChannelIconType.Voice
        }

        Channel.GUILD_ANNOUNCEMENT -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Announcements
            spoiler -> GuildChannelIconType.Special.SpoilerAnnouncements
            locked -> GuildChannelIconType.Locked.Announcements
            else -> GuildChannelIconType.Announcements
        }

        Channel.DIRECTORY -> GuildChannelIconType.Directory

        Channel.GUILD_FORUM -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Forum
            spoiler -> GuildChannelIconType.Special.SpoilerForum
            locked && channel.isGameInvitesChannel() -> GuildChannelIconType.Special.LockedGameInvites
            locked -> GuildChannelIconType.Locked.Forum
            channel.isGameInvitesChannel() -> GuildChannelIconType.Special.GameInvites
            else -> GuildChannelIconType.Forum
        }

        Channel.GUILD_MEDIA -> when {
            channel.nsfw -> GuildChannelIconType.Special.NsfwMedia
            spoiler -> GuildChannelIconType.Special.SpoilerForum
            locked -> GuildChannelIconType.Special.LockedMedia
            else -> GuildChannelIconType.Media
        }

        Channel.GUILD_STAGE_VOICE ->
            if (locked) GuildChannelIconType.Locked.Stage else GuildChannelIconType.Stage

        Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD ->
            if (channel.nsfw) GuildChannelIconType.NSFW.Thread else GuildChannelIconType.Thread

        Channel.PRIVATE_THREAD -> GuildChannelIconType.Locked.Thread

        else -> when {
            channel.nsfw -> GuildChannelIconType.NSFW.Text
            spoiler -> GuildChannelIconType.Special.SpoilerText
            locked -> GuildChannelIconType.Locked.Text
            else -> GuildChannelIconType.Text
        }
    }
}

/**
 * The icon for a channel the plain text / announcement / forum / media rows don't cover (rules,
 * moderator report, spoiler, game invites, NSFW or locked media), or null for any other channel.
 * Lets the channel list keep its thread-aware variants for ordinary channels.
 */
@DrawableRes
fun specialGuildChannelIcon(channel: Channel, locked: Boolean): Int? =
    (getChannelType(channel, locked) as? GuildChannelIconType.Special)?.let(::mapGuildChannelTypeToIcon)

@DrawableRes
fun guildChannelIcon(channel: Channel?): Int = mapGuildChannelTypeToIcon(getChannelType(channel))

@DrawableRes
fun mapGuildChannelTypeToIcon(guildChannelIconType: GuildChannelIconType): Int =
    when (guildChannelIconType) {
        GuildChannelIconType.Text -> R.drawable.ic_channel_text
        GuildChannelIconType.Thread -> R.drawable.ic_thread
        GuildChannelIconType.Announcements -> R.drawable.ic_channel_announcements
        GuildChannelIconType.Forum -> R.drawable.ic_discord_forum_filled
        GuildChannelIconType.Media -> R.drawable.ic_image_library_24dp
        GuildChannelIconType.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.NSFW.Text -> R.drawable.ic_channel_text_nsfw
        GuildChannelIconType.NSFW.Announcements -> R.drawable.ic_channel_announcements_nsfw
        GuildChannelIconType.NSFW.Thread -> R.drawable.ic_thread_locked
        GuildChannelIconType.NSFW.Voice -> R.drawable.ic_voice_nsfw
        GuildChannelIconType.NSFW.Forum -> R.drawable.ic_channel_forum_nsfw
        GuildChannelIconType.NSFW.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.Special.Rules -> R.drawable.ic_channel_rules
        GuildChannelIconType.Special.ModeratorReport -> R.drawable.ic_channel_moderator_report
        GuildChannelIconType.Special.SpoilerText -> R.drawable.ic_channel_text_spoiler
        GuildChannelIconType.Special.SpoilerAnnouncements -> R.drawable.ic_channel_announcements_spoiler
        GuildChannelIconType.Special.SpoilerForum -> R.drawable.ic_channel_forum_spoiler
        GuildChannelIconType.Special.SpoilerVoice -> R.drawable.ic_channel_voice_spoiler
        GuildChannelIconType.Special.GameInvites -> R.drawable.ic_channel_forum_game_invites
        GuildChannelIconType.Special.LockedGameInvites -> R.drawable.ic_channel_forum_game_invites_locked
        GuildChannelIconType.Special.NsfwMedia -> R.drawable.ic_channel_media_nsfw
        GuildChannelIconType.Special.LockedMedia -> R.drawable.ic_channel_media_locked

        GuildChannelIconType.Locked.Voice -> R.drawable.ic_channel_voice_locked
        GuildChannelIconType.Locked.Text -> R.drawable.ic_channel_text_locked
        GuildChannelIconType.Locked.Announcements -> R.drawable.ic_channel_announcements_locked
        GuildChannelIconType.Locked.Thread -> R.drawable.ic_thread_locked
        GuildChannelIconType.Locked.Forum -> R.drawable.ic_channel_forum_locked
        GuildChannelIconType.Locked.ForumPost -> R.drawable.ic_channel_forum_post

        GuildChannelIconType.Stage -> R.drawable.ic_channel_stage_24dp
        GuildChannelIconType.Locked.Stage -> R.drawable.ic_channel_stage_locked

        GuildChannelIconType.Directory -> R.drawable.ic_hub_24dp
        GuildChannelIconType.Voice -> R.drawable.ic_channel_voice_grey_header_24dp
    }
