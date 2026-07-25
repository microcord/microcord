package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.api.user.UserProfile
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.widgets.user.WidgetUserStatusSheet
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rx.Observable
import rx.Subscription

class WidgetSettings : Fragment(R.layout.widget_settings) {
    data class State(val user: MeUser, val presence: Presence?)

    private var subscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideUnavailableRows(view)

        val settingsHeader = view.findViewById<TextView>(R.id.user_settings_header)
        ViewCompat.setAccessibilityHeading(settingsHeader, true)

        view.findViewById<View>(R.id.set_status_container).apply {
            contentDescription = getString(R.string.set_status)
            isFocusable = true
            setOnClickListener {
                onSetStatus?.invoke() ?: WidgetUserStatusSheet.show(parentFragmentManager)
            }
        }
        bindRoute(view, R.id.text_images_settings, WidgetSettingsMedia())
        bindRoute(view, R.id.accessibility, WidgetSettingsAccessibility())
        bindRoute(view, R.id.behavior, WidgetSettingsBehavior())
        bindRoute(view, R.id.activity_status, WidgetSettingsActivityStatus())
        view.findViewById<Toolbar>(R.id.action_bar_toolbar).apply {
            setTitle(R.string.user_settings)
            menu.clear()
            inflateMenu(R.menu.menu_settings_logout)
            setOnMenuItemClickListener { item ->
                if (item.itemId != R.id.menu_settings_log_out) return@setOnMenuItemClickListener false
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.logout)
                    .setMessage(R.string.user_settings_confirm_logout)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.logout) { _, _ ->
                        onLogout?.invoke() ?: StoreStream.getAuthentication().logout()
                    }
                    .show()
                true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        onLifecycleStateChanged?.invoke(Lifecycle.State.STARTED)
        subscription = (stateSource?.invoke() ?: defaultStateSource()).distinctUntilChanged().subscribe { state ->
            view?.post {
                if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    render(state)
                }
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        onLifecycleStateChanged?.invoke(Lifecycle.State.CREATED)
        super.onStop()
    }

    private fun render(state: State) {
        requireView().findViewById<UserProfileHeaderView>(R.id.user_settings_profile_header_view).apply {
            contentDescription = displayName(state.user)
            updateViewState(
                UserProfileHeaderViewModel.ViewState.Loaded(
                    user = state.user,
                    banner = state.user.banner,
                    bannerColorHex = state.user.bannerColor,
                    guildMember = null,
                    guildMembersForAka = emptyList(),
                    presence = state.presence,
                    streamContext = null,
                    userProfile = UserProfile(),
                    isMeUserPremium = false,
                    isMeUserVerified = state.user.isVerified,
                    allowAnimatedEmojis = true,
                    showPresence = true,
                    editable = false,
                    reducedMotionEnabled = false,
                    allowAnimationInReducedMotion = false,
                    isMe = true,
                ),
            )
        }
        requireView().findViewById<com.discord.views.StatusView>(R.id.settings_status_view).setPresence(state.presence)
        requireView().findViewById<TextView>(R.id.settings_presence_text).setText(statusText(state.presence?.status))
    }

    private fun hideUnavailableRows(view: View) {
        val keep = setOf(
            R.id.user_settings_header,
            R.id.set_status_container,
            R.id.app_settings_header,
            *implementedRouteIds.toTypedArray(),
        )
        val rows = view.findViewById<View>(R.id.user_settings_header).parent as ViewGroup
        for (index in 0 until rows.childCount) {
            rows.getChildAt(index).visibility = if (rows.getChildAt(index).id in keep) View.VISIBLE else View.GONE
        }
    }

    private fun bindRoute(view: View, rowId: Int, destination: Fragment) {
        view.findViewById<View>(rowId).setOnClickListener {
            parentFragmentManager.commit {
                replace(this@WidgetSettings.id, destination)
                addToBackStack(destination::class.java.name)
            }
        }
    }

    private fun defaultStateSource(): Observable<State> = Observable.combineLatest(
        StoreStream.getUsers().observeMe(),
        StoreStream.getPresences().observeAllPresences(),
    ) { user, presences -> State(user, presences[user.id]) }

    companion object {
        internal val implementedRouteIds = setOf(
            R.id.text_images_settings,
            R.id.accessibility,
            R.id.behavior,
            R.id.activity_status,
        )
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onSetStatus: (() -> Unit)? = null
        @JvmField var onBlockedUsers: (() -> Unit)? = null
        @JvmField var onLogout: (() -> Unit)? = null
        @JvmField var onLifecycleStateChanged: ((Lifecycle.State) -> Unit)? = null

        internal fun displayName(user: MeUser) = if (user.discriminator == 0) {
            user.username
        } else {
            "${user.username}#${user.discriminator.toString().padStart(4, '0')}"
        }

        private fun statusText(status: ClientStatus?) = when (status) {
            ClientStatus.ONLINE -> R.string.status_online
            ClientStatus.IDLE -> R.string.status_idle
            ClientStatus.DND -> R.string.status_dnd
            ClientStatus.INVISIBLE, ClientStatus.OFFLINE, null -> R.string.status_offline
        }
    }
}
