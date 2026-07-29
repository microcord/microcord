package com.discord.widgets.servers.community

import android.view.View
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.WidgetChannelSelector

class WidgetServerSettingsCommunitySecondStep :
    AppFragment(R.layout.widget_server_settings_community_setup_second_step) {

    private val viewModel: WidgetServerSettingsEnableCommunityViewModel by enableCommunityViewModel()
    private lateinit var rulesChannel: CommunitySelectorView
    private lateinit var updatesChannel: CommunitySelectorView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        rulesChannel = view.findViewById(R.id.community_guidelines_channel)
        updatesChannel = view.findViewById(R.id.community_updates_channel)
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_RULES_CHANNEL) { channelId, _ ->
            val channel = StoreStream.getChannels().getChannel(channelId)
            viewModel.modifyGuildConfig { it.copy(rulesChannel = channel, rulesChannelId = channelId) }
        }
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_UPDATES_CHANNEL) { channelId, _ ->
            val channel = StoreStream.getChannels().getChannel(channelId)
            viewModel.modifyGuildConfig { it.copy(updatesChannel = channel, updatesChannelId = channelId) }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { state ->
            if (state is WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) configureUI(state)
        }
    }

    private fun configureUI(state: WidgetServerSettingsEnableCommunityViewModel.ViewState.Loaded) {
        val config = state.communityGuildConfig
        rulesChannel.setSubtitle(
            config.rulesChannel?.getDisplayNameOrDefault(requireContext(), includePrefix = false)
                ?: getString(R.string.enable_public_modal_create_channel),
        )
        updatesChannel.setSubtitle(
            config.updatesChannel?.getDisplayNameOrDefault(requireContext(), includePrefix = false)
                ?: getString(R.string.enable_public_modal_create_channel),
        )
        rulesChannel.setOnClickListener {
            WidgetChannelSelector.launchForText(this, viewModel.guildId, REQUEST_KEY_RULES_CHANNEL)
        }
        updatesChannel.setOnClickListener {
            WidgetChannelSelector.launchForText(this, viewModel.guildId, REQUEST_KEY_UPDATES_CHANNEL)
        }
    }

    companion object {
        private const val REQUEST_KEY_RULES_CHANNEL = "REQUEST_KEY_RULES_CHANNEL"
        private const val REQUEST_KEY_UPDATES_CHANNEL = "REQUEST_KEY_UPDATES_CHANNEL"
    }
}
