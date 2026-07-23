package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import com.discord.R
import com.discord.widgets.guildscheduledevent.buttonconfiguration.ButtonConfiguration

class GuildScheduledEventBottomButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val primaryButton: ImageView
    private val primaryText: TextView
    private val secondaryButton: ImageView
    private val secondaryText: TextView
    private val shareButton: ImageView
    private val extraButton: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_scheduled_event_bottom_button_view, this)
        primaryButton = requireNotNull(findViewById(R.id.primary_button))
        primaryText = requireNotNull(findViewById(R.id.primary_button_text))
        secondaryButton = requireNotNull(findViewById(R.id.secondary_button))
        secondaryText = requireNotNull(findViewById(R.id.secondary_button_text))
        shareButton = requireNotNull(findViewById(R.id.share_button))
        extraButton = requireNotNull(findViewById(R.id.extra_button))
    }

    fun configure(configuration: ButtonConfiguration) {
        primaryText.text = configuration.primaryButtonText(context)
        primaryText.visible(configuration.primaryButtonVisible)
        primaryButton.visible(configuration.primaryButtonVisible)
        primaryButton.setOnClickListener(configuration.primaryButtonOnClickListener)

        secondaryButton.visible(configuration.secondaryButtonVisible)
        secondaryButton.setImageResource(configuration.secondaryButtonBackground)
        secondaryButton.setOnClickListener(configuration.secondaryButtonOnClickListener)
        secondaryButton.isEnabled = configuration.secondaryButtonEnabled
        secondaryText.visible(configuration.secondaryButtonVisible)
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
            secondaryText,
            configuration.secondaryButtonTextDrawableRes,
            0,
            0,
            0,
        )
        secondaryText.setTextColor(configuration.secondaryButtonTextColor(context))
        secondaryText.text = configuration.secondaryButtonText(context)

        shareButton.visible(configuration.isShareVisible)
        shareButton.setOnClickListener(configuration.shareButtonOnClickListener)
        extraButton.visibility = View.GONE
        extraButton.setOnClickListener(null)
    }

    fun configureForDetails(configuration: ButtonConfiguration) {
        configure(configuration)
        if (configuration.primaryButtonVisible) {
            secondaryButton.visibility = View.GONE
            secondaryText.visibility = View.GONE
        }
        val showExtra = configuration.isExtrasVisible && configuration.extrasButtonOnClickListener != null
        extraButton.visible(showExtra)
        extraButton.setOnClickListener(configuration.extrasButtonOnClickListener)
    }

    private fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }
}
