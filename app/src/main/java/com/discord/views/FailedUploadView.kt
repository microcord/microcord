package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class FailedUploadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {
    private val displayName: TextView
    private val fileImage: ImageView
    private val subtitle: TextView

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_chat_upload, this, false)
        addView(content)
        displayName = requireNotNull(content.findViewById(R.id.display_name))
        requireNotNull<ImageView>(content.findViewById(R.id.failed_alert_icon))
        fileImage = requireNotNull(content.findViewById(R.id.file_image))
        subtitle = requireNotNull(content.findViewById(R.id.subtitle_text))
    }

    @JvmOverloads
    fun configure(title: CharSequence, iconResId: Int, subtitle: String? = null) {
        displayName.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        fileImage.setImageResource(iconResId)
    }
}
