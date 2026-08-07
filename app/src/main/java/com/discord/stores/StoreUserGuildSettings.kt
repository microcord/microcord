package com.discord.stores

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isTextChannel
import com.discord.api.channel.isThread
import com.discord.api.notification.AccountNotificationFlags
import com.discord.api.notification.AccountNotificationSettings
import com.discord.api.notification.ChannelNotificationSettingsFlags
import com.discord.api.notification.GuildNotificationSettingsFlags
import com.discord.api.notification.UnreadSetting
import com.discord.utilities.channel.ChannelNotificationSettingsUtils
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject

/**
 * Per-guild notification settings: mute state, notification frequency, channel overrides and the
 * "hide muted channels" preference.
 *
 * Guild writes wait for the gateway echo. Private-channel overrides are optimistic because their
 * dedicated endpoint returns the complete private-channel settings object; a failed current write
 * restores the prior object, while a newer write or gateway update wins.
 */
class StoreUserGuildSettings(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck,
    /**
     * Resolve a channel on the store thread. Wired from `StoreChannels.findChannelByIdInternal`.
     */
    private val getChannel: (Long) -> Channel? = { null },
    /**
     * Current notification flags for a joined thread, for the analytics before/after pair.
     * Wired from `StoreThreadsJoined.getJoinedThread(id)?.flags`.
     */
    private val getJoinedThreadFlags: (Long) -> Int = { 0 },
    private val analytics: Analytics = Analytics.NONE,
    private val guildSettingsCache: Persister<Map<Long, ModelNotificationSettings>> =
        Persister(GUILD_SETTINGS_CACHE_KEY, emptyMap()),
    private val hiddenMutedChannelsCache: Persister<Set<Long>> =
        Persister(HIDDEN_MUTED_CHANNELS_CACHE_KEY, emptySet()),
    /** Cached so the new-unread behaviour is known before READY, rather than flipping on connect. */
    private val notificationFlagsCache: Persister<Int> = Persister(NOTIFICATION_FLAGS_CACHE_KEY, 0),
) : StoreV2() {

    enum class SettingsUpdateType { GUILD, CHANNEL, THREAD, CATEGORY }

    sealed class Event {
        data class SettingsUpdated(val type: SettingsUpdateType) : Event()
    }

    /**
     * The analytics surface this store needs. Separated from `StoreAnalytics` so that the settings
     * object and both flag values survive the call instead of being flattened into a label.
     */
    interface Analytics {
        fun onNotificationSettingsUpdated(settings: ModelNotificationSettings, channelId: Long?)
        fun onThreadNotificationSettingsUpdated(channelId: Long, parentChannelId: Long, flags: Int, oldFlags: Int)

        companion object {
            val NONE = object : Analytics {
                override fun onNotificationSettingsUpdated(settings: ModelNotificationSettings, channelId: Long?) = Unit
                override fun onThreadNotificationSettingsUpdated(
                    channelId: Long,
                    parentChannelId: Long,
                    flags: Int,
                    oldFlags: Int,
                ) = Unit
            }
        }
    }

    private val guildSettings = HashMap<Long, ModelNotificationSettings>()
    private val guildsToHideMutedChannelsIn = HashSet<Long>()
    private var guildSettingsSnapshot: Map<Long, ModelNotificationSettings> = emptyMap()
    private var guildsToHideMutedChannelsInSnapshot: Set<Long> = emptySet()
    private var accountSettings = AccountNotificationSettings()
    private var accountSettingsSnapshot = AccountNotificationSettings()
    private val eventSubject = PublishSubject.create<Event>()
    private var recomputeSettingsSubscription: Subscription? = null
    private val pendingPrivateWriteRevisions = HashMap<Long, Long>()
    private var nextPrivateWriteRevision = 0L

    /** Bound by `StoreStream` once the REST stack exists; every setter is inert until then. */
    private var restApi: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.restApi = api
    }

    // -- Lifecycle ------------------------------------------------------------------------------

    @StoreThread
    override fun init(context: Context) = restore()

    @StoreThread
    internal fun restore() {
        guildSettings.putAll(guildSettingsCache.get())
        guildsToHideMutedChannelsIn.addAll(hiddenMutedChannelsCache.get())
        accountSettings = AccountNotificationSettings(notificationFlagsCache.get())
        markChanged()
        scheduleMuteRecompute()
    }

    @StoreThread
    internal fun reset() {
        StoreThreadScheduler.assertCurrentThread()
        recomputeSettingsSubscription?.unsubscribe()
        recomputeSettingsSubscription = null
        guildSettings.clear()
        guildsToHideMutedChannelsIn.clear()
        pendingPrivateWriteRevisions.clear()
        accountSettings = AccountNotificationSettings()
        guildSettingsCache.clear(persist = true)
        hiddenMutedChannelsCache.clear(persist = true)
        notificationFlagsCache.clear(persist = true)
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        guildSettingsSnapshot = guildSettings.toMap()
        guildsToHideMutedChannelsInSnapshot = guildsToHideMutedChannelsIn.toSet()
        accountSettingsSnapshot = accountSettings
        guildSettingsCache.set(guildSettingsSnapshot)
        hiddenMutedChannelsCache.set(guildsToHideMutedChannelsInSnapshot, persist = true)
        notificationFlagsCache.set(accountSettings.flags, persist = true)
    }

    // -- Reads ----------------------------------------------------------------------------------

    fun getGuildSettings(): Map<Long, ModelNotificationSettings> = guildSettingsSnapshot

    @StoreThread
    internal fun getGuildSettingsInternal(): Map<Long, ModelNotificationSettings> = guildSettings

    fun getGuildsToHideMutedChannelsIn(): Set<Long> = guildsToHideMutedChannelsInSnapshot

    fun getAccountNotificationSettings(): AccountNotificationSettings = accountSettingsSnapshot

    @StoreThread
    internal fun getAccountNotificationSettingsInternal(): AccountNotificationSettings = accountSettings

    fun observeAccountNotificationSettings(): Observable<AccountNotificationSettings> =
        observationDeck.connectRx(this, generator = ::getAccountNotificationSettings).distinctUntilChanged()

    /**
     * What marks [channel] unread. [guildDefaultMessageNotifications] is the guild's own default,
     * which the resolution falls back to when nothing overrides it.
     */
    fun resolveUnreadSetting(channel: Channel, guildDefaultMessageNotifications: Int): UnreadSetting =
        ChannelNotificationSettingsUtils.resolveUnreadSetting(
            channel,
            guildSettingsSnapshot[channel.guildId],
            guildDefaultMessageNotifications,
            accountSettingsSnapshot.useNewNotifications,
        )

    fun observeEvents(): Observable<Event> = eventSubject
    fun observeGuildSettings(): Observable<Map<Long, ModelNotificationSettings>> =
        observationDeck.connectRx(this, generator = ::getGuildSettings).distinctUntilChanged()

    /**
     * Never `null`: a guild with no stored settings behaves as a default one (unmuted, mobile push
     * on, frequency unset), and returning `null` would push that decision onto every caller.
     */
    fun observeGuildSettings(guildId: Long): Observable<ModelNotificationSettings> =
        observeGuildSettings().map { it[guildId] ?: ModelNotificationSettings(guildId = guildId) }
            .distinctUntilChanged()

    fun observeHideMutedChannels(guildId: Long): Observable<Boolean> =
        observationDeck.connectRx(this, generator = ::getGuildsToHideMutedChannelsIn)
            .map { guildId in it }.distinctUntilChanged()
    fun observeMutedGuildIds(): Observable<List<Long>> = observeGuildSettings()
        .map { settings -> settings.values.filter(ModelNotificationSettings::isMuted).map { it.guildId } }
        .distinctUntilChanged()

    // -- Gateway handlers -----------------------------------------------------------------------

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        payload.notificationSettings?.let { accountSettings = it }
        markChanged()
        val versioned = payload.userGuildSettings ?: return
        if (!versioned.isPartial) guildSettings.clear()
        handleGuildSettings(versioned.entries.orEmpty())
        markChanged()
    }

    @StoreThread
    fun handleGuildSettingUpdated(settings: List<ModelNotificationSettings>) = handleGuildSettings(settings)

    @StoreThread
    fun handleNotificationSettingsUpdate(settings: AccountNotificationSettings) {
        StoreThreadScheduler.assertCurrentThread()
        if (accountSettings == settings) return
        accountSettings = settings
        markChanged()
    }

    @StoreThread
    private fun handleGuildSettings(settings: List<ModelNotificationSettings>) {
        StoreThreadScheduler.assertCurrentThread()
        recomputeSettingsSubscription?.unsubscribe()
        settings.forEach { setting ->
            val computed = setting.copyAndRecomputeTransientFields(clock)
            pendingPrivateWriteRevisions.remove(computed.guildId)
            if (guildSettings[computed.guildId] != computed) {
                guildSettings[computed.guildId] = computed
                markChanged()
            }
        }
        scheduleMuteRecompute()
    }

    // -- Setters --------------------------------------------------------------------------------

    /**
     * Flips one [AccountNotificationFlags] bit.
     *
     * The endpoint returns no body; the new flags arrive as `NOTIFICATION_SETTINGS_UPDATE`.
     */
    fun setAccountNotificationFlag(context: Context, flag: Int, enabled: Boolean) = dispatcher.schedule {
        val api = restApi ?: return@schedule
        val flags = accountSettings.flags
        val updated = if (enabled) flags or flag else flags and flag.inv()
        if (updated == flags) return@schedule
        api.updateAccountNotificationSettings(RestAPIParams.AccountNotificationSettings(updated))
            .restSubscribeOn()
            .ui()
            .appSubscribe(StoreUserGuildSettings::class.java, context) { }
    }

    fun setHideMutedChannels(guildId: Long, hide: Boolean) = dispatcher.schedule {
        if (if (hide) guildsToHideMutedChannelsIn.add(guildId) else guildsToHideMutedChannelsIn.remove(guildId)) {
            markChanged()
        }
    }

    fun setChannelFrequency(context: Context, channel: Channel, frequency: Int) {
        if (channel.isThread()) {
            updateThreadMemberSettings(
                context,
                channel,
                RestAPIParams.ThreadMemberSettings(flags = frequency.toThreadNotificationFlags()),
            )
        } else {
            updateUserGuildSettings(
                context,
                channel.guildId,
                RestAPIParams.UserGuildSettings(
                    channel.id,
                    RestAPIParams.UserGuildSettings.ChannelOverride(messageNotifications = frequency, flags = null),
                ),
                settingsUpdateType(channel),
            )
        }
    }

    @JvmOverloads
    fun setChannelMuted(context: Context, channelId: Long, muted: Boolean, muteConfig: ModelMuteConfig? = null) =
        dispatcher.schedule {
            val channel = getChannel(channelId) ?: return@schedule
            if (channel.isThread()) {
                updateThreadMemberSettings(
                    context,
                    channel,
                    RestAPIParams.ThreadMemberSettings(muted = muted, muteConfig = muteConfig),
                )
            } else {
                updateUserGuildSettings(
                    context,
                    channel.guildId,
                    RestAPIParams.UserGuildSettings(
                        channel.id,
                        RestAPIParams.UserGuildSettings.ChannelOverride(muted, muteConfig),
                    ),
                    settingsUpdateType(channel),
                )
            }
        }

    fun setChannelNotificationFlags(context: Context, channel: Channel, flags: Int) {
        if (!channel.isThreadOnlyChannel()) return
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(messageNotifications = null, flags = flags),
            ),
            settingsUpdateType(channel),
        )
    }

    /**
     * Sets what marks [channel] unread, independently of its notification level.
     *
     * The two unread bits are mutually exclusive, so the write is built from the override's
     * current flags rather than sent bare: anything else would clear the forum and opt-in bits
     * that share the field.
     */
    fun setChannelUnreadSetting(context: Context, channel: Channel, setting: UnreadSetting) {
        val current = getGuildSettings()[channel.guildId]?.getChannelOverride(channel.id)?.flags ?: 0
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(
                    messageNotifications = null,
                    flags = ChannelNotificationSettingsFlags.withUnreadSetting(current, setting),
                ),
            ),
            settingsUpdateType(channel),
        )
    }

    /**
     * Sets a channel's notification level and unread badge together.
     *
     * One request rather than two so the pair can never be observed half-applied, which would
     * briefly resolve to a combination the viewer never chose.
     */
    fun setChannelPreset(
        context: Context,
        channel: Channel,
        messageNotifications: Int,
        unreadSetting: UnreadSetting,
    ) {
        val current = getGuildSettings()[channel.guildId]?.getChannelOverride(channel.id)?.flags ?: 0
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(
                    messageNotifications = messageNotifications,
                    flags = ChannelNotificationSettingsFlags.withUnreadSetting(current, unreadSetting),
                ),
            ),
            settingsUpdateType(channel),
        )
    }

    /**
     * Pins [channel] to the top of the channel list.
     *
     * Pinning also opts the channel in, because a pinned channel the viewer cannot see would be a
     * setting with no effect.
     */
    fun setChannelFavorite(context: Context, channel: Channel, favorite: Boolean) {
        val current = getGuildSettings()[channel.guildId]?.getChannelOverride(channel.id)?.flags ?: 0
        var flags = ChannelNotificationSettingsFlags.withFlag(
            current,
            ChannelNotificationSettingsFlags.FAVORITED,
            favorite,
        )
        if (favorite) {
            flags = ChannelNotificationSettingsFlags.withFlag(
                flags,
                ChannelNotificationSettingsFlags.OPT_IN_ENABLED,
                true,
            )
        }
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(messageNotifications = null, flags = flags),
            ),
            settingsUpdateType(channel),
        )
    }

    /** Adds or removes [channel] from the viewer's channel list under guild opt-in. */
    fun setChannelOptedIn(context: Context, channel: Channel, optedIn: Boolean) {
        val current = getGuildSettings()[channel.guildId]?.getChannelOverride(channel.id)?.flags ?: 0
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(
                    messageNotifications = null,
                    flags = ChannelNotificationSettingsFlags.withFlag(
                        current,
                        ChannelNotificationSettingsFlags.OPT_IN_ENABLED,
                        optedIn,
                    ),
                ),
            ),
            settingsUpdateType(channel),
        )
    }

    /** Hides channels the viewer has not opted into, across the whole guild. */
    fun setGuildOptInChannels(context: Context, guildId: Long, optInOnly: Boolean) {
        val current = getGuildSettings()[guildId]?.flags ?: 0
        updateUserGuildSettings(
            context,
            guildId,
            RestAPIParams.UserGuildSettings(
                flags = GuildNotificationSettingsFlags.withFlag(
                    current,
                    GuildNotificationSettingsFlags.OPT_IN_CHANNELS_ON,
                    optInOnly,
                ),
            ),
            SettingsUpdateType.GUILD,
        )
    }

    /** As [setChannelPreset], for the guild's own defaults. */
    fun setGuildPreset(
        context: Context,
        guildId: Long,
        messageNotifications: Int,
        unreadSetting: UnreadSetting,
    ) {
        val current = getGuildSettings()[guildId]?.flags ?: 0
        updateUserGuildSettings(
            context,
            guildId,
            RestAPIParams.UserGuildSettings(
                messageNotifications = messageNotifications,
                flags = GuildNotificationSettingsFlags.withUnreadSetting(current, unreadSetting),
            ),
            SettingsUpdateType.GUILD,
        )
    }

    /** As [setChannelUnreadSetting], for the guild's own default. */
    fun setGuildUnreadSetting(context: Context, guildId: Long, setting: UnreadSetting) {
        val current = getGuildSettings()[guildId]?.flags ?: 0
        updateUserGuildSettings(
            context,
            guildId,
            RestAPIParams.UserGuildSettings(
                flags = GuildNotificationSettingsFlags.withUnreadSetting(current, setting),
            ),
            SettingsUpdateType.GUILD,
        )
    }

    fun setChannelNotificationsDefault(context: Context, channel: Channel) {
        if (channel.isThread()) return
        updateUserGuildSettings(
            context,
            channel.guildId,
            RestAPIParams.UserGuildSettings(
                channel.id,
                RestAPIParams.UserGuildSettings.ChannelOverride(
                    muted = false,
                    muteConfig = null,
                    messageNotifications = ModelNotificationSettings.FREQUENCY_UNSET,
                ),
            ),
            settingsUpdateType(channel),
        )
    }

    fun setGuildFrequency(context: Context, guild: Guild, frequency: Int) = updateUserGuildSettings(
        context,
        guild.id,
        RestAPIParams.UserGuildSettings(messageNotifications = frequency),
        SettingsUpdateType.GUILD,
    )

    @JvmOverloads
    fun setGuildMuted(context: Context, guildId: Long, muted: Boolean, muteConfig: ModelMuteConfig? = null) =
        updateUserGuildSettings(
            context,
            guildId,
            RestAPIParams.UserGuildSettings(muted = muted, muteConfig = muteConfig),
            SettingsUpdateType.GUILD,
        )

    fun setGuildToggles(
        context: Context,
        guild: Guild,
        suppressEveryone: Boolean? = null,
        suppressRoles: Boolean? = null,
        mobilePush: Boolean? = null,
        muted: Boolean? = null,
        muteScheduledEvents: Boolean? = null,
    ) = updateUserGuildSettings(
        context,
        guild.id,
        RestAPIParams.UserGuildSettings(
            suppressEveryone = suppressEveryone,
            suppressRoles = suppressRoles,
            muted = muted,
            mobilePush = mobilePush,
            muteScheduledEvents = muteScheduledEvents,
        ),
        SettingsUpdateType.GUILD,
    )

    fun setNotifyHighlights(context: Context, guild: Guild, notifyHighlights: Int) = updateUserGuildSettings(
        context,
        guild.id,
        RestAPIParams.UserGuildSettings(notifyHighlights = notifyHighlights),
        SettingsUpdateType.GUILD,
    )

    // -- REST -----------------------------------------------------------------------------------

    /**
     * `PATCH /users/@me/guilds/{guildId}/settings`.
     *
     * Bound to [context] so a failure surfaces as the app's standard error toast instead of being
     * swallowed; the success callback runs on the main thread, which is where [observeEvents]
     * consumers live.
     */
    private fun updateUserGuildSettings(
        context: Context,
        guildId: Long,
        userGuildSettings: RestAPIParams.UserGuildSettings,
        settingsUpdateType: SettingsUpdateType,
    ) {
        val api = restApi ?: return
        val channelId = userGuildSettings.channelOverrides?.keys?.firstOrNull()
        if (guildId == PRIVATE_CHANNELS_GUILD_ID && channelId != null) {
            dispatcher.schedule {
                val previous = guildSettings[guildId] ?: ModelNotificationSettings(guildId = guildId)
                val update = requireNotNull(userGuildSettings.channelOverrides?.get(channelId))
                val revision = ++nextPrivateWriteRevision
                pendingPrivateWriteRevisions[guildId] = revision
                guildSettings[guildId] = previous.withChannelOverride(
                    channelId,
                    update.muted,
                    update.muteConfig,
                    update.messageNotifications,
                    update.flags,
                    clock,
                )
                markChanged()
                requestUserGuildSettings(api, guildId, userGuildSettings)
                    .restSubscribeOn()
                    .ui()
                    .appSubscribe(
                        StoreUserGuildSettings::class.java,
                        context,
                        errorHandler = {
                            dispatcher.schedule {
                                privateChannelRollback(
                                    pendingPrivateWriteRevisions[guildId],
                                    revision,
                                    previous,
                                )?.let { restored ->
                                    pendingPrivateWriteRevisions.remove(guildId)
                                    guildSettings[guildId] = restored
                                    markChanged()
                                }
                            }
                        },
                    ) { confirmed ->
                        dispatcher.schedule {
                            if (pendingPrivateWriteRevisions[guildId] == revision) {
                                pendingPrivateWriteRevisions.remove(guildId)
                                handleGuildSettings(listOf(confirmed))
                            }
                        }
                        analytics.onNotificationSettingsUpdated(confirmed, channelId)
                        eventSubject.onNext(Event.SettingsUpdated(settingsUpdateType))
                    }
            }
            return
        }
        requestUserGuildSettings(api, guildId, userGuildSettings)
            .restSubscribeOn()
            .ui()
            .appSubscribe(StoreUserGuildSettings::class.java, context) { notifSettings ->
                analytics.onNotificationSettingsUpdated(notifSettings, channelId)
                eventSubject.onNext(Event.SettingsUpdated(settingsUpdateType))
            }
    }

    /** `PATCH /channels/{channelId}/thread-members/@me/settings`. */
    private fun updateThreadMemberSettings(
        context: Context,
        channel: Channel,
        threadMemberSettings: RestAPIParams.ThreadMemberSettings,
    ) {
        val api = restApi ?: return
        val channelId = channel.id
        val parentChannelId = channel.parentId
        val oldFlags = getJoinedThreadFlags(channelId)
        api.updateThreadMemberSettings(channelId, threadMemberSettings)
            .restSubscribeOn()
            .ui()
            .appSubscribe(StoreUserGuildSettings::class.java, context) { threadMember ->
                analytics.onThreadNotificationSettingsUpdated(
                    channelId,
                    parentChannelId,
                    threadMember.flags,
                    oldFlags,
                )
                eventSubject.onNext(Event.SettingsUpdated(SettingsUpdateType.THREAD))
            }
    }

    // -- Internals -------------------------------------------------------------------------------

    /**
     * Re-evaluate temporary mutes when the earliest one expires.
     *
     * `ModelNotificationSettings.derivedMute` is a function of the wall clock, so without this timer
     * a mute that ended while the app was open would keep suppressing notifications until the next
     * gateway update.
     */
    private fun scheduleMuteRecompute() {
        val next = guildSettings.values.minOfOrNull { it.getNextMuteEndTimeMs(clock) } ?: Long.MAX_VALUE
        if (next == Long.MAX_VALUE) return
        recomputeSettingsSubscription = Observable.timer(
            (next - clock.currentTimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
            dispatcher.scheduler,
        ).subscribe { dispatcher.schedule { handleGuildSettings(guildSettings.values.toList()) } }
    }

    /**
     * Analytics label only. A voice, stage or store channel is neither a text channel nor a
     * category and is reported as `CATEGORY`, matching the reference's fallback.
     */
    private fun settingsUpdateType(channel: Channel) = when {
        channel.isThread() -> SettingsUpdateType.THREAD
        channel.isTextChannel() -> SettingsUpdateType.CHANNEL
        else -> SettingsUpdateType.CATEGORY
    }

    private fun Int.toThreadNotificationFlags(): Int = when (this) {
        ModelNotificationSettings.FREQUENCY_NOTHING -> THREAD_FLAG_NO_MESSAGES
        ModelNotificationSettings.FREQUENCY_MENTIONS -> THREAD_FLAG_ONLY_MENTIONS
        else -> THREAD_FLAG_ALL_MESSAGES
    }

    companion object {
        private const val PRIVATE_CHANNELS_GUILD_ID = 0L
        const val GUILD_SETTINGS_CACHE_KEY = "STORE_SETTINGS_USER_GUILD_V6"
        const val HIDDEN_MUTED_CHANNELS_CACHE_KEY = "STORE_SHOW_HIDE_MUTED_CHANNELS_V2"
        const val NOTIFICATION_FLAGS_CACHE_KEY = "STORE_ACCOUNT_NOTIFICATION_FLAGS_V1"

        private const val THREAD_FLAG_ALL_MESSAGES = 2
        private const val THREAD_FLAG_ONLY_MENTIONS = 4
        private const val THREAD_FLAG_NO_MESSAGES = 8
    }
}

internal fun requestUserGuildSettings(
    api: RestAPIInterface,
    guildId: Long,
    settings: RestAPIParams.UserGuildSettings,
): Observable<ModelNotificationSettings> = if (guildId == 0L) {
    api.updatePrivateChannelSettings(settings)
} else {
    api.updateUserGuildSettings(guildId, settings)
}

internal fun privateChannelRollback(
    currentRevision: Long?,
    failedRevision: Long,
    previous: ModelNotificationSettings,
): ModelNotificationSettings? = previous.takeIf { currentRevision == failedRevision }
