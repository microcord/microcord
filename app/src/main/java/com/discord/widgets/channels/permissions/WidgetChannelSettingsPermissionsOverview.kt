package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isCategory
import com.discord.api.channel.isStageVoiceChannel
import com.discord.app.AppFragment
import com.discord.databinding.WidgetChannelSettingsPermissionsOverviewBinding
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.permissions.WidgetChannelSettingsPermissionsOverviewViewModel.Tab
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetChannelSettingsPermissionsOverview : AppFragment() {
    private val channelId: Long get() = arguments?.getLong(EXTRA_CHANNEL_ID)
        ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val isStage by lazy { StoreStream.getChannels().getChannel(channelId)?.isStageVoiceChannel() == true }

    /** Stage channels keep their Moderators view, so only other channels get the Basic/Advanced screen. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() && !isStage }
    private var _binding: WidgetChannelSettingsPermissionsOverviewBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel by viewModels<WidgetChannelSettingsPermissionsOverviewViewModel> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WidgetChannelSettingsPermissionsOverviewViewModel(
                    isStage,
                    modern,
                    SharedPreferencesProvider.getOrNull()?.getBoolean(KEY_ADVANCED_MODE, false) == true,
                ) as T
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(
            if (modern) {
                R.layout.widget_channel_settings_permissions_overview_modern
            } else {
                R.layout.widget_channel_settings_permissions_overview
            },
            container,
            false,
        )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetChannelSettingsPermissionsOverviewBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        binding.segmentedControl.setOnSegmentSelectedChangeListener { index ->
            val tab = when {
                index == 1 -> Tab.ADVANCED
                modern -> Tab.BASIC
                else -> Tab.MODERATOR
            }
            viewModel.selectTab(tab)
            if (modern) SharedPreferencesProvider.getOrNull()?.edit { putBoolean(KEY_ADVANCED_MODE, tab == Tab.ADVANCED) }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
                    setActionBarTitle(
                        when {
                            modern -> R.string.permissions
                            value.isCategory() -> R.string.category_permissions
                            else -> R.string.channel_permissions
                        },
                    )
                    setActionBarSubtitle(value.getDisplayNameOrDefault(requireContext()))
                }
            }
        viewModel.observeViewState().ui(this).subscribe(::renderTabs)
    }

    /** Both layouts put the first tab (Moderators, or Basic) first and Advanced second. */
    private fun renderTabs(state: WidgetChannelSettingsPermissionsOverviewViewModel.ViewState) {
        if (_binding == null) return
        val index = if (state.selectedTab == Tab.ADVANCED) 1 else 0
        binding.segmentedControl.apply {
            visibility = if (state.availableTabs.size > 1) View.VISIBLE else View.GONE
            setSelectedIndex(index)
        }
        binding.flipper.displayedChild = index
    }

    companion object {
        internal const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val KEY_ADVANCED_MODE = "channel_permissions_advanced_mode"
        fun launch(context: Context, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetChannelSettingsPermissionsOverview::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId),
        )
    }
}
