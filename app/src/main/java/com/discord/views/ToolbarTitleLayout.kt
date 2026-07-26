package com.discord.views

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
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
        binding.toolbarIcon.setDrawableOrHide(leadingDrawableRes)
        binding.toolbarTitleTrailingIcon.setDrawableOrHide(trailingDrawableRes)
    }

    fun setSubtitle(subtitle: CharSequence?) {
        binding.toolbarTitleSubtext.setTextAndVisibilityBy(subtitle)
    }

    fun setTitleColor(@ColorInt color: Int) {
        binding.toolbarTitle.setTextColor(color)
    }

    private fun android.widget.ImageView.setDrawableOrHide(@DrawableRes drawableRes: Int?) {
        if (drawableRes == null) {
            visibility = View.GONE
            return
        }
        setImageDrawable(ResourcesCompat.getDrawable(resources, drawableRes, context.theme))
        visibility = View.VISIBLE
    }
}
