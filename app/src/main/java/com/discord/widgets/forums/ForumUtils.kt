package com.discord.widgets.forums

import android.content.Context
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.permissions.PermissionUtils
import rx.Observable

object ForumUtils {
    fun canCreatePost(permissions: Long?): Boolean =
        PermissionUtils.can(Permission.SEND_MESSAGES, permissions) &&
            PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions)

    /**
     * The viewer's own sort, layout and tag matching for a channel.
     *
     * Each falls back to the channel's default until the viewer picks something, and "reset to
     * default" is expressed by clearing the override rather than writing the default back, so a
     * channel whose default later changes is followed again.
     */
    fun getSortOrder(channelId: Long, channel: Channel? = null): ForumSortOrder =
        readOverride(SORT_ORDER_KEY, channelId)
            ?.let { ForumSortOrder.entries.getOrNull(it) }
            ?: ForumSortOrder.entries.getOrNull(channel?.defaultSortOrder ?: -1)
            ?: ForumSortOrder.LATEST_ACTIVITY

    fun setSortOrder(channelId: Long, order: ForumSortOrder) =
        writeOverride(SORT_ORDER_KEY, channelId, order.ordinal)

    fun getLayout(channelId: Long, channel: Channel? = null): ForumLayout =
        readOverride(LAYOUT_KEY, channelId)
            ?.let(ForumLayout::fromApiValue)
            ?: ForumLayout.fromApiValue(channel?.defaultForumLayout ?: 0)
            ?: ForumLayout.LIST

    fun setLayout(channelId: Long, layout: ForumLayout) =
        writeOverride(LAYOUT_KEY, channelId, layout.apiValue)

    fun getTagSetting(channelId: Long, channel: Channel? = null): ForumTagSetting =
        readOverride(TAG_SETTING_KEY, channelId)
            ?.let { ForumTagSetting.entries.getOrNull(it) }
            ?: ForumTagSetting.fromApiValue(channel?.defaultTagSetting)

    fun setTagSetting(channelId: Long, setting: ForumTagSetting) =
        writeOverride(TAG_SETTING_KEY, channelId, setting.ordinal)

    /**
     * The tags a post must carry to be listed, by tag id.
     *
     * Unlike sort and layout the reference does not persist this across launches, but it does keep
     * it while navigating away and back, which a per-channel preference gives for free.
     */
    fun getTagFilter(channelId: Long): Set<String> =
        SharedPreferencesProvider.get().getStringSet("$TAG_FILTER_KEY$channelId", emptySet()).orEmpty()

    fun setTagFilter(channelId: Long, tagIds: Set<String>) {
        SharedPreferencesProvider.get().edit()
            .putStringSet("$TAG_FILTER_KEY$channelId", tagIds)
            .apply()
    }

    /** Drops every override so the channel's own defaults apply again. */
    fun resetDisplaySettings(channelId: Long) {
        SharedPreferencesProvider.get().edit()
            .remove("$SORT_ORDER_KEY$channelId")
            .remove("$LAYOUT_KEY$channelId")
            .remove("$TAG_SETTING_KEY$channelId")
            .remove("$TAG_FILTER_KEY$channelId")
            .apply()
    }

    private fun readOverride(key: String, channelId: Long): Int? =
        SharedPreferencesProvider.get().getInt("$key$channelId", NO_OVERRIDE).takeIf { it != NO_OVERRIDE }

    private fun writeOverride(key: String, channelId: Long, value: Int) {
        SharedPreferencesProvider.get().edit().putInt("$key$channelId", value).apply()
    }

    fun isGuildNsfwGateAgreed(guildId: Long): Boolean = guildId.toString() in
        SharedPreferencesProvider.get().getStringSet(NSFW_GUILDS_ALLOWED_KEY, emptySet()).orEmpty()

    fun allowGuildNsfw(guildId: Long) {
        val prefs = SharedPreferencesProvider.get()
        val allowed = prefs.getStringSet(NSFW_GUILDS_ALLOWED_KEY, emptySet()).orEmpty() + guildId.toString()
        prefs.edit().putStringSet(NSFW_GUILDS_ALLOWED_KEY, allowed).apply()
    }

    fun observeSelectedPostChannel(
        channels: StoreChannels = StoreStream.getChannels(),
        selected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    ): Observable<Channel?> = selected.observeSelectedChannel().map { channel ->
        channel?.takeIf { channels.getChannel(it.parentId)?.isThreadOnlyChannel() == true }
    }.distinctUntilChanged()

    fun maybeLoadAndObserveThreadListing(
        channelId: Long,
        hasActiveThreads: Boolean,
        archivedThreads: ArchivedThreadsStore = StoreStream.getArchivedThreads(),
    ): Observable<ArchivedThreadsStore.ThreadListingState> = if (hasActiveThreads) {
        archivedThreads.observeGuildForumThreadListing(channelId)
    } else {
        archivedThreads.loadAndObserveGuildForumThreadListing(channelId)
    }

    fun getActiveAgoTimestamp(context: Context, timestamp: Long?, now: Long = System.currentTimeMillis()): CharSequence? {
        timestamp ?: return null
        val elapsed = now - timestamp
        if (elapsed < 0) return null
        val (resource, count) = when {
            elapsed < HOUR -> R.string.form_post_active_ago_timestamp_minutes to elapsed / MINUTE
            elapsed < DAY -> R.string.form_post_active_ago_timestamp_hours to elapsed / HOUR
            elapsed < THIRTY_DAYS -> R.string.form_post_active_ago_timestamp_days to elapsed / DAY
            else -> return context.getString(R.string.form_post_active_ago_timestamp_more_than_month)
        }
        return context.getString(resource).replace("{count}", count.toString())
    }

    fun getPlaceholderMessageReaction(storeEmoji: StoreEmoji): MessageReaction? =
        storeEmoji.unicodeEmojisNamesMap["heart"]?.let {
            MessageReaction(0, MessageReactionEmoji(null, it.getUniqueId(), false), false)
        }

    fun Message.mostCommonReaction(storeEmoji: StoreEmoji): MessageReaction? = reactions.orEmpty().minWithOrNull(
        compareByDescending<MessageReaction> { it.count }
            .thenByDescending { it.emoji.name == getPlaceholderMessageReaction(storeEmoji)?.emoji?.name }
            .thenBy { it.emoji.reactionKey },
    )

    fun MessageReaction.isDefaultPostReaction(storeEmoji: StoreEmoji): Boolean =
        emoji.name == getPlaceholderMessageReaction(storeEmoji)?.emoji?.name

    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private const val THIRTY_DAYS = 2_592_000_000L
    private const val NSFW_GUILDS_ALLOWED_KEY = "GUILDS_ALLOWED_KEY"
    private const val SORT_ORDER_KEY = "forum_sort_order_"
    private const val LAYOUT_KEY = "forum_layout_"
    private const val TAG_SETTING_KEY = "forum_tag_setting_"
    private const val TAG_FILTER_KEY = "forum_tag_filter_"
    private const val NO_OVERRIDE = -1
}

enum class ForumSortOrder { LATEST_ACTIVITY, CREATION_DATE }

/** `default_forum_layout`; the wire's 0 means the channel never set one. */
enum class ForumLayout(val apiValue: Int) {
    LIST(1),
    GALLERY(2),
    ;

    companion object {
        fun fromApiValue(value: Int?): ForumLayout? = entries.firstOrNull { it.apiValue == value }
    }
}

enum class ForumTagSetting(val apiValue: String) {
    MATCH_SOME("match_some"),
    MATCH_ALL("match_all"),
    ;

    companion object {
        fun fromApiValue(value: String?): ForumTagSetting =
            entries.firstOrNull { it.apiValue == value } ?: MATCH_SOME
    }
}
