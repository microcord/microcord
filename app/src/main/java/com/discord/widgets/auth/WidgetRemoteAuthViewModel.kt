package com.discord.widgets.auth

import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.restapi.RestAPIInterface
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Drives "log in on another device by scanning a QR code" from the already-signed-in phone.
 *
 * The fingerprint comes off the scanned `/ra/<fingerprint>` code. Exchanging it for a handshake
 * token is what tells the waiting desktop client that a real, authenticated device is on the other
 * end; nothing is granted until the user then presses Login, which is [remoteLogin].
 *
 * The handshake token is a live credential for someone else's session: it is held in the view state
 * for the length of the screen, is never logged or persisted, and [cancelLogin] revokes it the
 * moment the user backs out.
 *
 * Reference: `com.discord.widgets.auth.WidgetRemoteAuthViewModel`.
 */
class WidgetRemoteAuthViewModel(
    fingerprint: String,
    private val restAPI: RestAPIInterface = RestAPI.api,
) : AppViewModel<WidgetRemoteAuthViewModel.ViewState>(ViewState.Loading) {

    /** `true` = this login expires with the session; the switch on screen is the inverse of it. */
    private val temporary = BehaviorSubject.create(true)

    init {
        if (fingerprint.isBlank()) {
            updateViewState(ViewState.Failed)
        } else {
            restAPI.postRemoteAuthInitialize(RestAPIParams.RemoteAuthInitialize(fingerprint))
                .restSubscribeOn(retry = false)
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetRemoteAuthViewModel::class.java,
                    errorHandler = { error ->
                        // 10056 is "unknown handshake": the code is stale or already used.
                        if (error.code == ERROR_UNKNOWN_HANDSHAKE) updateViewState(ViewState.Failed)
                    },
                ) { handshake ->
                    val isTemporary = temporary.value ?: true
                    // Login stays disabled for a beat so a stray tap on a screen that just changed
                    // under the user's finger cannot authorize a session.
                    updateViewState(ViewState.Loaded(handshake.handshakeToken, isTemporary, loginAllowed = false))
                    Observable.timer(LOGIN_ENABLE_DELAY_MS, TimeUnit.MILLISECONDS)
                        .ui(this)
                        .appSubscribe(errorClass = WidgetRemoteAuthViewModel::class.java) {
                            updateViewState(
                                ViewState.Loaded(handshake.handshakeToken, temporary.value ?: true, loginAllowed = true),
                            )
                        }
                }
        }
    }

    /**
     * Grants the waiting device a session. 126.21 always sends `temporary = false`.
     *
     * The spinner goes up before the request, which is also what makes a double tap harmless: the
     * second one no longer sees a [ViewState.Loaded] and returns. Without that the second request
     * fails on an already-spent handshake and reports failure over a login that worked.
     */
    fun remoteLogin() {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(ViewState.Loading)
        restAPI.postRemoteAuthFinish(RestAPIParams.RemoteAuthFinish(false, loaded.handshakeToken))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetRemoteAuthViewModel::class.java,
                errorHandler = { updateViewState(ViewState.Failed) },
            ) { updateViewState(ViewState.Succeeded) }
    }

    /** Revokes the handshake so the waiting device stops polling. Fire-and-forget, as in the reference. */
    fun cancelLogin() {
        val loaded = viewState as? ViewState.Loaded ?: return
        restAPI.postRemoteAuthCancel(RestAPIParams.RemoteAuthCancel(loaded.handshakeToken))
            .restSubscribeOn()
            .appSubscribe(errorClass = WidgetRemoteAuthViewModel::class.java) { }
    }

    fun toggleTemporary(isTemporary: Boolean) {
        temporary.onNext(isTemporary)
    }

    sealed class ViewState {
        object Loading : ViewState()

        /** The handshake exists; [loginAllowed] gates the button for the first second. */
        data class Loaded(
            val handshakeToken: String,
            val isTemporary: Boolean,
            val loginAllowed: Boolean,
        ) : ViewState() {
            override fun toString(): String =
                "Loaded(handshakeToken=<redacted>, isTemporary=$isTemporary, loginAllowed=$loginAllowed)"
        }

        object Failed : ViewState()
        object Succeeded : ViewState()
    }

    companion object {
        /** `Unknown handshake` — the scanned code has expired or was already consumed. */
        private const val ERROR_UNKNOWN_HANDSHAKE = 10056

        private const val LOGIN_ENABLE_DELAY_MS = 1_000L
    }
}
