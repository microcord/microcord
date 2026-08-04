package com.discord.widgets.servers

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isCategory
import com.discord.api.channel.isTextChannel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildProfiles
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserGuildSettings
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * State for the per-server notification settings screen.
 *
 * Reference: `WidgetServerNotifications.Model` plus the screen's second
 * `getNotificationOverrides` stream. Both are folded into one [ViewState] here, so the screen never
 * renders a header from one guild against overrides from another.
 *
 * `guildProfile` has no source in this project and is therefore a constructor parameter rather than
 * a silently-dropped field:
 *
 * - **`guildProfile`** — the reference reads `StoreStream.getGuildProfiles().observeGuildProfile()`.
 *   `StoreGuildProfiles` exists but `StoreStream` neither constructs nor exposes it, so
 *   [guildProfileSource] defaults to "no profile". Consequence: `approximateMemberCount` is unknown,
 *   so [ViewState.Loaded.isAboveNotifyAllSize] and [ViewState.Loaded.isAboveHighlightsSize] are
 *   `false` and the large-server gating on "All messages" never engages.
 */
class WidgetServerNotificationsViewModel(
    val guildId: Long,
    private val guildSettingsStore: StoreUserGuildSettings = StoreStream.getUserGuildSettings(),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<WidgetServerNotificationsViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val guildSettings: ModelNotificationSettings,
        val guildProfile: StoreGuildProfiles.GuildProfileData?,
        val guildChannels: Map<Long, Channel>,
        val channelPermissions: Map<Long, Long>,
        val highlightsEnabled: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** The guild went away — it was left or deleted while the screen was open. */
        object Invalid : ViewState()

        data class Loaded(
            val guild: Guild,
            val guildSettings: ModelNotificationSettings,
            val overrides: List<NotificationsOverridesAdapter.Item>,
            val highlightsEnabled: Boolean,
            val isAboveNotifyAllSize: Boolean,
            val isAboveHighlightsSize: Boolean,
        ) : ViewState() {
            /** A guild with no explicit frequency inherits the guild's own default. */
            val notificationsSetting: Int
                get() = if (guildSettings.messageNotifications != ModelNotificationSettings.FREQUENCY_UNSET) {
                    guildSettings.messageNotifications
                } else {
                    guild.defaultMessageNotifications
                }

            val isMuted: Boolean get() = guildSettings.isMuted

            /** Hubs have no roles, no @everyone and no per-channel overrides to configure. */
            val isHub: Boolean get() = guild.isHub()
        }
    }

    init {
        storeStateObservable.computationLatest().ui(this).subscribe { handleStoreState(it) }
    }

    @MainThread
    fun setFrequency(context: Context, frequency: Int) = withLoaded { loaded ->
        guildSettingsStore.setGuildFrequency(context, loaded.guild, frequency)
    }

    @MainThread
    fun toggleSuppressEveryone(context: Context) = withLoaded { loaded ->
        guildSettingsStore.setGuildToggles(
            context,
            loaded.guild,
            suppressEveryone = !loaded.guildSettings.isSuppressEveryone,
        )
    }

    @MainThread
    fun toggleSuppressRoles(context: Context) = withLoaded { loaded ->
        guildSettingsStore.setGuildToggles(
            context,
            loaded.guild,
            suppressRoles = !loaded.guildSettings.isSuppressRoles,
        )
    }

    @MainThread
    fun toggleMobilePush(context: Context) = withLoaded { loaded ->
        guildSettingsStore.setGuildToggles(
            context,
            loaded.guild,
            mobilePush = !loaded.guildSettings.isMobilePush,
        )
    }

    @MainThread
    fun setNotifyHighlights(context: Context, notifyHighlights: Int) = withLoaded { loaded ->
        guildSettingsStore.setNotifyHighlights(context, loaded.guild, notifyHighlights)
    }

    @MainThread
    fun unmute(context: Context) {
        guildSettingsStore.setGuildMuted(context, guildId, false)
    }

    /**
     * Clears a channel's override, which is what a swipe-to-delete on an override row means.
     * Unmuting comes first: an override that is only a mute carries no frequency to reset.
     */
    @MainThread
    fun deleteOverride(context: Context, item: NotificationsOverridesAdapter.Item) {
        if (item.overrideSettings.isMuted) {
            guildSettingsStore.setChannelMuted(context, item.channel.id, false)
        }
        guildSettingsStore.setChannelFrequency(
            context,
            item.channel,
            ModelNotificationSettings.FREQUENCY_UNSET,
        )
    }

    private inline fun withLoaded(block: (ViewState.Loaded) -> Unit) {
        (viewState as? ViewState.Loaded)?.let(block)
    }

    @MainThread
    internal fun handleStoreState(storeState: StoreState) {
        val guild = storeState.guild ?: return updateViewState(ViewState.Invalid)
        val guildSettings = storeState.guildSettings

        val memberCount = storeState.guildProfile?.data?.approximateMemberCount
        val isAboveNotifyAllSize = memberCount != null && memberCount > LARGE_GUILD_MEMBER_COUNT
        updateViewState(
            ViewState.Loaded(
                guild = guild,
                guildSettings = guildSettings,
                overrides = buildOverrides(
                    guildSettings,
                    storeState.guildChannels,
                    storeState.channelPermissions,
                ),
                highlightsEnabled = storeState.highlightsEnabled,
                isAboveNotifyAllSize = isAboveNotifyAllSize,
                isAboveHighlightsSize = storeState.highlightsEnabled && isAboveNotifyAllSize,
            ),
        )
    }

    companion object {
        /** Above this many members a server counts as "large" for notification purposes. */
        const val LARGE_GUILD_MEMBER_COUNT = 2500

        /**
         * The override rows, in sidebar order.
         *
         * Only overrides that actually change something are listed — an override row exists once a
         * channel is muted or has an explicit frequency — and only for channels the user can still
         * see. Categories are included; voice channels are not, because
         * `NotificationsOverridesAdapter` has no row for them.
         */
        internal fun buildOverrides(
            guildSettings: ModelNotificationSettings,
            guildChannels: Map<Long, Channel>,
            channelPermissions: Map<Long, Long>,
        ): List<NotificationsOverridesAdapter.Item> {
            val overridesByChannelId = guildSettings.channelOverrides
                .filter {
                    it.messageNotifications != ModelNotificationSettings.FREQUENCY_UNSET || it.isMuted
                }
                .associateBy { it.channelId }
            if (overridesByChannelId.isEmpty()) return emptyList()

            return GuildChannelsSort.sortedVisibleChannels(guildChannels, channelPermissions)
                .filter { it.isCategory() || it.isTextChannel() }
                .mapNotNull { channel ->
                    overridesByChannelId[channel.id]?.let { override ->
                        NotificationsOverridesAdapter.Item(
                            channel = channel,
                            parent = guildChannels[channel.parentId],
                            overrideSettings = override,
                        )
                    }
                }
        }

        fun observeStoreState(
            guildId: Long,
            guildProfileSource: (Long) -> Observable<StoreGuildProfiles.GuildProfileData?> =
                { Observable.just(null) },
            highlightsEnabledSource: Observable<Boolean> = Observable.just(true),
        ): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUserGuildSettings().observeGuildSettings(guildId),
            guildProfileSource(guildId),
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
            highlightsEnabledSource,
        ) { guild, guildSettings, guildProfile, channels, permissions, highlightsEnabled ->
            StoreState(
                guild = guild,
                guildSettings = guildSettings,
                guildProfile = guildProfile,
                guildChannels = channels,
                channelPermissions = permissions,
                highlightsEnabled = highlightsEnabled,
            )
        }.distinctUntilChanged()
    }
}
