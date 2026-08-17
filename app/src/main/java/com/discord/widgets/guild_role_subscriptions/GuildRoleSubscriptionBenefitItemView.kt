package com.discord.widgets.guild_role_subscriptions

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import com.facebook.drawee.drawable.ScalingUtils
import com.discord.views.VitoImageView

class GuildRoleSubscriptionBenefitItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val image: VitoImageView
    private val name: TextView
    private val description: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild_role_subscription_benefit_item, this)
        image = requireNotNull(findViewById(R.id.guild_role_subscription_benefit_image))
        name = requireNotNull(findViewById(R.id.guild_role_subscription_benefit_name))
        description = requireNotNull(findViewById(R.id.guild_role_subscription_benefit_description))
        val padding = resources.getDimensionPixelSize(R.dimen.guild_role_subscription_setup_default_padding)
        setPadding(padding, padding, padding, padding)
        background = ContextCompat.getDrawable(context, R.drawable.ripple_rounded_bg_secondary)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        context.obtainStyledAttributes(attrs, R.styleable.GuildRoleSubscriptionBenefitItemView).run {
            val title = getString(R.styleable.GuildRoleSubscriptionBenefitItemView_benefit_title)
            val imageRes = getResourceId(
                R.styleable.GuildRoleSubscriptionBenefitItemView_benefit_image,
                0,
            )
            if (!title.isNullOrBlank() && imageRes != 0) {
                configureUI(
                    title,
                    imageRes,
                    getString(R.styleable.GuildRoleSubscriptionBenefitItemView_benefit_description),
                )
            }
            recycle()
        }
    }

    fun configureUI(title: String, @DrawableRes imageRes: Int, description: String?) {
        configureTitleAndDescription(title, description)
        image.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.CENTER_INSIDE)
        image.setActualImageResource(imageRes)
    }

    fun configureUI(benefit: Benefit) {
        configureTitleAndDescription(benefit.name, benefit.description)
        image.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.CENTER)
        MGImages.setImage(
            image,
            benefit.emoji.getImageUri(
                false,
                minOf(IconUtils.getMediaProxySize(resources.getDimensionPixelSize(R.dimen.emoji_size)), 64),
                context,
            ),
        )
        image.contentDescription = benefit.emoji.firstName
        setChannelIcon((benefit as? Benefit.ChannelBenefit)?.channelIconResId)
    }

    fun configureUI(title: String, imageUri: String, imageContentDescription: String, description: String?) {
        configureTitleAndDescription(title, description)
        image.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.CENTER)
        image.setImageURI(imageUri)
        image.contentDescription = imageContentDescription
    }

    fun setChannelIcon(@DrawableRes imageRes: Int?) {
        name.setCompoundDrawablesWithIntrinsicBounds(imageRes ?: 0, 0, 0, 0)
    }

    private fun configureTitleAndDescription(title: String, description: String?) {
        name.text = title
        this.description.text = description
        this.description.visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
