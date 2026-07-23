package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import com.discord.R
import com.discord.stores.StoreApplicationStreamPreviews.StreamPreview
import com.discord.utilities.streams.StreamContext.Joinability
import com.facebook.drawee.view.SimpleDraweeView

class StreamPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val image: SimpleDraweeView
    private val overlay: TextView
    private val caption: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_stream_preview, this)
        image = requireNotNull(findViewById(R.id.stream_preview_image))
        overlay = requireNotNull(findViewById(R.id.stream_preview_overlay_text))
        caption = requireNotNull(findViewById(R.id.stream_preview_placeholder_caption))
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @MainThread
    fun configure(preview: StreamPreview, joinability: Joinability, isAlreadyWatchingStream: Boolean) {
        when (preview) {
            StreamPreview.Fetching -> setCaption(context.getString(R.string.stream_preview_loading))
            is StreamPreview.Resolved -> if (preview.url != null) {
                image.setImageURI(preview.url)
                setCaption(null)
            } else {
                image.setImageURI(null as String?)
                setCaption(
                    if (joinability == Joinability.CAN_CONNECT) {
                        context.getString(R.string.stream_no_preview)
                    } else null,
                )
            }
        }
        setOverlay(
            when {
                isAlreadyWatchingStream -> context.getString(R.string.watch_stream)
                joinability == Joinability.CAN_CONNECT -> context.getString(R.string.join_stream)
                joinability == Joinability.VOICE_CHANNEL_FULL -> context.getString(R.string.unable_to_join_channel_full)
                else -> context.getString(R.string.no_screenshare_permission_dialog_body)
            },
        )
    }

    @MainThread
    fun a(preview: StreamPreview, joinability: Joinability, isAlreadyWatchingStream: Boolean) =
        configure(preview, joinability, isAlreadyWatchingStream)

    private fun setCaption(text: CharSequence?) {
        caption.text = text
        caption.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setOverlay(text: CharSequence?) {
        overlay.text = text
        overlay.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
