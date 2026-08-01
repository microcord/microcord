package com.discord.widgets.user.search

import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildVocalChannel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toFuzzyMatchedResult
import com.discord.widgets.user.search.WidgetGlobalSearchModel.Companion.toMatchedResult
import com.discord.widgets.user.search.WidgetGlobalSearchScoreStrategy.score
import rx.Observable
import rx.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

/**
 * The quick switcher.
 *
 * Three things happen here that the old alphabetical list did not do:
 *
 *  * **Permission gating.** Only channels the viewer can actually open are candidates, using the
 *    same visibility rule as the channel list. A result you cannot navigate to is worse than no
 *    result.
 *  * **Ranking.** Candidates are scored by [WidgetGlobalSearchScoreStrategy] — frecency, unread
 *    mentions, presence, friendship, and where the typed text landed — and only the top
 *    [WidgetGlobalSearchModel.MAX_RESULTS] survive.
 *  * **Frecency.** Channel visits are recorded so the empty-query list is "where you actually were",
 *    not an arbitrary slice of everything.
 *
 * A leading `@`, `#`, `!` or `*` narrows the search to one kind and also changes the weights, which
 * is why the prefix is carried through as `searchType` rather than being consumed here.
 */
class WidgetGlobalSearchViewModel : AppViewModel<WidgetGlobalSearchModel>() {

    private val filterSubject = BehaviorSubject.create("")

    /**
     * Channel frecency.
     *
     * The reference tracks this from `StoreChannelsSelected`. That store is outside this slice, so
     * the tracker is fed here from the same observable instead — it produces the same series, but
     * only while a quick switcher has existed this session. Move it into the store when that file is
     * next touched.
     */
    private val frecencyTracker = ChannelFrecencyTracker()

    init {
        StoreStream.getChannelsSelected().observeId()
            .filter { it != 0L }
            .distinctUntilChanged()
            .ui(this)
            .subscribe { channelId -> frecencyTracker.track(channelId) }

        Observable
            .combineLatest(
                filterSubject.leadingEdgeThrottle(FILTER_THROTTLE_MS, TimeUnit.MILLISECONDS),
                channelContext(),
                userContext(),
                guildsRail(),
            ) { filter, channels, users, guildsList -> create(filter, channels, users, guildsList) }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .subscribe(::updateViewState)
    }

    fun setFilter(filter: String) {
        filterSubject.onNext(filter)
    }

    // region inputs

    private data class ChannelContext(
        val channels: Map<Long, Channel>,
        val guilds: Map<Long, Guild>,
        val channelIdsByGuild: Map<Long, List<Long>>,
        val mentionCounts: Map<Long, Int>,
        val unreadChannelIds: Set<Long>,
    )

    private data class UsersContext(
        val users: Map<Long, User>,
        val presences: Map<Long, com.discord.models.presence.Presence>,
        val relationships: Map<Long, Int>,
    )

    private fun channelContext(): Observable<ChannelContext> = Observable
        .combineLatest(
            StoreStream.getChannels().observeAllChannels(),
            StoreStream.getPermissions().observePermissionsForAllChannels(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getChannels().observeIds(),
            StoreStream.getMentions().observeMentionCounts(),
            StoreStream.getReadStates().getUnreadChannelIds(),
        ) { channels, permissions, guilds, channelIdsByGuild, mentionCounts, unreadChannelIds ->
            // Gate before anything else: an inaccessible channel must not even influence ranking.
            val visible = channels.filterValues {
                WidgetGlobalSearchModel.DEFAULT_PERMISSIONS_PREDICATE.invoke(it, permissions)
            }
            ChannelContext(visible, guilds, channelIdsByGuild, mentionCounts, unreadChannelIds)
        }
        .leadingEdgeThrottle(CONTEXT_THROTTLE_SECONDS, TimeUnit.SECONDS)

    private fun userContext(): Observable<UsersContext> = Observable
        .combineLatest(
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getPresences().observeAllPresences(),
            StoreStream.getUserRelationships().observe(),
        ) { users, presences, relationships -> UsersContext(users, presences, relationships) }
        .leadingEdgeThrottle(CONTEXT_THROTTLE_SECONDS, TimeUnit.SECONDS)

    private fun guildsRail(): Observable<WidgetGlobalSearchGuildsModel> = Observable
        .combineLatest(
            StoreStream.getGuildSelected().observeRecentSelectedGuildIds(),
            StoreStream.getGuildSelected().observeSelectedGuildId(),
            StoreStream.getReadStates().getUnreadGuildIds(),
            StoreStream.getMentions().observeMentionCounts(),
            StoreStream.getChannels().observePrivateChannels(),
            StoreStream.getGuilds().observeGuilds(),
            StoreStream.getChannels().observeIds(),
        ) { recent, selected, unreadGuilds, mentions, privateChannels, guilds, channelIds ->
            WidgetGlobalSearchGuildsModel.create(
                recentGuildIds = recent,
                selectedGuildId = selected,
                unreadGuildIds = unreadGuilds,
                mentionCounts = mentions,
                privateChannels = privateChannels,
                guilds = guilds,
                channelIdsByGuild = channelIds,
            )
        }
        .leadingEdgeThrottle(CONTEXT_THROTTLE_SECONDS, TimeUnit.SECONDS)

    // endregion

    /**
     * Builds and ranks the result list.
     *
     * Matching is two-pass per candidate: a literal substring first, then a fuzzy fallback. The
     * fallback keeps "vc" matching "voice-chat" while the scorer still buries it beneath anything
     * that matched literally.
     */
    private fun create(
        rawFilter: String,
        channelContext: ChannelContext,
        usersContext: UsersContext,
        guildsList: WidgetGlobalSearchGuildsModel,
    ): WidgetGlobalSearchModel {
        val trimmed = rawFilter.trim()
        val prefix = trimmed.firstOrNull()?.takeIf { it in SEARCH_PREFIXES }
        val searchType = when (prefix) {
            MENTION_PREFIX -> WidgetGlobalSearchModel.SEARCH_TYPE_USER
            CHANNEL_PREFIX -> WidgetGlobalSearchModel.SEARCH_TYPE_TEXT_CHANNEL
            VOICE_PREFIX -> WidgetGlobalSearchModel.SEARCH_TYPE_VOICE_CHANNEL
            GUILD_PREFIX -> WidgetGlobalSearchModel.SEARCH_TYPE_GUILD
            else -> WidgetGlobalSearchModel.SEARCH_TYPE_GENERAL
        }
        val filter = if (prefix != null) trimmed.drop(1).trim() else trimmed

        val results = ArrayList<WidgetGlobalSearchModel.ItemDataPayload>()

        if (searchType.matchesChannels) {
            channelContext.channels.values
                .filter { it.matchesSearchType(searchType) }
                .forEach { channel ->
                    val label = channel.getDisplayName()
                    val matched = label.match(filter) ?: return@forEach
                    results += WidgetGlobalSearchModel.ItemChannel(
                        matchedResult = matched,
                        channel = channel,
                        parentChannel = channelContext.channels[channel.parentId],
                        guild = channelContext.guilds[channel.guildId],
                        mentions = channelContext.mentionCounts[channel.id] ?: 0,
                        unread = channel.id in channelContext.unreadChannelIds,
                        presence = channel.dmRecipientId()?.let { usersContext.presences[it] },
                    )
                }
        }

        if (searchType == WidgetGlobalSearchModel.SEARCH_TYPE_GENERAL ||
            searchType == WidgetGlobalSearchModel.SEARCH_TYPE_GUILD
        ) {
            channelContext.guilds.values.forEach { guild ->
                val matched = guild.name.match(filter) ?: return@forEach
                val channelIds = channelContext.channelIdsByGuild[guild.id]
                results += WidgetGlobalSearchModel.ItemGuild(
                    matchedResult = matched,
                    guild = guild,
                    mentions = WidgetGlobalSearchGuildsModel.mentionCount(channelIds, channelContext.mentionCounts),
                    unread = channelIds?.any(channelContext.unreadChannelIds::contains) == true,
                )
            }
        }

        if (searchType == WidgetGlobalSearchModel.SEARCH_TYPE_GENERAL ||
            searchType == WidgetGlobalSearchModel.SEARCH_TYPE_USER
        ) {
            val dmChannelsByRecipient = channelContext.channels.values
                .filter { it.type == Channel.DM }
                .mapNotNull { channel -> channel.dmRecipientId()?.let { it to channel } }
                .toMap()

            usersContext.users.values.forEach { user ->
                val matched = user.username.match(filter) ?: return@forEach
                val dmChannel = dmChannelsByRecipient[user.id]
                results += WidgetGlobalSearchModel.ItemUser(
                    matchedResult = matched,
                    user = user,
                    aliases = emptyList(),
                    isFriend = usersContext.relationships[user.id] ==
                        ModelUserRelationship.TYPE_FRIEND,
                    presence = usersContext.presences[user.id],
                    channel = dmChannel,
                    mentions = dmChannel?.let { channelContext.mentionCounts[it.id] } ?: 0,
                    unread = dmChannel?.id in channelContext.unreadChannelIds,
                )
            }
        }

        val frecencyChannels = frecencyTracker.getSortedKeys()
        val ranked = results
            .sortedByDescending { it.score(searchType, frecencyChannels) }
            .take(WidgetGlobalSearchModel.MAX_RESULTS)

        return WidgetGlobalSearchModel(filter, searchType, withHeaders(ranked), guildsList.items)
    }

    /** Groups the ranked list under one header per kind, preserving rank inside each group. */
    private fun withHeaders(
        ranked: List<WidgetGlobalSearchModel.ItemDataPayload>,
    ): List<WidgetGlobalSearchModel.ItemDataPayload> {
        val rows = ArrayList<WidgetGlobalSearchModel.ItemDataPayload>(ranked.size + 3)
        var previousType: Int? = null
        ranked.forEach { item ->
            if (item.type != previousType) {
                val header = when (item.type) {
                    WidgetGlobalSearchModel.TYPE_CHANNEL -> R.string.channels
                    WidgetGlobalSearchModel.TYPE_GUILD -> R.string.servers
                    WidgetGlobalSearchModel.TYPE_USER -> R.string.users
                    else -> null
                }
                if (header != null) rows += WidgetGlobalSearchModel.ItemHeader(header)
                previousType = item.type
            }
            rows += item
        }
        return rows
    }

    private fun String.match(filter: String): WidgetGlobalSearchModel.MatchedResult? =
        if (filter.isEmpty()) {
            WidgetGlobalSearchModel.MatchedResult(this, 0, 0)
        } else {
            toMatchedResult(filter) ?: toFuzzyMatchedResult(filter)
        }

    private fun Channel.dmRecipientId(): Long? =
        if (type == Channel.DM) recipients?.firstOrNull()?.id else null

    private fun Channel.matchesSearchType(searchType: Int): Boolean = when (searchType) {
        WidgetGlobalSearchModel.SEARCH_TYPE_TEXT_CHANNEL -> !isGuildVocalChannel()
        WidgetGlobalSearchModel.SEARCH_TYPE_VOICE_CHANNEL -> isGuildVocalChannel()
        else -> type != Channel.CATEGORY
    }

    private val Int.matchesChannels: Boolean
        get() = this == WidgetGlobalSearchModel.SEARCH_TYPE_GENERAL ||
            this == WidgetGlobalSearchModel.SEARCH_TYPE_TEXT_CHANNEL ||
            this == WidgetGlobalSearchModel.SEARCH_TYPE_VOICE_CHANNEL

    companion object {
        const val MENTION_PREFIX = '@'
        const val CHANNEL_PREFIX = '#'
        const val VOICE_PREFIX = '!'
        const val GUILD_PREFIX = '*'

        private val SEARCH_PREFIXES =
            setOf(MENTION_PREFIX, CHANNEL_PREFIX, VOICE_PREFIX, GUILD_PREFIX)

        private const val FILTER_THROTTLE_MS = 250L
        private const val CONTEXT_THROTTLE_SECONDS = 3L
    }
}
