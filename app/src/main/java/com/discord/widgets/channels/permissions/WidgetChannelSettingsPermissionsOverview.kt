package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isCategory
import com.discord.api.channel.isStageVoiceChannel
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.views.segmentedcontrol.SegmentedControlContainer
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetChannelSettingsPermissionsOverview : AppFragment(R.layout.widget_channel_settings_permissions_overview) {
    private val channelId: Long get() = arguments?.getLong(EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val viewModel by viewModels<WidgetChannelSettingsPermissionsOverviewViewModel> {
        val stage = StoreStream.getChannels().getChannel(channelId)?.isStageVoiceChannel() == true
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WidgetChannelSettingsPermissionsOverviewViewModel(stage) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<SegmentedControlContainer>(R.id.segmented_control).apply {
            setOnSegmentSelectedChangeListener { index ->
                viewModel.selectTab(if (index == 0) WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR else WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED)
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val channel = StoreStream.getChannels().observeChannel(channelId)
        Observable.combineLatest(
            channel,
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForChannel(channelId),
        ) { value, me, permissions -> Triple(value, me, permissions) }
            .ui(this)
            .subscribe { (value, me, permissions) ->
                val guild = value?.let { StoreStream.getGuilds().getGuild(it.guildId) }
                if (value == null || guild == null || !PermissionEditor.canManage(guild, me, permissions)) {
                    requireActivity().onBackPressed()
                } else {
                    setActionBarTitle(if (value.isCategory()) R.string.category_permissions else R.string.channel_permissions)
                    setActionBarSubtitle(value.getDisplayNameOrDefault(requireContext()))
                }
            }
        viewModel.observeViewState().ui(this).subscribe(::renderTabs)
    }

    private fun renderTabs(state: WidgetChannelSettingsPermissionsOverviewViewModel.ViewState) {
        view?.findViewById<SegmentedControlContainer>(R.id.segmented_control)?.apply {
            visibility = if (state.availableTabs.size > 1) View.VISIBLE else View.GONE
            setSelectedIndex(if (state.selectedTab == WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR) 0 else 1)
        }
        view?.findViewById<AppViewFlipper>(R.id.flipper)?.displayedChild =
            if (state.selectedTab == WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR) 0 else 1
    }

    companion object {
        internal const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        fun launch(context: Context, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetChannelSettingsPermissionsOverview::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
