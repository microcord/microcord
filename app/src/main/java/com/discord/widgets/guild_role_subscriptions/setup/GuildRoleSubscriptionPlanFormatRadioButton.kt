package com.discord.widgets.guild_role_subscriptions.setup

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Checkable
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.google.android.material.radiobutton.MaterialRadioButton

class GuildRoleSubscriptionPlanFormatRadioButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr), Checkable {
    private val button: MaterialRadioButton
    private val subtitle: TextView
    private val title: TextView
    private val image: ImageView

    init {
        LayoutInflater.from(context).inflate(
            R.layout.view_guild_role_subscription_plan_format_radio_button,
            this,
        )
        button = requireNotNull(findViewById(R.id.guild_role_subscription_plan_format_button))
        subtitle = requireNotNull(findViewById(R.id.guild_role_subscription_plan_format_button_subtitle))
        title = requireNotNull(findViewById(R.id.guild_role_subscription_plan_format_button_title))
        image = requireNotNull(findViewById(R.id.guild_role_subscription_plan_format_image))
        val padding = resources.getDimensionPixelSize(R.dimen.guild_role_subscription_setup_default_padding)
        setPadding(padding, padding, padding, padding)
        background = ContextCompat.getDrawable(
            context,
            R.drawable.bg_guild_role_subscription_plan_format_radio_button,
        )
        context.obtainStyledAttributes(
            attrs,
            R.styleable.GuildRoleSubscriptionPlanFormatRadioButton,
        ).run {
            configureFormat(getInt(R.styleable.GuildRoleSubscriptionPlanFormatRadioButton_format_type, 0))
            recycle()
        }
    }

    override fun isChecked() = button.isChecked

    override fun setChecked(checked: Boolean) {
        isSelected = checked
        image.isSelected = checked
        button.isChecked = checked
    }

    override fun toggle() = setChecked(!isSelected)

    private fun configureFormat(type: Int) {
        when (type) {
            0 -> {
                title.setText(R.string.guild_role_subscription_format_all_channels_name)
                subtitle.setText(R.string.guild_role_subscription_format_all_channels_description)
                image.setImageResource(R.drawable.ic_key_24dp)
            }
            1 -> {
                title.setText(R.string.guild_role_subscription_format_some_channels_name)
                subtitle.setText(R.string.guild_role_subscription_format_some_channels_description)
                image.setImageResource(R.drawable.ic_channel_text)
            }
        }
    }
}
