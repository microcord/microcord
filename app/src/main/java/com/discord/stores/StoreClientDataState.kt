package com.discord.stores

import android.util.Log
import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.guild.Guild
import com.discord.api.message.Message
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.gateway.io.IdentifyClientState
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.cache.SharedPreferencesProvider
import com.google.gson.Gson
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
    private val cache: Persister<ClientDataState> = Persister(CACHE_KEY, ClientDataState()),
) : StoreV2() {

    /**
     * Immutable snapshot handed to IDENTIFY. Defaults match a client that knows nothing, which is
     * also the correct state for a fresh login.
     */
    data class ClientDataState(
        val guildVersions: Map<Long, Long> = emptyMap(),
        val highestLastMessageId: Long = 0L,
        val readStateVersion: Int = -1,
        val userGuildSettingsVersion: Int = -1,
        val privateChannelsVersion: Long? = null,
        val apiCodeVersion: Int? = null,
        val initialGuildId: Long? = null,
    ) {
        /**
         * [userSettingsVersion] is not cached here: the settings proto owns it, and omitting it
         * only costs a copy of the proto in READY.
         */
        fun toIdentifyData(userSettingsVersion: Int? = null): IdentifyClientState = IdentifyClientState(
            guildVersions = guildVersions,
            highestLastMessageId = highestLastMessageId,
            readStateVersion = readStateVersion,
            userGuildSettingsVersion = userGuildSettingsVersion,
            userSettingsVersion = userSettingsVersion,
            privateChannelsVersion = privateChannelsVersion,
            initialGuildId = initialGuildId,
            apiCodeVersion = apiCodeVersion,
        )
    }

    private val guildVersions = HashMap<Long, Long>()
    private var highestLastMessageId = 0L
    private var readStateVersion = -1
    private var userGuildSettingsVersion = -1
    private var privateChannelsVersion: Long? = null
    private var apiCodeVersion: Int? = null
    private var initialGuildId: Long? = null
    private var pendingFastConnectCommit = false

    private var clientDataStateSnapshot = ClientDataState()

    /**
     * Restores the cache written by the previous process, so the first IDENTIFY after a cold start
     * can still ask the gateway to skip guilds this client already has.
     */
    @StoreThread
    fun init() {
        val restored = cache.get()
        guildVersions.putAll(restored.guildVersions)
        highestLastMessageId = restored.highestLastMessageId
        readStateVersion = restored.readStateVersion
        userGuildSettingsVersion = restored.userGuildSettingsVersion
        privateChannelsVersion = restored.privateChannelsVersion
        apiCodeVersion = restored.apiCodeVersion
        initialGuildId = restored.initialGuildId
        markChanged()
    }

    fun getClientDataState(): ClientDataState = clientDataStateSnapshot

    fun observeClientState(): Observable<ClientDataState> =
        observationDeck.connectRx(this, generator = ::getClientDataState)

    override fun snapshotData() {
        super.snapshotData()
        clientDataStateSnapshot = currentState()
        // Marked dirty only: snapshotData runs after every dispatch that touched this store,
        // and Persister.persist() is a synchronous Kryo write. The strategy flush picks it up.
        cache.set(clientDataStateSnapshot)
    }

    private fun currentState() = ClientDataState(
            HashMap(guildVersions),
            highestLastMessageId,
            readStateVersion,
            userGuildSettingsVersion,
            privateChannelsVersion,
            apiCodeVersion,
            initialGuildId,
        )

    @StoreThread
    fun clear(persist: Boolean = true) {
        pendingFastConnectCommit = false
        guildVersions.clear()
        highestLastMessageId = 0L
        readStateVersion = -1
        userGuildSettingsVersion = -1
        privateChannelsVersion = null
        apiCodeVersion = null
        cache.clear(persist = persist)
        if (persist) runCatching {
            SharedPreferencesProvider.get().edit().remove(FAST_CONNECT_CACHE_KEY).commit()
        }
        markChanged()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        // A guild that came back unavailable told us nothing about itself, so its version carries
        // over; dropping it would ask for the whole guild again the moment it comes back.
        val retained = HashMap(guildVersions)
        clear(persist = false)
        payload.guilds.orEmpty().forEach { guild ->
            if (guild.unavailable) {
                retained[guild.id]?.let { guildVersions[guild.id] = it }
                return@forEach
            }
            updateGuildVersion(guild)
            guild.channels.orEmpty().forEach { channel -> updateHighestLastMessageId(channel.lastMessageId) }
        }
        payload.privateChannels.orEmpty().forEach { channel -> updatePrivateChannelsVersion(channel.lastMessageId) }
        payload.readState?.let { readStateVersion = it.version }
        payload.userGuildSettings?.let { userGuildSettingsVersion = it.version }
        apiCodeVersion = payload.apiCodeVersion
        pendingFastConnectCommit = true
        markChanged()
    }

    /**
     * Remembers which guild to ask the gateway to send ahead of READY.
     *
     * Only a real guild qualifies: 0 is the DM pseudo-guild, and asking for it would spend the one
     * early guild slot on something the gateway cannot send.
     */
    @StoreThread
    fun handleGuildSelected(guildId: Long) {
        val selected = guildId.takeIf { it > 0L }
        if (selected == initialGuildId) return
        initialGuildId = selected
        markChanged()
    }

    @StoreThread
    fun handleGuildAdd(guild: Guild) {
        updateGuildVersion(guild)
        markChanged()
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) {
        guildVersions.remove(guild.id)
        markChanged()
    }

    @StoreThread
    fun handleChannelCreateOrUpdateOrDelete(channel: Channel) {
        if (channel.guildId == 0L) updatePrivateChannelsVersion(channel.lastMessageId)
        else updateVersion(channel.guildId, channel.version)
        markChanged()
    }

    @StoreThread
    fun commitFastConnectStateIfPending() {
        if (!pendingFastConnectCommit || !Persister.persist(REQUIRED_CACHE_KEYS)) return
        val committed = runCatching {
            persistFastConnectState(SharedPreferencesProvider.get(), clientDataStateSnapshot)
        }.getOrDefault(false)
        if (committed) pendingFastConnectCommit = false
    }

    @StoreThread
    fun handleGuildEntityVersion(guildId: Long, version: Long) {
        updateVersion(guildId, version)
        markChanged()
    }

    @StoreThread
    fun handleEmojiUpdate(payload: GuildEmojisUpdate) {
        payload.emojis.forEach { updateVersion(payload.guildId, it.version) }
        markChanged()
    }

    @StoreThread
    fun handleStickersUpdate(payload: GuildStickersUpdate) {
        payload.stickers.forEach { updateVersion(payload.guildId, it.version) }
        markChanged()
    }

    @StoreThread
    fun handleMessageCreate(message: Message) {
        if (message.guildId == null) updatePrivateChannelsVersion(message.id)
        else updateHighestLastMessageId(message.id)
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
    private fun updateGuildVersion(guild: Guild) {
        if (guild.unavailable) return
        updateVersion(guild.id, guild.version)
        guild.roles.orEmpty().forEach { updateVersion(guild.id, it.version) }
        guild.emojis.orEmpty().forEach { updateVersion(guild.id, it.version) }
        guild.stickers.orEmpty().forEach { updateVersion(guild.id, it.version) }
        guild.channels.orEmpty().forEach { updateVersion(guild.id, it.version) }
    }

    private fun updateVersion(guildId: Long, version: Long) {
        if (version > guildVersions.getOrDefault(guildId, 0L)) guildVersions[guildId] = version
    }

    @StoreThread
    private fun updateHighestLastMessageId(newMessageId: Long) {
        if (newMessageId > highestLastMessageId) highestLastMessageId = newMessageId
    }

    private fun updatePrivateChannelsVersion(newMessageId: Long) {
        if (newMessageId > (privateChannelsVersion ?: 0L)) privateChannelsVersion = newMessageId
    }

    companion object {
        const val CACHE_KEY = "STORE_CLIENT_DATA_STATE_V2"
        private const val FAST_CONNECT_CACHE_KEY = "STORE_CLIENT_DATA_STATE_V2_FAST_CONNECT_TX2"
        private val REQUIRED_CACHE_KEYS = setOf(
            CACHE_KEY,
            StoreGuilds.GUILDS_CACHE_KEY,
            StoreGuilds.GUILD_ROLES_CACHE_KEY,
            StoreChannels.CHANNELS_CACHE_KEY,
            StoreMessageAck.ACKS_CACHE_KEY,
            StoreMentions.MENTION_COUNTS_CACHE_KEY,
            StoreUserGuildSettings.GUILD_SETTINGS_CACHE_KEY,
        )
        private val gson = Gson()

        fun readFastConnectState(preferences: SharedPreferences): ClientDataState? =
            preferences.getString(FAST_CONNECT_CACHE_KEY, null)?.let { json ->
                runCatching { gson.fromJson(json, ClientDataState::class.java) }
                    .onFailure { Log.w("ManticordGateway", "fast connect mirror read failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }

        private fun persistFastConnectState(preferences: SharedPreferences, state: ClientDataState): Boolean =
            preferences.edit().putString(FAST_CONNECT_CACHE_KEY, gson.toJson(state)).commit()
    }
}
