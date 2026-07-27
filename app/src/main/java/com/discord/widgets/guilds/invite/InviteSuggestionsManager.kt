package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getSortByMostRecent
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.user.User
import com.discord.stores.StoreChannels
import com.discord.stores.StoreMessagesMostRecent
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserAffinities
import com.discord.stores.StoreUserRelationships
import rx.Observable

/**
 * Builds the ranked "who should I invite?" list.
 *
 * The ordering is not a simple concatenation, and every step below is load-bearing:
 *
 * 1. the single most recently active private channel goes first, so the top row is where the user
 *    was last talking;
 * 2. then affinity-ranked users, minus anyone who is the recipient of that first channel (it would
 *    be the same conversation twice) and minus blocked users;
 * 3. then every remaining private channel, minus any DM whose recipient already appeared as a user
 *    suggestion — again to avoid listing one person twice.
 *
 * `observeRecentMessageIds().take(1)` is the reference's: the ordering is fixed when the screen
 * opens, so a message arriving mid-session does not reshuffle rows under the user's finger.
 */
class InviteSuggestionsManager(
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeMessagesMostRecent: StoreMessagesMostRecent = StoreStream.getMessagesMostRecent(),
    private val storeUserAffinities: StoreUserAffinities = StoreStream.getUserAffinities(),
    private val storeUsers: StoreUser = StoreStream.getUsers(),
    private val storeUserRelationships: StoreUserRelationships = StoreStream.getUserRelationships(),
) {

    private data class UserAffinityData(
        val userIds: List<Long>,
        val users: Map<Long, User>,
        val relationships: Map<Long, Int>,
    )

    fun observeInviteSuggestions(): Observable<List<InviteSuggestion>> = Observable.combineLatest(
        storeChannels.observePrivateChannels(),
        storeMessagesMostRecent.observeRecentMessageIds().take(1),
        observeUserAffinityData(),
    ) { privateChannels, mostRecentMessages, affinity ->
        buildSuggestions(privateChannels, mostRecentMessages, affinity)
    }

    private fun observeUserAffinityData(): Observable<UserAffinityData> =
        storeUserAffinities.observeAffinityUserIds().switchMap { affinityUserIds ->
            Observable.combineLatest(
                storeUsers.observeUsers(affinityUserIds).distinctUntilChanged(),
                storeUserRelationships.observe(affinityUserIds),
            ) { users, relationships -> UserAffinityData(affinityUserIds, users, relationships) }
        }

    private fun buildSuggestions(
        privateChannels: Map<Long, Channel>,
        mostRecentMessages: Map<Long, Long>,
        affinity: UserAffinityData,
    ): List<InviteSuggestion> {
        val suggestions = mutableListOf<InviteSuggestion>()

        val channelsByRecency =
            privateChannels.values.sortedWith(Channel.getSortByMostRecent(mostRecentMessages))
        val mostRecentChannel = channelsByRecency.firstOrNull()
        if (mostRecentChannel != null) {
            suggestions += InviteSuggestion.ChannelItem(mostRecentChannel)
        }

        val mostRecentRecipient = mostRecentChannel?.getDMRecipient()
        val affinityUsers = affinity.userIds
            .mapNotNull { userId -> affinity.users[userId] }
            .filterNot { user ->
                mostRecentRecipient?.id == user.id ||
                    affinity.relationships[user.id] == ModelUserRelationship.TYPE_BLOCKED
            }
            .distinct()

        affinityUsers.forEach { user -> suggestions += InviteSuggestion.UserSuggestion(user) }

        channelsByRecency
            .filter { channel ->
                channel != mostRecentChannel &&
                    channel.getDMRecipient().let { it == null || it !in affinityUsers }
            }
            .forEach { channel -> suggestions += InviteSuggestion.ChannelItem(channel) }

        return suggestions
    }
}
