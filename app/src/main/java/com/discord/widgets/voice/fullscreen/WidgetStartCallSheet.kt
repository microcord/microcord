package com.discord.widgets.voice.fullscreen

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.widgets.voice.call.callAndLaunch

class WidgetStartCallSheet : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_start_call_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val channelId = argumentsOrDefault.getLong(ARG_VOICE_CHANNEL_ID)
        view.findViewById<View>(R.id.start_call_sheet_voice_item).setOnClickAndDismissListener {
            launch(channelId, isVideo = false)
        }
        view.findViewById<View>(R.id.start_call_sheet_video_item).setOnClickAndDismissListener {
            launch(channelId, isVideo = true)
        }
    }

    private fun launch(channelId: Long, isVideo: Boolean) = callAndLaunch(
        channelId = channelId,
        isVideo = isVideo,
        appPermissionsRequests = this,
        context = requireContext(),
        appComponent = this,
        fragmentManager = parentFragmentManager,
    )

    companion object {
        private const val ARG_VOICE_CHANNEL_ID = "ARG_VOICE_CHANNEL_ID"

        @JvmStatic
        fun show(voiceChannelId: Long, fragmentManager: FragmentManager) {
            WidgetStartCallSheet().apply {
                arguments = bundleOf(ARG_VOICE_CHANNEL_ID to voiceChannelId)
                show(fragmentManager, WidgetStartCallSheet::class.java.name)
            }
        }
    }
}
