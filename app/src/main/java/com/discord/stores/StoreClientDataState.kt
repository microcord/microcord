package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.guild.Guild
import com.discord.api.guildhash.GuildHashes
import com.discord.api.message.Message
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.gateway.io.IdentifyClientState
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Tracks everything the client already knows, so IDENTIFY can ask the gateway to skip it.
 *
 * The gateway lets a client send per-guild content hashes plus the highest message id, read-state
 * version and user-guild-settings version it has cached. Guilds whose hashes still match are
 * omitted from READY, which is what makes a warm reconnect cheap. Sending empty/`-1` values (the
 * previous placeholder) disables that optimisation entirely and forces a full READY every time.
 */
class StoreClientDataState(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    /**
     * Immutable snapshot handed to IDENTIFY. Defaults match a client that knows nothing, which is
     * also the correct state for a fresh login.
     */
    data class ClientDataState(
        val guildHashes: Map<Long, GuildHashes> = emptyMap(),
        val highestLastMessageId: Long = 0L,
        val readStateVersion: Int = -1,
        val userGuildSettingsVersion: Int = -1,
    ) {
        /**
         * Only guilds with a complete metadata/channel/role hash triple are worth sending; a
         * partial triple cannot be validated server-side and is dropped.
         */
        private fun GuildHashes.toHashTriple(): Array<String>? {
            val metadata = metadata?.hash ?: return null
            val channels = channels?.hash ?: return null
            val roles = roles?.hash ?: return null
            return arrayOf(metadata, channels, roles)
        }

        fun toIdentifyData(): IdentifyClientState = IdentifyClientState(
            guildHashes = guildHashes.mapNotNull { (guildId, hashes) ->
                hashes.toHashTriple()?.let { guildId to it }
            }.toMap(),
            highestLastMessageId = highestLastMessageId,
            readStateVersion = readStateVersion,
            userGuildSettingsVersion = userGuildSettingsVersion,
        )
    }

    private val guildHashes = HashMap<Long, GuildHashes>()
    private var highestLastMessageId = 0L
    private var readStateVersion = -1
    private var userGuildSettingsVersion = -1

    private var clientDataStateSnapshot = ClientDataState()

    fun getClientDataState(): ClientDataState = clientDataStateSnapshot

    fun observeClientState(): Observable<ClientDataState> =
        observationDeck.connectRx(this, generator = ::getClientDataState)

    override fun snapshotData() {
        super.snapshotData()
        clientDataStateSnapshot = ClientDataState(
            HashMap(guildHashes),
            highestLastMessageId,
            readStateVersion,
            userGuildSettingsVersion,
        )
    }

    @StoreThread
    fun clear() {
        guildHashes.clear()
        highestLastMessageId = 0L
        readStateVersion = 0
        userGuildSettingsVersion = -1
        markChanged()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        clear()
        payload.guilds.orEmpty().forEach { guild ->
            updateGuildHash(guild)
            guild.channels.orEmpty().forEach { channel -> updateHighestLastMessageId(channel.lastMessageId) }
        }
        payload.privateChannels.orEmpty().forEach { channel -> updateHighestLastMessageId(channel.lastMessageId) }
        payload.readState?.let { readStateVersion = it.version }
        payload.userGuildSettings?.let { userGuildSettingsVersion = it.version }
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        updateGuildHash(guild)
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) {
        guildHashes.remove(guild.id)
        markChanged()
    }

    @StoreThread
    fun handleChannelCreateOrUpdateOrDelete(channel: Channel) {
        val hashes = channel.guildHashes ?: return
        if (!hashes.isSupported) return
        guildHashes[channel.guildId] = hashes
        markChanged()
    }

    @StoreThread
    fun handleRoleAddOrRemove(guildId: Long, guildHashes: GuildHashes?) {
        if (guildHashes == null || !guildHashes.isSupported) return
        this.guildHashes[guildId] = guildHashes
        markChanged()
    }

    @StoreThread
    fun handleEmojiUpdate(payload: GuildEmojisUpdate) {
        if (!payload.guildHashes.isSupported) return
        guildHashes[payload.guildId] = payload.guildHashes
        markChanged()
    }

    @StoreThread
    fun handleStickersUpdate(payload: GuildStickersUpdate) {
        if (!payload.guildHashes.isSupported) return
        guildHashes[payload.guildId] = payload.guildHashes
        markChanged()
    }

    @StoreThread
    fun handleMessageCreate(message: Message) {
        updateHighestLastMessageId(message.id)
        markChanged()
    }

    @StoreThread
    fun handleMessageAck(readState: ModelReadState) {
        if (readState.version <= readStateVersion) return
        readStateVersion = readState.version
        markChanged()
    }

    @StoreThread
    fun handleGuildSettingUpdated(userGuildSettings: ModelNotificationSettings) {
        if (userGuildSettings.version <= userGuildSettingsVersion) return
        userGuildSettingsVersion = userGuildSettings.version
        markChanged()
    }

    @StoreThread
    private fun updateGuildHash(guild: Guild) {
        val hashes = guild.guildHashes
        if (!guild.unavailable && hashes != null && hashes.isSupported) {
            guildHashes[guild.id] = hashes
        } else {
            guildHashes.remove(guild.id)
        }
    }

    @StoreThread
    private fun updateHighestLastMessageId(newMessageId: Long) {
        if (newMessageId > highestLastMessageId) highestLastMessageId = newMessageId
    }

    private companion object {
        /** The gateway only honours version 1 of the guild-hash contract. */
        const val SUPPORTED_GUILD_HASH_VERSION = 1

        val GuildHashes?.isSupported: Boolean
            get() = this?.version == SUPPORTED_GUILD_HASH_VERSION
    }
}
