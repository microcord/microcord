package com.discord.widgets.voice.fullscreen

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.dialogs.GuildVideoAtCapacityDialog
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.views.JoinVoiceChannelButton
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet

class WidgetGuildCallOnboardingSheet : AppBottomSheet() {
    private val channelId by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }
    private val viewModel: WidgetGuildCallOnboardingSheetViewModel by appViewModels {
        WidgetGuildCallOnboardingSheetViewModel(channelId)
    }
    private lateinit var connect: JoinVoiceChannelButton

    override fun getContentViewResId() = R.layout.widget_guild_call_onboarding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        connect = view.findViewById(R.id.guild_call_onboarding_connect)
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildCallOnboardingSheet::class.java, onNext = ::configureUI)
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildCallOnboardingSheet::class.java, onNext = ::handleEvent)
    }

    private fun configureUI(viewState: WidgetGuildCallOnboardingSheetViewModel.ViewState) {
        val loaded = viewState as? WidgetGuildCallOnboardingSheetViewModel.ViewState.Loaded ?: return
        connect.alpha = 1f
        when (loaded.joinability) {
            VoiceChannelJoinability.CAN_JOIN,
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY,
            -> {
                connect.setText(R.string.connect_to_voice)
                connect.setOnClickListener { viewModel.onConnectPressed() }
            }
            VoiceChannelJoinability.PERMISSIONS_MISSING -> {
                connect.setText(R.string.channel_locked_short)
                connect.disableWithToast(connect, R.string.channel_locked)
            }
            VoiceChannelJoinability.CHANNEL_FULL -> {
                connect.setText(R.string.unable_to_join_channel_full)
                connect.disableWithToast(connect, R.string.unable_to_join_channel_full)
            }
            VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST -> {
                connect.setText(R.string.connect_to_voice)
                connect.disableWithToast(connect, R.string.guild_settings_public_welcome_invalid_channel)
            }
        }
    }

    private fun handleEvent(event: WidgetGuildCallOnboardingSheetViewModel.Event) {
        when (event) {
            WidgetGuildCallOnboardingSheetViewModel.Event.ShowGuildVideoCapacityDialog ->
                GuildVideoAtCapacityDialog.show(parentFragmentManager)
            is WidgetGuildCallOnboardingSheetViewModel.Event.LaunchGuildCallScreen ->
                WidgetVoiceBottomSheet.show(
                    parentFragmentManager,
                    event.channelId,
                    forwardToFullscreenIfVideoActivated = true,
                    featureContext = WidgetVoiceBottomSheet.FeatureContext.HOME,
                )
        }
        dismiss()
    }

    companion object {
        private const val CACHE_KEY_VOICE_CHANNEL_ONBOARDED = "CACHE_KEY_VOICE_CHANNEL_ONBOARDED"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        @JvmStatic
        fun hasUserSeenVoiceChannelOnboarding(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(CACHE_KEY_VOICE_CHANNEL_ONBOARDED, false)

        @JvmStatic
        fun markSeenUserVoiceChannelOnboarding(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(CACHE_KEY_VOICE_CHANNEL_ONBOARDED, true)
                .apply()
        }

        @JvmStatic
        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetGuildCallOnboardingSheet().apply {
                arguments = bundleOf(EXTRA_CHANNEL_ID to channelId)
                show(fragmentManager, WidgetGuildCallOnboardingSheet::class.java.name)
            }
        }
    }
}
