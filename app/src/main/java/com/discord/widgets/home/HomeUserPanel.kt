package com.discord.widgets.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.discord.databinding.WidgetHomeUserPanelBinding
import com.discord.stores.StoreStream
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.user.UserUtils.displayName
import com.discord.views.user.UserAvatarPresenceViewController
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.user.WidgetUserStatusSheet
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers

/**
 * Manticord: the docked (tablet) layout's user area at the bottom of the channel list, like the
 * desktop and React Native tablet clients. It replaces the bottom tab bar: the profile opens the
 * status sheet and the buttons open the tabs the bar would have.
 */
class HomeUserPanel(parent: ViewGroup, private val fragmentManager: FragmentManager) {

    private val binding = WidgetHomeUserPanelBinding.inflate(LayoutInflater.from(parent.context), parent, true)
    private val avatarController = UserAvatarPresenceViewController(binding.userPanelAvatar)
    private var subscription: Subscription? = null

    init {
        binding.userPanelProfile.setOnClickListener { WidgetUserStatusSheet.show(fragmentManager) }
        binding.userPanelFriends.setOnClickListener { StoreStream.getTabsNavigation().selectTab(NavigationTab.FRIENDS) }
        binding.userPanelMentions.setOnClickListener { StoreStream.getTabsNavigation().selectTab(NavigationTab.MENTIONS) }
        binding.userPanelSettings.setOnClickListener { StoreStream.getTabsNavigation().selectTab(NavigationTab.SETTINGS) }
    }

    fun bind() {
        unbind()
        avatarController.userId = StoreStream.getUsers().getMe().id
        avatarController.bind()
        subscription = avatarController.observeState()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { state ->
                val context = binding.root.context
                binding.userPanelName.text = state.user.displayName
                binding.userPanelStatus.text = PresenceUtils.getStatusText(
                    context,
                    state.presence,
                    isStreamingApplication = false,
                    showFallbackStatusText = true,
                )
            }
    }

    fun unbind() {
        avatarController.unbind()
        subscription?.unsubscribe()
        subscription = null
    }
}
