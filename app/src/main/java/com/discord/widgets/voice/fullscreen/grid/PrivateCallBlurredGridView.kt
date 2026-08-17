package com.discord.widgets.voice.fullscreen.grid

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.views.VitoImageView
import com.github.mmin18.widget.RealtimeBlurView

class PrivateCallBlurredGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var callUsers: List<StoreVoiceParticipants.VoiceUser> = emptyList()
    private val gridItems = linkedMapOf<Long, VitoImageView>()
    private val grid = GridLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        columnCount = columns()
    }

    init {
        addView(grid)
        addView(RealtimeBlurView(context, null).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBlurRadius(dpToPixels(64).toFloat())
            setOverlayColor(
                ColorUtils.setAlphaComponent(
                    ColorCompat.getColor(context, R.color.primary_dark_600),
                    OVERLAY_ALPHA,
                ),
            )
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
            // `setIcon` supplies the default-avatar fallback for a user with no avatar set;
            // binding the URL directly would leave those tiles blank.
            IconUtils.setIcon(item, voiceUser.user, guildMember = voiceUser.guildMember)
            item.contentDescription = voiceUser.displayName
            if (orderChanged) grid.addView(item)
        }
        this.callUsers = callUsers
    }

    private fun createItem(fullWidth: Boolean) = VitoImageView(context).apply {
        layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, if (fullWidth) columns() else 1, 1f),
        )
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
    }

    private fun columns() = if (isLandscape) 3 else 2
    private fun maxShownParticipants() = if (isLandscape) 6 else 4
    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private companion object {
        /** 80 % opaque, so the blurred avatars read as a backdrop rather than as content. */
        const val OVERLAY_ALPHA = 204
    }
}
