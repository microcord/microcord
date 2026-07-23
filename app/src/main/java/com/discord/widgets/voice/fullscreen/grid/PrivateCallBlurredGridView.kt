package com.discord.widgets.voice.fullscreen.grid

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.stores.StoreVoiceParticipants
import com.discord.views.user.avatarUrl
import com.facebook.drawee.view.SimpleDraweeView
import com.github.mmin18.widget.RealtimeBlurView

class PrivateCallBlurredGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var callUsers: List<StoreVoiceParticipants.VoiceUser> = emptyList()
    private val gridItems = linkedMapOf<Long, SimpleDraweeView>()
    private val grid = GridLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        columnCount = columns()
    }

    init {
        addView(grid)
        addView(RealtimeBlurView(context, null).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBlurRadius(64.dp.toFloat())
            setOverlayColor(ColorUtils.setAlphaComponent(resolveBackground(), 204))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    fun configure(callUsers: List<StoreVoiceParticipants.VoiceUser>) {
        val shown = callUsers.take(maxShownParticipants())
        val orderChanged = this.callUsers.map { it.user.id } != callUsers.map { it.user.id }
        if (orderChanged) grid.removeAllViews()
        shown.forEachIndexed { index, voiceUser ->
            val fullWidth = shown.size == 2 || (index == shown.lastIndex && (index - 1) % 2 != 0)
            val item = gridItems.getOrPut(voiceUser.user.id) { createItem(fullWidth) }
            item.setImageURI(avatarUrl(voiceUser.user, voiceUser.guildMember))
            item.contentDescription = voiceUser.displayName
            if (orderChanged) grid.addView(item)
        }
        this.callUsers = callUsers
    }

    private fun createItem(fullWidth: Boolean) = SimpleDraweeView(context).apply {
        layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, if (fullWidth) columns() else 1, 1f),
        )
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
    }

    private fun resolveBackground(): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(R.attr.colorBackgroundPrimary, value, true)) {
            if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, value.resourceId) else value.data
        } else Color.BLACK
    }

    private fun columns() = if (isLandscape) 3 else 2
    private fun maxShownParticipants() = if (isLandscape) 6 else 4
    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
