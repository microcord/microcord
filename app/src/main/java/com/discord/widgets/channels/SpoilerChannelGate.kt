package com.discord.widgets.channels

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.widgets.notice.WidgetNoticeDialog

/**
 * The confirmation shown before joining a spoiler voice channel.
 *
 * A text channel gets a full-screen gate instead; a voice channel has no content to cover, so the
 * reference asks in a dialog and joins on agreement.
 */
object SpoilerChannelGate {

    /** @return true when the gate was shown, meaning the caller should not join yet. */
    fun maybeShowForVoiceChannel(
        context: Context,
        fragmentManager: FragmentManager,
        channelId: Long,
        onAgree: () -> Unit,
    ): Boolean {
        val channel = StoreStream.getChannels().getChannel(channelId) ?: return false
        val gatingChannelId = StoreStream.getChannelSpoilerAgree().getSpoilerGatingChannelId(channel)
            ?: return false
        WidgetNoticeDialog.show(
            fragmentManager = fragmentManager,
            headerText = context.getString(R.string.spoiler_channel_gate_header),
            bodyText = context.getString(R.string.spoiler_channel_voice_gate_cta_description_full),
            okText = context.getString(R.string.yes_text),
            cancelText = context.getString(R.string.go_back),
            listenerMap = mapOf(R.id.notice_ok to { _: View ->
                StoreStream.getChannelSpoilerAgree().agree(gatingChannelId)
                onAgree()
            }),
        )
        return true
    }
}
