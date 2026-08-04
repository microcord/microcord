package com.discord.widgets.settings

import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import rx.Observable
import rx.subjects.BehaviorSubject

/** Shorthand so the explicit type arguments on the header combine below stay readable. */
private typealias ViewStateOfHeader = UserProfileHeaderViewModel.ViewState

/**
 * State for the user-settings root screen (`WidgetSettings`).
 *
 * Reference: `com.discord.widgets.settings.SettingsViewModel`, whose `StoreState` combines
 * `StoreUser`, `StoreGuilds`, `StoreUserPresence`, `StoreOutboundPromotions`,
 * `StoreNotificationUpsells`, `StoreExperiments` and `StoreSubscriptions` through the
 * `ObservationDeck`. Four of those seven stores do not exist in this project yet, so the fields they
 * feed are sourced from the constructor instead of being silently dropped:
 *
 * - `promoCount` (`StoreOutboundPromotions`) — [promoCountSource], default `0`.
 * - `showRoleSubscriptionsButton` (`StoreSubscriptions.getSubscriptions()` filtered by
 *   `isActiveGuildSubscription`) — [hasActiveGuildSubscriptionSource], default `false`.
 * - `pushNotificationUpsellDismissed` (`StoreNotificationUpsells`) — held in memory here, so
 *   [dismissPushNotificationUpsell] works for the session but does not survive process death.
 * - `refreshSubscriptions()` (`StoreSubscriptions.fetchSubscriptions()`) — no-op.
 *
 * The screen additionally renders `UserProfileHeaderView`. The reference feeds it from
 * `UserProfileHeaderViewModel.observeViewState()`; that class is a data-only shell in this project
 * and is owned by the `widgets/user` slice, so the header state is assembled here from the same
 * stores (`StoreUser`, `StoreUserProfile`, `StoreUserPresence`, `StoreUserSettings`,
 * `StoreAccessibility`) rather than being hardcoded. Move it when the real view model lands.
 */
class SettingsViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
    private val profileHeaderStateObservable: Observable<UserProfileHeaderViewModel.ViewState> =
        observeProfileHeaderState(),
) : AppViewModel<SettingsViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val meUser: MeUser,
        val isStaffOrAlpha: Boolean,
        val presence: Presence?,
        val promoCount: Int,
        val pushNotificationUpsellDismissed: Boolean,
        val hasActiveGuildSubscription: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val meUser: MeUser,
            val isStaffOrAlpha: Boolean,
            val presence: Presence?,
            val promoCount: Int,
            val pushNotificationUpsellDismissed: Boolean,
            val showRoleSubscriptionsButton: Boolean,
        ) : ViewState()
    }

    /**
     * Set when the user taps "dismiss" on the push-notification upsell banner. The reference
     * persists this through `StoreNotificationUpsells.dismissPushNotificationsUpsell()`.
     */
    private val upsellDismissed = BehaviorSubject.create(false)

    /** Toggled by a banner press on the profile header; reference: `UserProfileHeaderViewModel`. */
    private val allowAnimationInReducedMotion = BehaviorSubject.create(false)

    init {
        Observable.combineLatest(storeStateObservable, upsellDismissed) { storeState, dismissed ->
            storeState.copy(pushNotificationUpsellDismissed = storeState.pushNotificationUpsellDismissed || dismissed)
        }
            .distinctUntilChanged()
            .ui(this)
            .subscribe { handleStoreState(it) }
    }

    fun observeProfileHeaderViewState(): Observable<UserProfileHeaderViewModel.ViewState> =
        // The result type is pinned explicitly: the lambda only ever produces `ViewState.Loaded`,
        // and `rx.Observable` is a Java type, so an inferred `Observable<Loaded>` would not be
        // assignable to the declared `Observable<ViewState>`.
        Observable.combineLatest<ViewStateOfHeader, Boolean, ViewStateOfHeader>(
            profileHeaderStateObservable,
            allowAnimationInReducedMotion,
        ) { state, allowAnimation ->
            when (state) {
                is UserProfileHeaderViewModel.ViewState.Loaded ->
                    state.copy(allowAnimationInReducedMotion = allowAnimation)
                UserProfileHeaderViewModel.ViewState.Uninitialized -> state
            }
        }.distinctUntilChanged()

    fun dismissPushNotificationUpsell() = upsellDismissed.onNext(true)

    /** Reference: `UserProfileHeaderViewModel.toggleAllowAnimationInReducedMotion()`. */
    fun toggleAllowAnimationInReducedMotion() =
        allowAnimationInReducedMotion.onNext(!(allowAnimationInReducedMotion.value ?: false))

    /**
     * Reference re-fetches the user's subscriptions whenever the settings tab is selected, so the
     * Nitro rows and the role-subscription entry reflect a fresh server state. `StoreSubscriptions`
     * does not exist yet; this stays a no-op until it does.
     */
    fun refreshSubscriptions() = Unit

    private fun handleStoreState(storeState: StoreState) = updateViewState(
        ViewState.Loaded(
            meUser = storeState.meUser,
            isStaffOrAlpha = storeState.isStaffOrAlpha,
            presence = storeState.presence,
            promoCount = storeState.promoCount,
            pushNotificationUpsellDismissed = storeState.pushNotificationUpsellDismissed,
            showRoleSubscriptionsButton = storeState.hasActiveGuildSubscription,
        ),
    )

    companion object {
        fun observeStoreState(
            promoCountSource: Observable<Int> = Observable.just(0),
            hasActiveGuildSubscriptionSource: Observable<Boolean> = Observable.just(false),
        ): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(),
            StoreStream.getPresences().observeAllPresences(),
            promoCountSource,
            hasActiveGuildSubscriptionSource,
        ) { meUser, presences, promoCount, hasActiveGuildSubscription ->
            StoreState(
                meUser = meUser,
                isStaffOrAlpha = meUser.isStaff,
                presence = presences[meUser.id],
                promoCount = promoCount,
                pushNotificationUpsellDismissed = false,
                hasActiveGuildSubscription = hasActiveGuildSubscription,
            )
        }

        fun observeProfileHeaderState(): Observable<UserProfileHeaderViewModel.ViewState> {
            val users = StoreStream.getUsers()
            val settings = StoreStream.getUserSettings()
            return users.observeMe().switchMap { meUser ->
                Observable.combineLatest(
                    StoreStream.getUserProfiles().observeUserProfile(meUser.id),
                    StoreStream.getPresences().observeAllPresences().map { it[meUser.id] },
                    settings.observeIsAnimatedEmojisEnabled(),
                    StoreStream.getAccessibility().observeReducedMotionEnabled(),
                ) { profile: UserProfile, presence: Presence?, animatedEmojis: Boolean, reducedMotion: Boolean ->
                    UserProfileHeaderViewModel.ViewState.Loaded(
                        user = meUser,
                        banner = meUser.banner,
                        bannerColorHex = meUser.bannerColor,
                        guildMember = null,
                        guildMembersForAka = emptyList(),
                        presence = presence,
                        streamContext = null,
                        userProfile = profile,
                        isMeUserPremium = meUser.premiumTier != com.discord.api.premium.PremiumTier.NONE,
                        isMeUserVerified = meUser.isVerified,
                        allowAnimatedEmojis = animatedEmojis,
                        showPresence = true,
                        // This header is a summary; avatar and banner are edited in the profile screen.
                        editable = false,
                        reducedMotionEnabled = reducedMotion,
                        allowAnimationInReducedMotion = false,
                        isMe = true,
                    )
                }
            }
        }
    }
}
