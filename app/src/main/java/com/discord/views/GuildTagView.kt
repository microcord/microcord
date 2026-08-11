package com.discord.views

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.api.user.PrimaryGuild
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.facebook.drawee.view.SimpleDraweeView

class GuildTagView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CardView(context, attrs) {
    private val badge = SimpleDraweeView(context)
    private val label = TextView(context, null, 0, R.style.UiKit_TextView).apply {
        isSingleLine = true
        setTypeface(typeface, Typeface.BOLD)
    }
    private val defaultBackgroundColor =
        ColorCompat.getThemedColor(context, R.attr.colorBackgroundModifierStrong)
    private val defaultTextColors = label.textColors

    init {
        visibility = View.GONE
        radius = dp(4).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(defaultBackgroundColor)
        setContentPadding(dp(4), dp(1), dp(4), dp(1))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(badge)
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(2)
            })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        setSize(12f)
    }

    fun setSize(textSizeSp: Float) {
        val badgeSize = sp(textSizeSp + 2f)
        badge.layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    fun setProfileColors(backgroundColor: Int?, textColor: Int?) {
        setCardBackgroundColor(backgroundColor ?: defaultBackgroundColor)
        label.setTextColor(textColor?.let(ColorStateList::valueOf) ?: defaultTextColors)
    }

    fun bind(primaryGuild: PrimaryGuild?) {
        val guildId = primaryGuild?.identityGuildId
        val tag = primaryGuild?.tag
        val badgeHash = primaryGuild?.badge
        if (primaryGuild?.identityEnabled != true || guildId == null || tag.isNullOrBlank() || badgeHash.isNullOrBlank()) {
            visibility = View.GONE
            setOnClickListener(null)
            return
        }

        label.text = tag
        contentDescription = tag
        IconUtils.setIcon(badge, "${IconUtils.CDN_HOST}/guild-tag-badges/$guildId/$badgeHash.png")
        setOnClickListener {
            context.fragmentActivity()?.let { activity ->
                WidgetGuildProfileSheet.show(activity.supportFragmentManager, false, guildId)
            }
        }
        visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun sp(value: Float) =
        (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics) + 0.5f).toInt()
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}
