package com.discord.widgets.settings

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isCategory
import com.discord.api.channel.isDM
import com.discord.api.channel.isGroup
import com.discord.api.channel.isThread
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadsJoined
import com.discord.stores.StoreUserGuildSettings
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Backs the mute-duration sheet shared by servers, channels, categories, threads and DMs.
 *
 * Reference: `com.discord.widgets.settings.MuteSettingsSheetViewModel`. The sheet is opened with
 * exactly one of a guild id or a channel id, which selects the [Config] and, through it, which
 * `StoreUserGuildSettings` setter a duration choice writes to.
 *
 * Reconstruction note: the reference's `handleStoreState` is
 * *"Method dump skipped, instructions count: 402"* in the JADX export and Vineflower cannot
 * decompile this class at all, so the reduction below is derived from the shape of
 * `ViewState.Loaded`, from `SettingsType`'s `WhenMappings` table (GUILD, THREAD, GUILD_CHANNEL, DM,
 * GROUP_DM, CATEGORY are all mapped, `UNKNOWN` is not) and from what `WidgetMuteSettingsSheet`
 * reads out of the state. It is the least-corroborated part of this slice.
 *
 * Thread mute state comes from `StoreThreadsJoined`, so reopening after rotation reflects the
 * persisted joined-thread flags instead of falling back to an unmuted row.
 */
class MuteSettingsSheetViewModel(
    val guildId: Long,
    val channelId: Long,
    private val storeUserGuildSettings: StoreUserGuildSettings = StoreStream.getUserGuildSettings(),
    private val clock: Clock = ClockFactory.get(),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId, channelId),
) : AppViewModel<MuteSettingsSheetViewModel.ViewState>(ViewState.Uninitialized) {

    /** Which surface the sheet is muting. Mirrors the reference enum, including its order. */
    enum class SettingsType { GUILD, DM, GROUP_DM, THREAD, GUILD_CHANNEL, CATEGORY, UNKNOWN }

    sealed class Config {
        data class Guild(val guildId: Long) : Config()
        data class Channel(val channelId: Long) : Config()
    }

    sealed class Event {
        object Dismiss : Event()
        data class NavigateToChannelSettings(val channelId: Long) : Event()
    }

    data class StoreState(
        val guild: Guild?,
        val channel: Channel?,
        val guildNotificationSettings: ModelNotificationSettings?,
        val joinedThread: StoreThreadsJoined.JoinedThread?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Failure : ViewState()

        data class Loaded(
            val settingsType: SettingsType,
            val subtitle: String,
            val isChannelMuted: Boolean = false,
            val isGuildMuted: Boolean = false,
            val muteEndTime: String? = null,
            val notificationSetting: Int = ModelNotificationSettings.FREQUENCY_UNSET,
        ) : ViewState()
    }

    val config: Config =
        if (guildId != 0L) Config.Guild(guildId) else Config.Channel(channelId)

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable.computationLatest().ui(this).subscribe(::handleStoreState)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun selectMuteDurationMs(muteDurationMs: Long, appContext: Context) {
        val muteConfig = if (muteDurationMs != MUTE_DURATION_ALWAYS) {
            ModelMuteConfig(TimeUtils.toUTCDateTime(clock.currentTimeMillis() + muteDurationMs))
        } else {
            null
        }
        when (val config = config) {
            is Config.Guild -> storeUserGuildSettings.setGuildMuted(appContext, config.guildId, true, muteConfig)
            is Config.Channel -> storeUserGuildSettings.setChannelMuted(appContext, config.channelId, true, muteConfig)
        }
        emitDismissEvent()
    }

    /** Unmuting a guild is done from the server-notifications screen, so only channels handle it here. */
    @MainThread
    fun unmute(appContext: Context) {
        val config = config
        if (config is Config.Channel) {
            storeUserGuildSettings.setChannelMuted(appContext, config.channelId, false, null)
            emitDismissEvent()
        }
    }

    @MainThread
    fun onChannelSettingsSelected() {
        val config = config
        if (config is Config.Channel) emitNotificationSettingsEvent(config.channelId)
    }

    @MainThread
    fun emitNotificationSettingsEvent(channelId: Long) =
        eventSubject.onNext(Event.NavigateToChannelSettings(channelId))

    @MainThread
    internal fun handleStoreState(storeState: StoreState) {
        val guild = storeState.guild
        val channel = storeState.channel
        val guildSettings = storeState.guildNotificationSettings
        val isGuildMuted = guildSettings?.isMuted == true

        when (config) {
            is Config.Guild -> {
                if (guild == null) return updateViewState(ViewState.Failure)
                updateViewState(
                    ViewState.Loaded(
                        settingsType = SettingsType.GUILD,
                        subtitle = guild.name,
                        isChannelMuted = isGuildMuted,
                        isGuildMuted = isGuildMuted,
                        muteEndTime = guildSettings?.muteEndTime,
                        notificationSetting = guildSettings?.messageNotifications
                            ?: ModelNotificationSettings.FREQUENCY_UNSET,
                    ),
                )
            }

            is Config.Channel -> {
                if (channel == null) return updateViewState(ViewState.Failure)
                val override = guildSettings?.getChannelOverride(channel.id)
                val joinedThread = storeState.joinedThread
                val isThread = channel.isThread()
                updateViewState(
                    ViewState.Loaded(
                        settingsType = settingsTypeOf(channel),
                        subtitle = channel.getDisplayName(),
                        isChannelMuted = if (isThread) joinedThread?.muted == true else override?.isMuted == true,
                        isGuildMuted = isGuildMuted,
                        muteEndTime = if (isThread) joinedThread?.muteConfig?.endTime else override?.muteEndTime,
                        notificationSetting = override?.messageNotifications
                            ?: ModelNotificationSettings.FREQUENCY_UNSET,
                    ),
                )
            }
        }
    }

    @MainThread
    private fun emitDismissEvent() = eventSubject.onNext(Event.Dismiss)

    companion object {
        /** "Until I turn it back on" — no mute config is sent, so the mute never expires. */
        const val MUTE_DURATION_ALWAYS = 0L

        internal fun settingsTypeOf(channel: Channel): SettingsType = when {
            channel.isDM() -> SettingsType.DM
            channel.isGroup() -> SettingsType.GROUP_DM
            channel.isThread() -> SettingsType.THREAD
            channel.isCategory() -> SettingsType.CATEGORY
            channel.guildId != 0L -> SettingsType.GUILD_CHANNEL
            else -> SettingsType.UNKNOWN
        }

        fun observeStoreState(
            guildId: Long,
            channelId: Long,
            joinedThreadSource: (Long) -> Observable<StoreThreadsJoined.JoinedThread?> =
                { StoreStream.getThreadsJoined().observeJoinedThread(it) },
        ): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getChannels().observeChannel(channelId),
            StoreStream.getUserGuildSettings().observeGuildSettings(),
            joinedThreadSource(channelId),
        ) { guild, channel, guildSettings, joinedThread ->
            val settingsGuildId = guild?.id ?: channel?.guildId ?: 0L
            StoreState(guild, channel, guildSettings[settingsGuildId], joinedThread)
        }
    }
}
