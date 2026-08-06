package com.discord.widgets.home

import android.content.SharedPreferences
import androidx.annotation.MainThread
import com.discord.api.permission.Permission
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.panels.PanelState
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuildWelcomeScreens
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreGuildsNsfw
import com.discord.stores.StoreLurking
import com.discord.stores.StoreNavigation
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserConnections
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.isNewUser
import com.discord.widgets.channels.ChannelOnboardingManager
import rx.Observable
import rx.subjects.PublishSubject

/**
 * State and one-shot events for the home surface.
 *
 * Two responsibilities that look unrelated but are not:
 *
 * * **Panel state.** The panels are driven from the stores rather than from touch alone, so a
 *   channel selected anywhere in the app lands the user on the chat with the list closed. The
 *   nav-drawer action stream ([StoreNavigation.PanelAction]) is translated into
 *   [Event.OpenLeftPanel] / [Event.ClosePanels] / [Event.UnlockLeftPanel].
 * * **First-run and upsell modals.** Exactly one modal may be offered per store emission, in a fixed
 *   order — channel onboarding, guild-event upsell, PlayStation upsell, guild-role-subscription
 *   upsell — so two sheets never stack. The guild welcome sheet is outside that chain because it is
 *   keyed to a *specific* guild being lurked rather than to the user.
 *
 * [Event.AnimatePeekIn] / [Event.AnimatePeekOut] fire on the transition into and out of a peeked
 * thread, which is why [wasThreadPeek] is remembered across emissions.
 */
class WidgetHomeViewModel(
    private val storeNavigation: StoreNavigation = StoreStream.getNavigation(),
    private val storeGuildWelcomeScreens: StoreGuildWelcomeScreens = StoreStream.getGuildWelcomeScreens(),
    storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    storePermissions: StorePermissions = StoreStream.getPermissions(),
    storeUser: StoreUser = StoreStream.getUsers(),
    storeStateObservable: Observable<StoreState> = observeStoreState(
        storeGuilds = storeGuilds,
        storeNavigation = storeNavigation,
        storeUser = storeUser,
        storeGuildWelcomeScreens = storeGuildWelcomeScreens,
        storePermissions = storePermissions,
    ),
    navigationPanelActionObservable: Observable<StoreNavigation.PanelAction> =
        storeNavigation.getNavigationPanelAction(),
    private val channelOnboardingManager: ChannelOnboardingManager = ChannelOnboardingManager(),
    private val sharedPreferences: SharedPreferences = SharedPreferencesProvider.get(),
    private val clock: Clock = ClockFactory.get(),
) : AppViewModel<WidgetHomeViewModel.ViewState>(
    ViewState(
        leftPanelState = PanelState.Closed,
        rightPanelState = PanelState.Closed,
        isNsfwUnconsented = false,
        isChannelNsfw = false,
        nsfwAllowed = null,
        guildId = 0L,
    ),
) {

    data class GuildInfo(
        val guildId: Long,
        val guild: Guild?,
        val isLurking: Boolean,
        val welcomeScreenState: StoreGuildWelcomeScreens.State?,
        val isNsfwUnconsented: Boolean,
        val canManageEvents: Boolean,
    )

    data class StoreState(
        val leftPanelState: PanelState,
        val rightPanelState: PanelState,
        val guildInfo: GuildInfo,
        val me: MeUser,
        val isThreadPeek: Boolean,
        val isChannelNsfw: Boolean,
        val nsfwAllowed: NsfwAllowance?,
        val guildId: Long,
        val connectedAccountsState: StoreUserConnections.State,
    )

    data class ViewState(
        val leftPanelState: PanelState,
        val rightPanelState: PanelState,
        val isNsfwUnconsented: Boolean,
        val isChannelNsfw: Boolean,
        val nsfwAllowed: NsfwAllowance?,
        val guildId: Long,
    )

    sealed class Event {
        object OpenLeftPanel : Event()
        object ClosePanels : Event()
        object UnlockLeftPanel : Event()
        object ShowChannelOnboardingSheet : Event()
        object AnimatePeekIn : Event()
        object AnimatePeekOut : Event()
        data class ShowGuildWelcomeSheet(val guildId: Long) : Event()
        data class ShowGuildEventUpsell(val guildId: Long) : Event()
        data class ShowGuildRoleSubscriptionUpsell(val guildId: Long) : Event()
    }

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    private var previousLeftPanelState: PanelState? = null
    private var wasThreadPeek = false

    /**
     * The last model the screen rendered, parked here so it survives the fragment view.
     * [WidgetHome.handleCenterPanelBack] needs the selection *without* re-subscribing.
     */
    var widgetHomeModel: WidgetHomeModel? = null

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetHomeViewModel::class.java) { handleStoreState(it) }

        navigationPanelActionObservable
            .ui(this)
            .appSubscribe(WidgetHomeViewModel::class.java) { handleNavDrawerAction(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun onStartPanelStateChange(panelState: PanelState) = storeNavigation.setLeftPanelState(panelState)

    fun onEndPanelStateChange(panelState: PanelState) = storeNavigation.setRightPanelState(panelState)

    @MainThread
    fun emitOpenLeftPanelEvent() = eventSubject.onNext(Event.OpenLeftPanel)

    @MainThread
    private fun handleNavDrawerAction(panelAction: StoreNavigation.PanelAction) = when (panelAction) {
        StoreNavigation.PanelAction.OPEN -> eventSubject.onNext(Event.OpenLeftPanel)
        StoreNavigation.PanelAction.CLOSE -> eventSubject.onNext(Event.ClosePanels)
        StoreNavigation.PanelAction.UNLOCK_LEFT -> eventSubject.onNext(Event.UnlockLeftPanel)
        StoreNavigation.PanelAction.NOOP -> Unit
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val leftPanelState = storeState.leftPanelState
        val guildId = storeState.guildInfo.guildId
        val welcomeScreenState = storeState.guildInfo.welcomeScreenState
        val isNewUser = storeState.me.isNewUser(clock)

        updateViewState(
            ViewState(
                leftPanelState = leftPanelState,
                rightPanelState = storeState.rightPanelState,
                isNsfwUnconsented = storeState.guildInfo.isNsfwUnconsented,
                isChannelNsfw = storeState.isChannelNsfw,
                nsfwAllowed = storeState.nsfwAllowed,
                guildId = storeState.guildId,
            ),
        )

        // Lurking a guild means previewing it without joining, which is exactly when its welcome
        // screen is worth showing. The content may not be loaded yet, so the first pass kicks off
        // the fetch and a later emission shows the sheet.
        if (storeState.guildInfo.isLurking && !storeGuildWelcomeScreens.hasWelcomeScreenBeenSeen(guildId)) {
            when (welcomeScreenState) {
                is StoreGuildWelcomeScreens.State.Loaded -> eventSubject.onNext(Event.ShowGuildWelcomeSheet(guildId))
                null -> storeGuildWelcomeScreens.fetchIfNonexisting(guildId)
                else -> Unit
            }
        }

        // At most one of these per emission, in this order.
        val guild = storeState.guildInfo.guild
        when {
            shouldShowChannelOnboardingSheet(
                previousLeftPanelState,
                leftPanelState,
                guildId,
                isNewUser,
                channelOnboardingManager.hasUserSeenChannelOnboarding(),
            ) ->
                eventSubject.onNext(Event.ShowChannelOnboardingSheet)

            shouldShowGuildEventUpsell(storeState.guildInfo.canManageEvents) -> {
                sharedPreferences.edit().putBoolean(GUILD_EVENT_UPSELL_CACHE_KEY, true).apply()
                eventSubject.onNext(Event.ShowGuildEventUpsell(guildId))
            }

            guild != null && guildRoleSubscriptionUpsellCanShow(guild) -> {
                eventSubject.onNext(Event.ShowGuildRoleSubscriptionUpsell(storeState.guildId))
                guildRoleSubscriptionUpsellShown()
            }
        }

        previousLeftPanelState = leftPanelState

        if (storeState.isThreadPeek && !wasThreadPeek) eventSubject.onNext(Event.AnimatePeekIn)
        if (!storeState.isThreadPeek && wasThreadPeek) eventSubject.onNext(Event.AnimatePeekOut)
        wasThreadPeek = storeState.isThreadPeek
    }

    /**
     * Channel onboarding is offered at the moment the guild list finishes opening for a new user in
     * a guild — never mid-swipe, which is why both the previous and the current panel state matter.
     */
    private fun shouldShowGuildEventUpsell(canManageEvents: Boolean): Boolean =
        canManageEvents && !sharedPreferences.getBoolean(GUILD_EVENT_UPSELL_CACHE_KEY, false)

    companion object {
        /** Matches `WidgetGuildScheduledEventUpsellBottomSheet.GUILD_EVENT_UPSELL_CACHE_KEY`. */
        const val GUILD_EVENT_UPSELL_CACHE_KEY = "CACHE_KEY_GUILD_EVENT_UPSELL"

        internal fun shouldShowChannelOnboardingSheet(
            previousLeftPanelState: PanelState?,
            leftPanelState: PanelState,
            guildId: Long,
            isNewUser: Boolean,
            hasUserSeenChannelOnboarding: Boolean,
        ): Boolean = previousLeftPanelState == PanelState.Opening &&
            leftPanelState == PanelState.Open &&
            guildId != 0L &&
            isNewUser &&
            !hasUserSeenChannelOnboarding

        /**
         * Seams for managers this project has not reconstructed yet. Each defaults to "already
         * shown / cannot show", so the corresponding sheet stays silent rather than being offered
         * with nothing behind it.
         *
         * * `GuildRoleSubscriptionOnboardingUpsellManager.canShow` / `setHasBeenShown`
         */
        @JvmField
        var guildRoleSubscriptionUpsellCanShow: (Guild) -> Boolean = { false }

        @JvmField
        var guildRoleSubscriptionUpsellShown: () -> Unit = {}

        /**
         * The lurk and NSFW-gate stores exist but are not published by `StoreStream`, so they are
         * injected when available and default to "not lurking" / "gate agreed" when not. Wire them
         * up the moment `StoreStream` exposes them.
         */
        @JvmField
        var storeLurking: StoreLurking? = null

        @JvmField
        var storeGuildsNsfw: StoreGuildsNsfw? = null

        fun observeStoreState(
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
            storeGuildSelected: StoreGuildSelected = StoreStream.getGuildSelected(),
            storeNavigation: StoreNavigation = StoreStream.getNavigation(),
            storeUser: StoreUser = StoreStream.getUsers(),
            storeGuildWelcomeScreens: StoreGuildWelcomeScreens? = null,
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeUserConnections: StoreUserConnections = StoreStream.getUserConnections(),
        ): Observable<StoreState> {
            val guildInfoObservable = storeGuildSelected.observeSelectedGuildId().switchMap { selectedGuildId ->
                Observable.combineLatest(
                    storeGuilds.observeGuild(selectedGuildId),
                    storeLurking?.isLurkingObs(selectedGuildId) ?: Observable.just(false),
                    storeGuildWelcomeScreens?.observeGuildWelcomeScreen(selectedGuildId)
                        ?: Observable.just<StoreGuildWelcomeScreens.State?>(null),
                    storePermissions.observePermissionsForGuild(selectedGuildId),
                    storeGuildsNsfw?.observeIsGuildNsfwGateAgreed(selectedGuildId) ?: Observable.just(true),
                ) { guild, isLurking, welcomeScreenState, permissions, isNsfwGateAgreed ->
                    GuildInfo(
                        guildId = selectedGuildId,
                        guild = guild,
                        isLurking = isLurking,
                        welcomeScreenState = welcomeScreenState,
                        isNsfwUnconsented = !isNsfwGateAgreed,
                        canManageEvents = PermissionUtils.can(Permission.MANAGE_EVENTS, permissions),
                    )
                }
            }

            return Observable.combineLatest(
                storeNavigation.observeLeftPanelState(),
                storeNavigation.observeRightPanelState(),
                guildInfoObservable,
                storeUser.observeMe(),
                storeChannelsSelected.observeResolvedSelectedChannel(),
                storeUserConnections.observeConnectedAccounts(),
            ) { leftPanelState, rightPanelState, guildInfo, me, selectedChannel, connectedAccounts ->
                val channel = selectedChannel.maybeChannel
                val isThreadPeek =
                    (selectedChannel as? StoreChannelsSelected.ResolvedSelectedChannel.Channel)?.peekParent != null
                StoreState(
                    leftPanelState = leftPanelState,
                    rightPanelState = rightPanelState,
                    guildInfo = guildInfo,
                    me = me,
                    isThreadPeek = isThreadPeek,
                    isChannelNsfw = channel != null && channel.nsfw,
                    nsfwAllowed = me.nsfwAllowance,
                    guildId = guildInfo.guildId,
                    connectedAccountsState = connectedAccounts,
                )
            }
        }
    }
}
