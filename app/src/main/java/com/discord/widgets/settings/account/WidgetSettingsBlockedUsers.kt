package com.discord.widgets.settings.account

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.discord.app.AppFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.user.NicknameUtils
import com.discord.i18n.i18nFormat
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.widgets.friends.BlockedUsersState
import com.discord.widgets.friends.FriendUserUi
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable
import rx.Subscription

class WidgetSettingsBlockedUsers : AppFragment(R.layout.widget_settings_blocked_users) {
    private lateinit var viewModel: WidgetSettingsBlockedUsersViewModel
    private lateinit var listAdapter: WidgetSettingsBlockedUsersAdapter
    private var stateSubscription: Subscription? = null
    private var eventSubscription: Subscription? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsNavigation.bindBack(this)
        val ignored = mostRecentIntent.getBooleanExtra(EXTRA_IGNORED, false)
        setActionBarTitle(
            if (ignored) R.string.user_settings_ignored_users else R.string.user_settings_blocked_users,
        )
        setActionBarDisplayHomeAsUpEnabled()
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    WidgetSettingsBlockedUsersViewModel(ignored) as T
            },
        )[WidgetSettingsBlockedUsersViewModel::class.java]
        view.findViewById<TextView>(R.id.blocked_users_empty_text).setText(
            if (ignored) {
                R.string.user_settings_ignored_users_empty
            } else {
                R.string.user_settings_blocked_users_empty
            },
        )
        view.findViewById<RecyclerView>(R.id.blocked_users_list_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            listAdapter = WidgetSettingsBlockedUsersAdapter(this).also { adapter = it }
        }
        listAdapter.isIgnoredList = ignored
        listAdapter.onClickUnblock = { viewModel.onClickUnblock(it.id, NicknameUtils.getFriendName(it)) }
        listAdapter.onClickUserProfile = { user ->
            onProfile(
                FriendUserUi(
                    id = user.id,
                    username = user.username,
                    discriminator = user.discriminator,
                    avatarUrl = IconUtils.getForUser(user.id, user.avatar, user.discriminator),
                ),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        stateSubscription = viewModel.observeViewState()
            .distinctUntilChanged()
            .subscribe(::show)
        eventSubscription = viewModel.observeEvents().subscribe { event ->
            when (event) {
                is WidgetSettingsBlockedUsersViewModel.Event.ShowToast -> {
                    val username = event.username
                    if (username == null) {
                        AppToast.show(this, event.stringRes)
                    } else {
                        AppToast.show(this, requireContext().i18nFormat(event.stringRes, "username" to username))
                    }
                }
            }
        }
    }

    override fun onStop() {
        stateSubscription?.unsubscribe()
        stateSubscription = null
        eventSubscription?.unsubscribe()
        eventSubscription = null
        super.onStop()
    }

    private fun show(state: WidgetSettingsBlockedUsersViewModel.ViewState) {
        val flipper = requireView().findViewById<AppViewFlipper>(R.id.blocked_users_flipper)
        when (state) {
            WidgetSettingsBlockedUsersViewModel.ViewState.Uninitialized -> Unit
            WidgetSettingsBlockedUsersViewModel.ViewState.Empty -> flipper.displayedChild = 1
            is WidgetSettingsBlockedUsersViewModel.ViewState.Loaded -> {
                flipper.displayedChild = 0
                listAdapter.setData(state.items)
            }
        }
    }

    companion object {
        private const val EXTRA_IGNORED = "com.discord.intent.extra.EXTRA_IGNORED"

        /** Opens the blocked list, or the ignored list when [ignored] is set. */
        fun launch(context: android.content.Context, ignored: Boolean = false) =
            SettingsNavigation.launch(
                context,
                WidgetSettingsBlockedUsers::class.java,
                android.content.Intent().putExtra(EXTRA_IGNORED, ignored),
            )

        // Retained until StoreStream's old installation sites are removed by their owner.
        @JvmField var stateSource: (() -> Observable<BlockedUsersState>)? = null
        @JvmField var onUnblock: (FriendUserUi) -> Unit = {}
        @JvmField var onProfile: (FriendUserUi) -> Unit = {}
    }
}
