package com.discord.widgets.channels

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetChannelOnboardingBinding

class WidgetChannelOnboarding : AppBottomSheet() {
    override fun getContentViewResId(): Int = R.layout.widget_channel_onboarding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ChannelOnboardingManager().markSeenUserChannelOnboarding()
        WidgetChannelOnboardingBinding.bind(view).channelActionsView.setOnClickListener { dismiss() }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            WidgetChannelOnboarding().show(fragmentManager, WidgetChannelOnboarding::class.java.name)
        }
    }
}
