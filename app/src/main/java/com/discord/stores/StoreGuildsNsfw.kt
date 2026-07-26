package com.discord.stores

import android.content.Context
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import rx.Observable

/**
 * Which age-restricted guilds the user has agreed to enter.
 *
 * A guild flagged `nsfw` shows a full-screen gate before any of its channels render. Agreement is
 * per device and permanent, so it lives in [prefs] rather than in a [com.discord.utilities.persister.Persister] —
 * it must survive a logout, and it must be readable before any store has hydrated.
 *
 * Declining navigates away from the guild instead of merely dismissing the gate, otherwise the
 * user would be left looking at the channel list of a guild they just refused.
 */
class StoreGuildsNsfw(
    private val dispatcher: Dispatcher,
    private val storeChannels: StoreChannels,
    private val observationDeck: ObservationDeck,
    /** Wire to `StoreStream.selectChannel`; called with `(0, 0)` to leave the guild entirely. */
    private val selectChannel: (guildId: Long, channelId: Long) -> Unit = { _, _ -> },
) : StoreV2() {
    private val guildIdsAllowed = LinkedHashSet<Long>()
    private var guildIdsAllowedSnapshot: Set<Long> = emptySet()

    @StoreThread
    override fun init(context: Context) {
        guildIdsAllowed.addAll(prefs.getStringSet(GUILDS_ALLOWED_KEY, emptySet()).orEmpty().toGuildIdSet())
        markChanged()
    }

    fun isGuildNsfwGateAgreed(guildId: Long): Boolean = guildId in guildIdsAllowedSnapshot

    fun observeIsGuildNsfwGateAgreed(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(this) { isGuildNsfwGateAgreed(guildId) }

    fun allow(guildId: Long) = dispatcher.schedule {
        guildIdsAllowed.add(guildId)
        markChanged()
    }

    /**
     * Declines the gate. The user is moved to the guild's default channel when that channel is
     * itself safe; otherwise they are sent all the way back to the home surface.
     */
    fun deny(guildId: Long) {
        storeChannels.observeDefaultChannel(guildId)
            .takeSingleUntilTimeout()
            .appSubscribe(StoreGuildsNsfw::class.java) { channel ->
                if (channel != null && !channel.nsfw) {
                    selectChannel(channel.guildId, channel.id)
                } else {
                    selectChannel(0L, 0L)
                }
            }
    }

    @StoreThread
    override fun snapshotData() {
        guildIdsAllowedSnapshot = HashSet(guildIdsAllowed)
        prefs.edit().putStringSet(GUILDS_ALLOWED_KEY, guildIdsAllowed.toStringSet()).apply()
    }

    /** Ids are stored as strings because `SharedPreferences` has no long-set type. */
    private fun Set<String>.toGuildIdSet(): Set<Long> = mapNotNull(String::toLongOrNull).toSet()

    private fun Set<Long>.toStringSet(): Set<String> = map(Long::toString).toSet()

    private companion object {
        private const val GUILDS_ALLOWED_KEY = "GUILDS_ALLOWED_KEY"
    }
}
