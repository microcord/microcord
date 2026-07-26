@file:JvmName("ChannelUtils")

package com.discord.api.channel

import android.content.Context
import com.discord.R
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.user.User as ApiUser
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils

/**
 * The canonical channel predicates and derived values.
 *
 * These are the single source of truth for "what kind of channel is this". Stores,
 * widgets, and utilities must call these instead of re-testing [Channel.type] inline,
 * because the type sets are not obvious (a "guild texty" channel includes threads and
 * directories, and [isPrivate] means DM *or* group DM).
 */

/** The Discord-owned directory channel that seeds the hub experience. */
private const val HQ_DIRECTORY_CHANNEL_ID = 883060064561299456L

private const val DISPLAY_PREFIX_GUILD = "#"
private const val DISPLAY_PREFIX_DM = "@"

/**
 * True for channel types that can hold messages posted by users: guild text,
 * announcements, all three thread types, directories, and forums.
 */
fun isTextyChannelType(type: Int): Boolean =
    type == Channel.GUILD_TEXT ||
        type == Channel.GUILD_ANNOUNCEMENT ||
        type == Channel.ANNOUNCEMENT_THREAD ||
        type == Channel.PUBLIC_THREAD ||
        type == Channel.PRIVATE_THREAD ||
        type == Channel.DIRECTORY ||
        type == Channel.GUILD_FORUM

/** A one-to-one direct message. */
fun Channel.isDM(): Boolean = type == Channel.DM

/** A group direct message. */
fun Channel.isGroup(): Boolean = type == Channel.GROUP_DM

/** Alias of [isGroup] used by the member-list and call surfaces. */
fun Channel.isMultiUserDM(): Boolean = type == Channel.GROUP_DM

/** A DM or a group DM, i.e. a channel that has no guild and no permission overwrites. */
fun Channel.isPrivate(): Boolean = type == Channel.DM || type == Channel.GROUP_DM

/** A DM whose only recipient is a Discord system account. */
fun Channel.isSystemDM(): Boolean =
    type == Channel.DM && recipients?.firstOrNull()?.system == true

fun Channel.isCategory(): Boolean = type == Channel.CATEGORY

fun Channel.isAnnouncementChannel(): Boolean = type == Channel.GUILD_ANNOUNCEMENT

fun Channel.isGuildForumChannel(): Boolean = type == Channel.GUILD_FORUM

fun Channel.isDirectory(): Boolean = type == Channel.DIRECTORY

/** A plain guild text channel or a forum channel. */
fun Channel.isGuildTextChannel(): Boolean =
    type == Channel.GUILD_TEXT || type == Channel.GUILD_FORUM

/** Any guild channel that renders a message list. See [isTextyChannelType]. */
fun Channel.isGuildTextyChannel(): Boolean = isTextyChannelType(type)

/** A guild voice or stage channel. */
fun Channel.isGuildVocalChannel(): Boolean =
    type == Channel.GUILD_VOICE || type == Channel.GUILD_STAGE_VOICE

/** A guild voice channel, excluding stage channels. */
fun Channel.isVoiceChannel(): Boolean = type == Channel.GUILD_VOICE

fun Channel.isStageVoiceChannel(): Boolean = type == Channel.GUILD_STAGE_VOICE

/** A private channel or any guild channel that renders a message list. */
fun Channel.isTextChannel(): Boolean = isPrivate() || isGuildTextyChannel()

/** Anything the user can be "in": a text-like channel or a voice channel. */
fun Channel.isTextOrVoiceChannel(): Boolean = isTextChannel() || isVoiceChannel()

/** Any of the three thread types. */
fun Channel.isThread(): Boolean =
    type == Channel.ANNOUNCEMENT_THREAD ||
        type == Channel.PUBLIC_THREAD ||
        type == Channel.PRIVATE_THREAD

/** A private thread, which only invited members and thread managers can see. */
fun Channel.isPrivateThread(): Boolean = type == Channel.PRIVATE_THREAD

/** A channel that threads can be created inside of. */
fun Channel.isThreaded(): Boolean =
    type == Channel.GUILD_TEXT || type == Channel.GUILD_ANNOUNCEMENT || type == Channel.GUILD_FORUM

fun Channel.isArchivedThread(): Boolean = threadMetadata?.archived == true

/** A thread whose parent is a forum channel, i.e. a forum post. */
fun Channel.isGuildForumPost(parentChannel: Channel?): Boolean =
    parentChannel?.type == Channel.GUILD_FORUM

/**
 * A channel created and owned by an application (for example a group DM opened by a
 * bot). Managed channels are never stored or shown by the client.
 */
fun Channel.isManaged(): Boolean = applicationId != 0L

fun Channel.isPinned(): Boolean = hasFlag(Channel.FLAG_PINNED)

fun Channel.hasFlag(flag: Long): Boolean = ((flags ?: Channel.FLAGS_NONE) and flag) == flag

fun Channel.isHQDirectoryChannel(): Boolean = id == HQ_DIRECTORY_CHANNEL_ID

/** Voice channels and private channels play a sound when someone joins or leaves. */
fun Channel.shouldPlayJoinLeaveSounds(): Boolean = isVoiceChannel() || isPrivate()

/**
 * The name to show for this channel.
 *
 * Private channels are allowed to have no name at all; the client then falls back to
 * the comma-separated recipient usernames. Everything else falls back to the empty
 * string rather than `null` so callers never render "null".
 */
fun Channel.getDisplayName(): String {
    if (isPrivate()) {
        val name = name
        if (name.isNullOrEmpty()) {
            return recipients?.joinToString { it.username } ?: ""
        }
    }
    return name ?: ""
}

/**
 * [getDisplayName] with the per-type placeholder used when a channel is unnamed, and
 * with the `#`/`@` prefix the reference renders in headers and mentions.
 */
@JvmOverloads
fun Channel.getDisplayNameOrDefault(context: Context, includePrefix: Boolean = true): String {
    val displayName = getDisplayName()
    when {
        isGuildTextChannel() || isAnnouncementChannel() -> {
            if (displayName.isEmpty()) return context.getString(R.string.invalid_text_channel)
            if (includePrefix) return DISPLAY_PREFIX_GUILD + displayName
        }
        isGuildVocalChannel() ->
            if (displayName.isEmpty()) return context.getString(R.string.invalid_voice_channel)
        isGroup() ->
            if (displayName.isEmpty()) return context.getString(R.string.unnamed)
        isDM() -> {
            if (displayName.isEmpty()) return context.getString(R.string.direct_message)
            if (includePrefix) return DISPLAY_PREFIX_DM + displayName
        }
    }
    return displayName
}

/** The string resource announcing that this channel no longer exists, if any. */
fun Channel.getDeletedMessage(): Int? = when {
    isCategory() -> R.string.category_has_been_deleted
    isThread() -> R.string.thread_has_been_deleted
    isPrivate() -> null
    else -> R.string.channel_has_been_deleted
}

/** The overwrite targeting [id], which may be a role id or a user id. */
fun Channel.getPermissionOverwriteForId(id: Long): PermissionOverwrite? =
    permissionOverwrites?.firstOrNull { it.id == id }

fun Channel.getRecipients(): List<User> = recipients.orEmpty().map(::CoreUser)

/** The single recipient of a one-to-one DM, or `null` for anything else. */
fun Channel.getDMRecipient(): User? {
    if (!isDM()) return null
    val recipients: List<ApiUser> = recipients ?: return null
    if (recipients.size != 1) return null
    return CoreUser(recipients.first())
}

/** Whether the DM row should render the recipient's presence dot. */
fun Channel.isDMStatusVisible(presence: Presence?): Boolean {
    val recipient = getDMRecipient()
    return isDM() && !isSystemDM() &&
        recipient != null && UserUtils.isStatusVisible(recipient, presence, true)
}

/**
 * The channel-list ordering: categories first by position, then text channels, then
 * voice channels, each by position, with the channel id breaking ties.
 */
fun Channel.Companion.getSortByNameAndType(): Comparator<Channel?> = Comparator { first, second ->
    when {
        first == null -> if (second == null) 0 else -1
        second == null -> 1
        first.isCategory() && second.isCategory() -> compareByPositionThenId(first, second)
        first.isCategory() -> -1
        second.isCategory() -> 1
        first.type == Channel.GUILD_TEXT && second.isGuildVocalChannel() -> -1
        first.isGuildVocalChannel() && second.type == Channel.GUILD_TEXT -> 1
        else -> compareByPositionThenId(first, second)
    }
}

/**
 * Orders channels by their most recent message, newest first, falling back to the
 * channel id when a channel has no known message.
 */
fun Channel.Companion.getSortByMostRecent(
    mostRecentMessageIds: Map<Long, Long>,
): Comparator<Channel> = Comparator { first, second ->
    val firstId = mostRecentMessageIds[first.id] ?: first.id
    val secondId = mostRecentMessageIds[second.id] ?: second.id
    secondId.compareTo(firstId)
}

private fun compareByPositionThenId(first: Channel, second: Channel): Int =
    if (first.position != second.position) {
        first.position - second.position
    } else {
        first.id.compareTo(second.id)
    }
