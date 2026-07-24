package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject

class StoreUserGuildSettings(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    private val getChannel: (Long) -> Channel? = { null },
    private val getJoinedThreadFlags: (Long) -> Int = { 0 },
    private val updateGuildSettings: (Long, UserGuildSettingsUpdate) -> Observable<ModelNotificationSettings> =
        { _, _ -> Observable.empty() },
    private val updateThreadSettings: (Long, ThreadMemberSettingsUpdate) -> Observable<Unit> =
        { _, _ -> Observable.empty() },
    private val trackSettingsUpdated: (SettingsUpdateType, Long?) -> Unit = { _, _ -> },
    private val guildSettingsCache: Persister<Map<Long, ModelNotificationSettings>> =
        Persister(GUILD_SETTINGS_CACHE_KEY, emptyMap()),
    private val hiddenMutedChannelsCache: Persister<Set<Long>> =
        Persister(HIDDEN_MUTED_CHANNELS_CACHE_KEY, emptySet()),
) : StoreV2() {
    enum class SettingsUpdateType { GUILD, CHANNEL, THREAD, CATEGORY }

    sealed class Event {
        data class SettingsUpdated(val type: SettingsUpdateType) : Event()
    }

    data class ChannelOverrideUpdate(
        val muted: Boolean? = null,
        val muteConfig: ModelMuteConfig? = null,
        val messageNotifications: Int? = null,
        val flags: Int? = null,
    )

    data class UserGuildSettingsUpdate(
        val suppressEveryone: Boolean? = null,
        val suppressRoles: Boolean? = null,
        val muted: Boolean? = null,
        val muteConfig: ModelMuteConfig? = null,
        val mobilePush: Boolean? = null,
        val messageNotifications: Int? = null,
        val channelOverrides: Map<Long, ChannelOverrideUpdate>? = null,
        val notifyHighlights: Int? = null,
    )

    data class ThreadMemberSettingsUpdate(
        val flags: Int? = null,
        val muted: Boolean? = null,
        val muteConfig: ModelMuteConfig? = null,
    )

    private val guildSettings = HashMap<Long, ModelNotificationSettings>()
    private val guildsToHideMutedChannelsIn = HashSet<Long>()
    private var guildSettingsSnapshot: Map<Long, ModelNotificationSettings> = emptyMap()
    private var guildsToHideMutedChannelsInSnapshot: Set<Long> = emptySet()
    private val eventSubject = PublishSubject.create<Event>()
    private var recomputeSettingsSubscription: Subscription? = null

    override fun init(context: Context) = restore()

    internal fun restore() {
        guildSettings.putAll(guildSettingsCache.get())
        guildsToHideMutedChannelsIn.addAll(hiddenMutedChannelsCache.get())
        markChanged()
        scheduleMuteRecompute()
    }

    fun getGuildSettings(): Map<Long, ModelNotificationSettings> = guildSettingsSnapshot
    internal fun getGuildSettingsInternal(): Map<Long, ModelNotificationSettings> = guildSettings
    fun getGuildsToHideMutedChannelsIn(): Set<Long> = guildsToHideMutedChannelsInSnapshot

    fun observeEvents(): Observable<Event> = eventSubject
    fun observeGuildSettings(): Observable<Map<Long, ModelNotificationSettings>> =
        observationDeck.connectRx(this, generator = ::getGuildSettings).distinctUntilChanged()
    fun observeGuildSettings(guildId: Long): Observable<ModelNotificationSettings?> =
        observeGuildSettings().map { it[guildId] }.distinctUntilChanged()
    fun observeHideMutedChannels(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::getGuildsToHideMutedChannelsIn)
            .map { guildId in it }.distinctUntilChanged()
    fun observeMutedGuildIds(): Observable<List<Long>> = observeGuildSettings()
        .map { settings -> settings.values.filter(ModelNotificationSettings::isMuted).map { it.guildId } }
        .distinctUntilChanged()

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        val versioned = payload.userGuildSettings ?: return
        if (!versioned.isPartial) guildSettings.clear()
        handleGuildSettings(versioned.entries.orEmpty())
        markChanged()
    }

    @StoreThread
    fun handleGuildSettingUpdated(settings: List<ModelNotificationSettings>) = handleGuildSettings(settings)

    @StoreThread
    private fun handleGuildSettings(settings: List<ModelNotificationSettings>) {
        StoreThreadScheduler.assertCurrentThread()
        recomputeSettingsSubscription?.unsubscribe()
        settings.forEach { setting ->
            val computed = setting.copyAndRecomputeTransientFields(clock)
            if (guildSettings[computed.guildId] != computed) {
                guildSettings[computed.guildId] = computed
                markChanged()
            }
        }
        scheduleMuteRecompute()
    }

    fun setHideMutedChannels(guildId: Long, hide: Boolean) = dispatcher.schedule {
        if (if (hide) guildsToHideMutedChannelsIn.add(guildId) else guildsToHideMutedChannelsIn.remove(guildId)) {
            markChanged()
        }
    }

    fun setChannelFrequency(context: Context, channel: Channel, frequency: Int) {
        if (channel.isThread()) {
            val flags = when (frequency) {
                ModelNotificationSettings.FREQUENCY_NOTHING -> 8
                ModelNotificationSettings.FREQUENCY_MENTIONS -> 4
                else -> 2
            }
            submitThreadUpdate(channel, ThreadMemberSettingsUpdate(flags = flags), getJoinedThreadFlags(channel.id))
        } else {
            submitGuildUpdate(
                channel.guildId,
                UserGuildSettingsUpdate(channelOverrides = mapOf(channel.id to ChannelOverrideUpdate(messageNotifications = frequency))),
                settingsUpdateType(channel),
                channel.id,
            )
        }
    }

    @JvmOverloads
    fun setChannelMuted(context: Context, channelId: Long, muted: Boolean, muteConfig: ModelMuteConfig? = null) =
        dispatcher.schedule {
            val channel = getChannel(channelId) ?: return@schedule
            if (channel.isThread()) {
                submitThreadUpdate(channel, ThreadMemberSettingsUpdate(muted = muted, muteConfig = muteConfig), getJoinedThreadFlags(channel.id))
            } else {
                submitGuildUpdate(
                    channel.guildId,
                    UserGuildSettingsUpdate(channelOverrides = mapOf(channel.id to ChannelOverrideUpdate(muted, muteConfig))),
                    settingsUpdateType(channel),
                    channel.id,
                )
            }
        }

    fun setChannelNotificationFlags(context: Context, channel: Channel, flags: Int) {
        if (channel.type != Channel.GUILD_FORUM) return
        submitGuildUpdate(
            channel.guildId,
            UserGuildSettingsUpdate(channelOverrides = mapOf(channel.id to ChannelOverrideUpdate(flags = flags))),
            settingsUpdateType(channel),
            channel.id,
        )
    }

    fun setChannelNotificationsDefault(context: Context, channel: Channel) {
        if (channel.isThread()) return
        submitGuildUpdate(
            channel.guildId,
            UserGuildSettingsUpdate(
                channelOverrides = mapOf(
                    channel.id to ChannelOverrideUpdate(
                        muted = false,
                        messageNotifications = ModelNotificationSettings.FREQUENCY_UNSET,
                    ),
                ),
            ),
            settingsUpdateType(channel),
            channel.id,
        )
    }

    fun setGuildFrequency(context: Context, guild: Guild, frequency: Int) = submitGuildUpdate(
        guild.id,
        UserGuildSettingsUpdate(messageNotifications = frequency),
        SettingsUpdateType.GUILD,
    )

    @JvmOverloads
    fun setGuildMuted(context: Context, guildId: Long, muted: Boolean, muteConfig: ModelMuteConfig? = null) =
        submitGuildUpdate(guildId, UserGuildSettingsUpdate(muted = muted, muteConfig = muteConfig), SettingsUpdateType.GUILD)

    fun setGuildToggles(
        context: Context,
        guild: Guild,
        suppressEveryone: Boolean? = null,
        suppressRoles: Boolean? = null,
        mobilePush: Boolean? = null,
        muted: Boolean? = null,
    ) = submitGuildUpdate(
        guild.id,
        UserGuildSettingsUpdate(suppressEveryone, suppressRoles, muted, mobilePush = mobilePush),
        SettingsUpdateType.GUILD,
    )

    fun setNotifyHighlights(context: Context, guild: Guild, notifyHighlights: Int) = submitGuildUpdate(
        guild.id,
        UserGuildSettingsUpdate(notifyHighlights = notifyHighlights),
        SettingsUpdateType.GUILD,
    )

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        recomputeSettingsSubscription?.unsubscribe()
        recomputeSettingsSubscription = null
        guildSettings.clear()
        guildsToHideMutedChannelsIn.clear()
        guildSettingsCache.clear(persist = true)
        hiddenMutedChannelsCache.clear(persist = true)
        markChanged()
    }

    override fun snapshotData() {
        guildSettingsSnapshot = guildSettings.toMap()
        guildsToHideMutedChannelsInSnapshot = guildsToHideMutedChannelsIn.toSet()
        guildSettingsCache.set(guildSettingsSnapshot)
        hiddenMutedChannelsCache.set(guildsToHideMutedChannelsInSnapshot, persist = true)
    }

    private fun submitGuildUpdate(
        guildId: Long,
        update: UserGuildSettingsUpdate,
        type: SettingsUpdateType,
        channelId: Long? = update.channelOverrides?.keys?.firstOrNull(),
    ) {
        updateGuildSettings(guildId, update).take(1).subscribe { settings ->
            dispatcher.schedule {
                handleGuildSettingUpdated(listOf(settings))
                trackSettingsUpdated(type, channelId)
                eventSubject.onNext(Event.SettingsUpdated(type))
            }
        }
    }

    private fun submitThreadUpdate(channel: Channel, update: ThreadMemberSettingsUpdate, oldFlags: Int) {
        updateThreadSettings(channel.id, update).take(1).subscribe {
            dispatcher.schedule {
                trackSettingsUpdated(SettingsUpdateType.THREAD, channel.id)
                eventSubject.onNext(Event.SettingsUpdated(SettingsUpdateType.THREAD))
            }
        }
    }

    private fun scheduleMuteRecompute() {
        val next = guildSettings.values.minOfOrNull { it.getNextMuteEndTimeMs(clock) } ?: Long.MAX_VALUE
        if (next == Long.MAX_VALUE) return
        recomputeSettingsSubscription = Observable.timer(
            (next - clock.currentTimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
            dispatcher.scheduler,
        ).subscribe { dispatcher.schedule { handleGuildSettings(guildSettings.values.toList()) } }
    }

    private fun settingsUpdateType(channel: Channel) = when {
        channel.isThread() -> SettingsUpdateType.THREAD
        channel.type == Channel.CATEGORY -> SettingsUpdateType.CATEGORY
        else -> SettingsUpdateType.CHANNEL
    }

    private fun Channel.isThread() = type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD

    companion object {
        const val GUILD_SETTINGS_CACHE_KEY = "STORE_SETTINGS_USER_GUILD_V6"
        const val HIDDEN_MUTED_CHANNELS_CACHE_KEY = "STORE_SHOW_HIDE_MUTED_CHANNELS_V2"
    }
}
