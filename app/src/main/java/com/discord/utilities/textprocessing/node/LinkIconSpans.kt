package com.discord.utilities.textprocessing.node

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.discord.utilities.images.setVitoSpan
import com.discord.utilities.textprocessing.dpToPixels

/**
 * Inserts a leading icon into a [DraweeSpanStringBuilder], so a Discord link can render as the
 * React Native client does: an icon followed by text inside one pill.
 *
 * [builder] must be a [DraweeSpanStringBuilder] for the image span to take effect; on a plain
 * builder this is a no-op so surfaces without Fresco spans still render the text.
 */
internal object LinkIconSpans {

    /** Appends a zero-width placeholder replaced by a local drawable tinted [color], on [backgroundColor]. */
    fun appendLocalIcon(
        builder: SpannableStringBuilder,
        context: Context,
        resId: Int,
        color: Int,
        backgroundColor: Int,
        sizeDp: Int = 16,
    ) {
        val size = dpToPixels(context, sizeDp)
        val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)?.mutate()
            ?: run {
                builder.append(" ")
                return
            }
        DrawableCompat.setTint(drawable, color)
        drawable.setBounds(0, 0, size, size)
        val start = builder.length
        builder.append(ICON_PLACEHOLDER)
        builder.setSpan(
            LinkPillIconSpan(drawable, backgroundColor),
            start,
            start + ICON_PLACEHOLDER.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** Appends a zero-width placeholder replaced by a circular avatar fetched from [url]. */
    fun appendAvatarIcon(
        builder: SpannableStringBuilder,
        context: Context,
        url: String,
        backgroundColor: Int,
        sizeDp: Int = 16,
    ) {
        val drawee = builder as? DraweeSpanStringBuilder ?: run {
            builder.append(" ")
            return
        }
        val size = dpToPixels(context, sizeDp)
        val start = builder.length
        builder.append(ICON_PLACEHOLDER)
        drawee.setVitoSpan(
            context = context,
            url = url,
            start = start,
            end = start + ICON_PLACEHOLDER.length - 1,
            width = size,
            height = size,
            alignment = 2,
            circular = true,
            background = ColorDrawable(backgroundColor),
            resizeWidth = size,
            resizeHeight = size,
        )
    }

    private const val ICON_PLACEHOLDER = "\u00A0"
}
