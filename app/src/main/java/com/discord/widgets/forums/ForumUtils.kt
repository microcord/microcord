package com.discord.widgets.forums

import android.content.Context
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.message.Message
import com.discord.stores.ArchivedThreadsStore
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import rx.Observable

object ForumUtils {
    private const val FORUM_EXPERIMENT = "2021-07_threads_only_channel"
    private const val REDESIGN_EXPERIMENT = "2022-03_android_forum_channel_redesign"

    fun canAccessForumChannels(guildId: Long, experiments: StoreExperiments = StoreStream.getExperiments()) =
        experiments.getGuildExperiment(FORUM_EXPERIMENT, guildId)?.bucket == 1

    fun canAccessRedesignedForumChannels(
        guildId: Long,
        experiments: StoreExperiments = StoreStream.getExperiments(),
    ) = experiments.getGuildExperiment(REDESIGN_EXPERIMENT, guildId)?.bucket == 1

    fun observeCanCreateForumChannels(
        guildId: Long,
        experiments: StoreExperiments = StoreStream.getExperiments(),
    ): Observable<Boolean> = experiments.observeGuildExperiment(FORUM_EXPERIMENT, guildId)
        .map { it?.bucket == 1 }
        .distinctUntilChanged()

    fun observeCanAccessRedesignedForumChannels(
        guildId: Long,
        experiments: StoreExperiments = StoreStream.getExperiments(),
    ): Observable<Boolean> = experiments.observeGuildExperiment(REDESIGN_EXPERIMENT, guildId)
        .map { it?.bucket == 1 }
        .distinctUntilChanged()

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
        experiments: StoreExperiments = StoreStream.getExperiments(),
    ): Observable<Channel?> = selected.observeSelectedChannel().map { channel ->
        channel?.takeIf {
            canAccessRedesignedForumChannels(it.guildId, experiments) &&
                channels.getChannel(it.parentId)?.isGuildForumChannel() == true
        }
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
}
