package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageChannelAPI

/**
 * Asked of a moderator the moment they enter a stage: do they want to come up as a speaker, or
 * stay in the audience?
 *
 * Choosing "speaker" needs the microphone permission first, so a moderator who declines the
 * runtime prompt stays in the audience instead of joining muted and confused.
 */
class WidgetStageModeratorJoinBottomSheet : AppBottomSheet() {

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    override fun getContentViewResId() = R.layout.widget_stage_moderator_join_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.join_speaker).setOnClickListener {
            requestMicrophone(
                onFailure = {
                    AppToast.show(context, R.string.stage_channel_permission_microphone_denied)
                },
                onSuccess = { setSelfSpeaker() },
            )
        }
        view.findViewById<View>(R.id.join_audience).setOnClickListener { dismiss() }
    }

    private fun setSelfSpeaker() {
        val channel = StoreStream.getChannels().getChannel(channelId) ?: return
        StageChannelAPI.setUserSuppressedInChannel(
            channel = channel,
            userId = StoreStream.getUsers().getMe().id,
            isSuppressed = false,
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetStageModeratorJoinBottomSheet::class.java) { dismiss() }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetStageModeratorJoinBottomSheet().apply {
                arguments = bundleOf(EXTRA_CHANNEL_ID to channelId)
            }.show(fragmentManager, WidgetStageModeratorJoinBottomSheet::class.java.simpleName)
        }
    }
}
