package com.discord.views

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.discord.utilities.images.MGImages
import com.facebook.drawee.generic.RoundingParams
import com.discord.databinding.ViewToolbarTitleBinding
import com.discord.utilities.view.extensions.setTextAndVisibilityBy

/**
 * Title area installed as the first child of the action bar toolbar.
 *
 * Hosts the title, an optional leading icon, an optional trailing icon, a subtitle line and the
 * presence indicator used by DM/channel headers.
 */
class ToolbarTitleLayout(context: Context) : ConstraintLayout(context) {

    private val binding = ViewToolbarTitleBinding.inflate(LayoutInflater.from(context), this)
    private var hasIcon = false
    private var showingAvatar = false

    val title: android.widget.TextView
        get() = binding.toolbarTitle

    val presence: StatusView
        get() = binding.toolbarPresence

    fun setTitle(
        title: CharSequence?,
        @DrawableRes leadingDrawableRes: Int? = null,
        @DrawableRes trailingDrawableRes: Int? = null,
    ) {
        binding.toolbarTitle.text = title
        hasIcon = leadingDrawableRes != null
        showingAvatar = false
        binding.toolbarAvatar.visibility = View.GONE
        binding.toolbarIcon.setDrawableOrHide(leadingDrawableRes)
        binding.toolbarIdentity.visibility = if (hasIcon) View.VISIBLE else View.GONE
        binding.toolbarTitleTrailingIcon.setDrawableOrHide(trailingDrawableRes)
    }

    fun setSubtitle(subtitle: CharSequence?) {
        binding.toolbarTitleSubtext.setTextAndVisibilityBy(subtitle)
    }

    fun setAvatar(url: String?) {
        if (url == null) return
        showingAvatar = true
        hasIcon = true
        binding.toolbarIcon.visibility = View.GONE
        binding.toolbarAvatar.hierarchy.roundingParams = RoundingParams.asCircle()
        MGImages.setImage(binding.toolbarAvatar, url, width = 24, height = 24, useSmallCache = true)
        binding.toolbarAvatar.visibility = View.VISIBLE
        binding.toolbarIdentity.visibility = View.VISIBLE
    }

    fun setIdentityVisibility(showIcon: Boolean, showTitle: Boolean) {
        binding.toolbarIcon.visibility = if (showIcon && hasIcon && !showingAvatar) View.VISIBLE else View.GONE
        binding.toolbarAvatar.visibility = if (showIcon && showingAvatar) View.VISIBLE else View.GONE
        binding.toolbarIdentity.visibility = if (showIcon && hasIcon) View.VISIBLE else View.GONE
        binding.toolbarTitle.visibility = if (showTitle) View.VISIBLE else View.GONE
        if (!showTitle) {
            binding.toolbarTitleSubtext.visibility = View.GONE
            binding.toolbarPresence.visibility = View.GONE
            binding.toolbarTitleTrailingIcon.visibility = View.GONE
        }
    }

    fun setTitleColor(@ColorInt color: Int) {
        binding.toolbarTitle.setTextColor(color)
    }

    private fun android.widget.ImageView.setDrawableOrHide(@DrawableRes drawableRes: Int?) {
        visibility = if (drawableRes == null) View.GONE else View.VISIBLE
        if (drawableRes != null) setImageDrawable(ResourcesCompat.getDrawable(resources, drawableRes, context.theme))
    }
}
