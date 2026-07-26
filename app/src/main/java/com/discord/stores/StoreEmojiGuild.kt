package com.discord.stores

import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The emoji list behind *server settings → Emoji*, which is a different shape from
 * [StoreEmojiCustom].
 *
 * The gateway only ever sends the emoji themselves; the settings screen additionally needs the
 * uploader for each one, which only `GET /guilds/{id}/emojis` returns. So this store fetches
 * [ModelEmojiGuild] over REST and keeps it for exactly one guild at a time — the one the settings
 * screen has [activate]d. A `GUILD_EMOJIS_UPDATE` for any other guild is ignored, because nothing
 * is displaying it.
 */
class StoreEmojiGuild(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val dispatcher: Dispatcher,
    private val api: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val guildEmoji = LinkedHashMap<Long, List<ModelEmojiGuild>>()
    private var guildEmojiSnapshot: Map<Long, List<ModelEmojiGuild>> = emptyMap()

    /** [NO_ACTIVE_GUILD] while the settings screen is closed. */
    private var activeGuildId: Long = NO_ACTIVE_GUILD

    /**
     * `null` means "never fetched", which is what tells the settings screen to show its loading
     * state rather than an empty list.
     */
    fun getGuildEmoji(guildId: Long): List<ModelEmojiGuild>? = guildEmojiSnapshot[guildId]

    fun observeGuildEmoji(guildId: Long): Observable<List<ModelEmojiGuild>?> =
        observationDeck.connectRx(this) { getGuildEmoji(guildId) }.distinctUntilChanged()

    fun activate(guildId: Long) = dispatcher.schedule { activeGuildId = guildId }

    fun deactivate() = dispatcher.schedule { activeGuildId = NO_ACTIVE_GUILD }

    fun fetchGuildEmoji(guildId: Long) {
        val api = api() ?: return
        api.getGuildEmojis(guildId)
            .restSubscribeOn()
            .appSubscribe(StoreEmojiGuild::class.java) { emojis ->
                handleGuildEmojisLoaded(guildId, emojis)
            }
    }

    /**
     * Removes the emoji locally rather than waiting for `GUILD_EMOJIS_UPDATE`, so the row
     * disappears the moment the delete call is made. The gateway event that follows re-fetches and
     * confirms it.
     */
    fun deleteEmoji(guildId: Long, emojiId: Long) = dispatcher.schedule {
        guildEmoji[guildId] = guildEmoji[guildId].orEmpty().filter { it.id != emojiId }
        markChanged()
    }

    @StoreThread
    fun handleEmojiUpdate(emojiUpdate: GuildEmojisUpdate) {
        if (emojiUpdate.guildId != activeGuildId) return
        fetchGuildEmoji(activeGuildId)
    }

    override fun snapshotData() {
        super.snapshotData()
        guildEmojiSnapshot = HashMap(guildEmoji)
    }

    @StoreThread
    internal fun reset() {
        guildEmoji.clear()
        activeGuildId = NO_ACTIVE_GUILD
        markChanged()
    }

    private fun handleGuildEmojisLoaded(guildId: Long, emojis: List<ModelEmojiGuild>) =
        dispatcher.schedule {
            guildEmoji[guildId] = emojis
            markChanged()
        }

    private companion object {
        const val NO_ACTIVE_GUILD = -1L
    }
}
