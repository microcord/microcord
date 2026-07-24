package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import rx.Observable

class StoreChannelsSelected(
    private val channels: StoreChannels,
    private val permissions: StorePermissions,
    private val guildSelected: StoreGuildSelected,
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) : StoreV2() {
    private val selections = HashMap<Long, Long>()
    private var selectedChannel: Channel? = null
    private var selectedIdSnapshot = UNSELECTED
    private var previousIdSnapshot = UNSELECTED
    private val selectionsCache = Persister(CACHE_KEY_SELECTED_CHANNEL_IDS, emptyMap<Long, Long>())

    override fun init(context: Context) = restore()

    internal fun restore() {
        selections.putAll(selectionsCache.get())
        markChanged()
    }

    fun getId(): Long = selectedIdSnapshot
    fun getSelectedChannel(): Channel? = selectedChannel
    fun observeId(): Observable<Long> = observationDeck.connectRx(this, generator = ::getId).distinctUntilChanged()
    fun observePreviousId(): Observable<Long> =
        observationDeck.connectRx(this, generator = { previousIdSnapshot }).distinctUntilChanged()
    fun observeSelectedChannel(): Observable<Channel?> =
        observationDeck.connectRx(this, generator = ::getSelectedChannel).distinctUntilChanged()

    fun trySelectChannel(guildId: Long, channelId: Long) = dispatcher.schedule { handleChannelSelected(guildId, channelId) }

    @StoreThread
    internal fun handleChannelSelected(guildId: Long, channelId: Long) {
        selections[guildId] = channelId
        resolve(guildId)
    }

    fun handleConnectionOpen(payload: ModelPayload) {
        val validGuilds = payload.guilds.orEmpty().mapTo(HashSet()) { it.id }.apply { add(PRIVATE_CHANNELS_ID) }
        selections.keys.retainAll(validGuilds)
        if (PRIVATE_CHANNELS_ID !in selections) selections[PRIVATE_CHANNELS_ID] = UNSELECTED
        resolve(guildSelected.getSelectedGuildIdInternal())
    }

    fun handleGuildSelected() = resolve(guildSelected.getSelectedGuildIdInternal())
    fun handleChannelOrThreadCreateOrUpdate(channel: Channel) {
        if (channel.guildId == guildSelected.getSelectedGuildIdInternal()) resolve(channel.guildId)
    }
    fun handlePermissionsChanged(guildId: Long) {
        if (guildId == guildSelected.getSelectedGuildIdInternal()) resolve(guildId)
    }
    fun handleChannelOrThreadDelete(channel: Channel) {
        if (selectedChannel?.id == channel.id) resolve(guildSelected.getSelectedGuildIdInternal())
    }
    fun handleGuildRemove(guildId: Long) {
        selections.remove(guildId)
        if (guildSelected.getSelectedGuildIdInternal() == guildId) resolve(PRIVATE_CHANNELS_ID)
    }

    internal fun reset() {
        selections.clear()
        selectedChannel = null
        selectedIdSnapshot = UNSELECTED
        previousIdSnapshot = UNSELECTED
        selectionsCache.clear()
        markChanged()
    }

    override fun snapshotData() {
        selectionsCache.set(selections.toMap(), persist = true)
    }

    private fun resolve(guildId: Long) {
        val channelPermissions = permissions.getPermissionsByChannelInternal(guildId)
        val selected = selections[guildId]?.let(channels::findChannelById)
            ?.takeIf { it.guildId == guildId && PermissionUtils.hasAccess(it, channelPermissions[it.id]) }
            ?: if (guildId == PRIVATE_CHANNELS_ID) null else channels.getChannelsByGuildInternal()[guildId]
                .orEmpty().values
                .filter { it.type != Channel.CATEGORY && PermissionUtils.hasAccess(it, channelPermissions[it.id]) }
                .minWithOrNull(compareBy<Channel> { it.position }.thenBy { it.id })
        val nextId = selected?.id ?: UNSELECTED
        if (nextId != selectedIdSnapshot) previousIdSnapshot = selectedIdSnapshot
        selectedChannel = selected
        selectedIdSnapshot = nextId
        if (selected != null) selections[guildId] = selected.id
        markChanged()
    }

    companion object {
        const val CACHE_KEY_SELECTED_CHANNEL_IDS = "CACHE_KEY_SELECTED_CHANNEL_IDS"
        const val PRIVATE_CHANNELS_ID = 0L
        const val UNSELECTED = 0L
    }
}
