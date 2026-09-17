package com.discord.views.user

import android.util.Log
import android.view.View
import androidx.annotation.MainThread
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserPresence
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers

/**
 * Drives a [UserAvatarPresenceView] from the stores.
 *
 * Screens that already have a user model configure the view directly. This controller exists for
 * the places that only know a user id — overlays, headers, sheet rows — and need the avatar and
 * presence dot to keep themselves up to date as the user record and presence change.
 *
 * The controller is bound and unbound by its owner, and additionally releases its subscription
 * when the view leaves the window, so a recycled or dismissed view never keeps a store
 * subscription alive.
 */
class UserAvatarPresenceViewController(
    private val view: UserAvatarPresenceView,
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeUserPresence: StoreUserPresence = StoreStream.getPresences(),
) {

    private var subscription: Subscription? = null

    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) = unbind()
    }

    /** User rendered by the view. Changing it while bound re-subscribes to the new user. */
    var userId: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            if (subscription != null) bind()
        }

    fun observeState(): Observable<UserAvatarPresenceView.ViewState> =
        Observable.combineLatest(
            storeUser.observeUser(userId).filter { user -> user != null }.map { user -> user!! },
            storeUserPresence.observePresenceForUser(userId),
        ) { user: User, presence: Presence? ->
            UserAvatarPresenceView.ViewState(
                user = user,
                presence = presence,
                showPresence = true,
            )
        }

    fun configureView(viewState: UserAvatarPresenceView.ViewState) {
        view.configure(viewState)
            view.setAvatarDecoration(viewState.guildMember?.identityAvatarDecoration() ?: viewState.user.avatarDecoration)
    }

    @MainThread
    fun bind() {
        unbind()
        view.addOnAttachStateChangeListener(detachListener)
        subscription = observeState()
            .distinctUntilChanged()
            .observeOn(AndroidSchedulers.mainThread())
            .filter { view.isAttachedToWindow }
            .subscribe(
                { viewState -> configureView(viewState) },
                { throwable -> Log.e(TAG, "error while observing user avatar presence", throwable) },
            )
    }

    @MainThread
    fun unbind() {
        view.removeOnAttachStateChangeListener(detachListener)
        subscription?.unsubscribe()
        subscription = null
    }

    private companion object {
        private const val TAG = "UserAvatarPresence"
    }
}
