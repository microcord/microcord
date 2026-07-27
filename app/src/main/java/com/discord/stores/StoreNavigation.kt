package com.discord.stores

import androidx.fragment.app.FragmentActivity
import com.discord.panels.PanelState
import com.discord.models.authentication.AuthState
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.LinkParseResult
import com.discord.widgets.home.PanelLayout
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

data class AuthNavigationState(
    val initialized: Boolean,
    val authenticated: Boolean,
    val initializedForAuthenticatedUser: Boolean = false,
    val requiredAction: RequiredAction? = null,
    val ageGate: StoreNavigation.AgeGate? = null,
)

enum class RequiredAction {
    UPDATE_PASSWORD,
    AGREEMENTS,
    TOS_UPDATE_ACKNOWLEDGMENT,
    REQUIRE_VERIFIED_EMAIL,
    REQUIRE_VERIFIED_PHONE,
    REQUIRE_CAPTCHA,
}

sealed class MainRoute {
    data object Loading : MainRoute()
    data object LoggedOut : MainRoute()
    data object LoggedIn : MainRoute()
    data class Required(val action: RequiredAction) : MainRoute()
    data class AgeGated(val ageGate: StoreNavigation.AgeGate) : MainRoute()
}

sealed interface IntentRouteResult {
    data object Rejected : IntentRouteResult
    data object Queued : IntentRouteResult
    data class External(val uri: String) : IntentRouteResult
}

private sealed interface PendingIntentState {
    data object Empty : PendingIntentState
    data class Value(val contract: AppIntentContract) : PendingIntentState
}

class StoreNavigation internal constructor(private val stream: StoreStream) {
    enum class PanelAction { NOOP, OPEN, CLOSE, UNLOCK_LEFT }
    enum class AgeGate { REGISTER_AGE_GATE, NSFW_CHANNEL_AGE_GATE }

    private val navigationPanelActionSubject = SerializedSubject(BehaviorSubject.create(PanelAction.NOOP))
    private val leftPanelStateSubject = BehaviorSubject.create<PanelState>(PanelState.Closed)
    private val rightPanelStateSubject = BehaviorSubject.create<PanelState>(PanelState.Closed)
    private val mainRouteSubject = BehaviorSubject.create<MainRoute>(MainRoute.Loading)
    private val pendingIntentSubject = BehaviorSubject.create<PendingIntentState>(PendingIntentState.Empty)

    fun getNavigationPanelAction(): Observable<PanelAction> = navigationPanelActionSubject.distinctUntilChanged()
    fun observeLeftPanelState(): Observable<PanelState> = leftPanelStateSubject.distinctUntilChanged()
    fun observeRightPanelState(): Observable<PanelState> = rightPanelStateSubject.distinctUntilChanged()
    fun observeMainRoute(): Observable<MainRoute> = mainRouteSubject.distinctUntilChanged()
    fun getMainRoute(): MainRoute = mainRouteSubject.value
    fun getPendingIntent(): AppIntentContract? = (pendingIntentSubject.value as? PendingIntentState.Value)?.contract
    fun observePendingIntent(): Observable<AppIntentContract> = pendingIntentSubject
        .filter { it is PendingIntentState.Value }
        .map { (it as PendingIntentState.Value).contract }

    fun updateAuthState(state: AuthNavigationState) {
        mainRouteSubject.onNext(resolveMainRoute(state))
    }

    fun updateAuthState(authState: AuthState?, initializedForAuthenticatedUser: Boolean) {
        updateAuthState(
            AuthNavigationState(
                initialized = true,
                authenticated = authState != null,
                initializedForAuthenticatedUser = initializedForAuthenticatedUser,
                requiredAction = if (AuthState.isUpdatePasswordRequired(authState)) RequiredAction.UPDATE_PASSWORD else null,
            ),
        )
    }

    @Synchronized
    fun routeIntent(contract: AppIntentContract): IntentRouteResult = when (contract) {
        AppIntentContract.Unsupported -> IntentRouteResult.Rejected
        is AppIntentContract.Link -> when (val result = contract.result) {
            is LinkParseResult.External -> IntentRouteResult.External(result.uri)
            is LinkParseResult.Internal -> IntentRouteResult.Queued.also {
                pendingIntentSubject.onNext(PendingIntentState.Value(contract))
            }
            is LinkParseResult.Unsupported -> IntentRouteResult.Rejected
        }
        is AppIntentContract.Share, is AppIntentContract.SdkAction ->
            IntentRouteResult.Queued.also { pendingIntentSubject.onNext(PendingIntentState.Value(contract)) }
    }

    @Synchronized
    fun consumePendingIntent(contract: AppIntentContract) {
        if (getPendingIntent() == contract) pendingIntentSubject.onNext(PendingIntentState.Empty)
    }

    fun setLeftPanelState(panelState: PanelState) = leftPanelStateSubject.onNext(panelState)
    fun setRightPanelState(panelState: PanelState) = rightPanelStateSubject.onNext(panelState)

    @StoreThread
    fun handleHomeTabSelected(panelAction: PanelAction) = setNavigationPanelAction(panelAction)

    fun launchNotice(noticeName: String, showAction: (FragmentActivity) -> Boolean) {
        val notices = StoreStream.getNotices()
        val notice = StoreNotices.Notice(
            name = noticeName,
            priority = StoreNotices.PRIORITY_HIGH,
            validScreens = null,
            delayPeriodMs = 0L,
            sinceLastPeriodMs = 0L,
        ) { activity ->
            notices.markSeen(noticeName)
            showAction(activity)
        }
        notices.markInAppSeen()
        notices.requestToShow(notice)
    }

    @JvmOverloads
    fun setNavigationPanelAction(actionType: PanelAction, panelLayout: PanelLayout? = null) {
        if (panelLayout == null) {
            navigationPanelActionSubject.onNext(actionType)
            return
        }
        when (actionType) {
            PanelAction.OPEN -> panelLayout.openStartPanel()
            PanelAction.CLOSE -> panelLayout.closePanels()
            PanelAction.UNLOCK_LEFT -> Unit
            PanelAction.NOOP -> Unit
        }
        navigationPanelActionSubject.onNext(PanelAction.NOOP)
    }

    internal fun reset() {
        navigationPanelActionSubject.onNext(PanelAction.NOOP)
        leftPanelStateSubject.onNext(PanelState.Closed)
        rightPanelStateSubject.onNext(PanelState.Closed)
        pendingIntentSubject.onNext(PendingIntentState.Empty)
        mainRouteSubject.onNext(MainRoute.LoggedOut)
    }

    companion object {
        fun resolveMainRoute(state: AuthNavigationState): MainRoute = when {
            !state.initialized -> MainRoute.Loading
            !state.authenticated -> MainRoute.LoggedOut
            state.requiredAction != null -> MainRoute.Required(state.requiredAction)
            state.ageGate != null -> MainRoute.AgeGated(state.ageGate)
            !state.initializedForAuthenticatedUser -> MainRoute.Loading
            else -> MainRoute.LoggedIn
        }
    }
}
