package com.discord.models.domain

import com.discord.utilities.time.ClockFactory

/** Wire shape of `channels/{id}/follower-stats`. */
data class ModelChannelFollowerStatsDto(
    val guildsFollowing: Int?,
    val channelsFollowing: Int?,
    val guildMembers: Int?,
    val usersSeenEver: Int?,
    val subscribersGainedSinceLastPost: Int?,
    val subscribersLostSinceLastPost: Int?,
)

/**
 * Cached follower statistics for an announcement channel. [lastFetched] is stamped at construction
 * so the store can expire the entry without a second timestamp field.
 */
data class ModelChannelFollowerStats(
    val lastFetched: Long = ClockFactory.get().currentTimeMillis(),
    val channelsFollowing: Int? = 0,
    val guildMembers: Int? = 0,
    val guildsFollowing: Int? = 0,
    val usersSeenEver: Int? = 0,
    val subscribersGainedSinceLastPost: Int? = 0,
    val subscribersLostSinceLastPost: Int? = 0,
) {
    companion object {
        fun fromResponse(response: ModelChannelFollowerStatsDto?): ModelChannelFollowerStats =
            if (response == null) {
                ModelChannelFollowerStats()
            } else {
                ModelChannelFollowerStats(
                    channelsFollowing = response.channelsFollowing,
                    guildMembers = response.guildMembers,
                    guildsFollowing = response.guildsFollowing,
                    usersSeenEver = response.usersSeenEver,
                    subscribersGainedSinceLastPost = response.subscribersGainedSinceLastPost,
                    subscribersLostSinceLastPost = response.subscribersLostSinceLastPost,
                )
            }
    }
}
