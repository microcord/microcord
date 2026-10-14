package com.discord.widgets.settings.themes

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import com.discord.R
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeLinks
import com.discord.utilities.theme.ThemeResolver

/**
 * A miniature Discord window in a theme's colors: server rail, channel list and a chat with a
 * mention and a link.
 *
 * Painted from resolved colors, so it shows a theme without applying it. That is what lets the
 * editor preview every change instantly and the theme list show what each theme looks like.
 */
internal class ThemePreviewDrawable : Drawable() {

    /** Colors in the order of the attributes [colorsOf] reads. */
    var colors: IntArray = IntArray(ATTR_IDS.size)
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val unit = h / 16f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), unit, unit, Path.Direction.CW)
        canvas.clipPath(clip)

        val railWidth = h * 0.22f
        val sidebarWidth = (w - railWidth) * 0.36f
        fill(canvas, RAIL, 0f, 0f, railWidth, h)
        fill(canvas, SIDEBAR, railWidth, 0f, railWidth + sidebarWidth, h)
        fill(canvas, CHAT, railWidth + sidebarWidth, 0f, w, h)

        // Server icons: the selected one in the brand color.
        val icon = railWidth * 0.32f
        circle(canvas, BRAND, railWidth / 2, unit * 2.5f, icon)
        circle(canvas, CHAT, railWidth / 2, unit * 2.5f + icon * 2.6f, icon)
        circle(canvas, CHAT, railWidth / 2, unit * 2.5f + icon * 5.2f, icon)

        // Channels: the second one selected.
        val channelLeft = railWidth + unit
        val channelRight = railWidth + sidebarWidth - unit
        for (i in 0 until 4) {
            val top = unit * (2f + i * 2.4f)
            if (i == 1) fill(canvas, SELECTED, channelLeft - unit * 0.4f, top - unit * 0.6f, channelRight, top + unit * 1.4f, unit * 0.5f)
            bar(canvas, if (i == 1) ACTIVE else CHANNEL, channelLeft + unit * 0.4f, top, channelLeft + (channelRight - channelLeft) * (0.8f - i * 0.1f), unit * 0.8f)
        }

        // Messages: author and text, a highlighted mention, then a link.
        val chatLeft = railWidth + sidebarWidth + unit
        val textLeft = chatLeft + unit * 3f
        val chatRight = w - unit
        message(canvas, chatLeft, textLeft, chatRight, unit * 1.6f, unit, lines = 2, link = false)
        fill(canvas, MENTIONED, railWidth + sidebarWidth, unit * 6.6f, w, unit * 11f)
        message(canvas, chatLeft, textLeft, chatRight, unit * 7.2f, unit, lines = 1, link = false)
        bar(canvas, MENTION, textLeft + (chatRight - textLeft) * 0.55f, unit * 9.6f, chatRight, unit * 0.8f)
        message(canvas, chatLeft, textLeft, chatRight, unit * 11.8f, unit, lines = 1, link = true)
        canvas.restore()
    }

    private fun message(canvas: Canvas, left: Float, textLeft: Float, right: Float, top: Float, unit: Float, lines: Int, link: Boolean) {
        circle(canvas, BRAND, left + unit, top + unit, unit)
        bar(canvas, HEADER, textLeft, top, textLeft + (right - textLeft) * 0.35f, unit * 0.8f)
        bar(canvas, MUTED, textLeft + (right - textLeft) * 0.4f, top + unit * 0.15f, textLeft + (right - textLeft) * 0.55f, unit * 0.5f)
        for (line in 0 until lines) {
            val y = top + unit * (1.6f + line * 1.2f)
            bar(canvas, TEXT, textLeft, y, textLeft + (right - textLeft) * (0.9f - line * 0.3f), unit * 0.7f)
        }
        if (link) bar(canvas, LINK, textLeft, top + unit * 2.8f, textLeft + (right - textLeft) * 0.5f, unit * 0.7f)
    }

    private fun fill(canvas: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) {
        paint.color = colors[color]
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun bar(canvas: Canvas, color: Int, l: Float, t: Float, r: Float, height: Float) =
        fill(canvas, color, l, t, r, t + height, height / 2)

    private fun circle(canvas: Canvas, color: Int, cx: Float, cy: Float, radius: Float) {
        paint.color = colors[color]
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The theme attributes the preview paints with, in [colors] order. */
        private val ATTR_IDS = intArrayOf(
            R.attr.colorBackgroundTertiary, R.attr.colorBackgroundSecondary, R.attr.colorBackgroundPrimary, R.attr.color_brand,
            R.attr.colorChannelDefault, R.attr.colorBackgroundModifierSelected, R.attr.colorInteractiveActive,
            R.attr.colorHeaderPrimary, R.attr.colorTextNormal, R.attr.colorTextMuted, R.attr.colorTextLink,
            R.attr.theme_chat_mentioned_me, R.attr.theme_chat_mention_foreground,
        )
        private const val RAIL = 0
        private const val SIDEBAR = 1
        const val CHAT = 2
        private const val BRAND = 3
        private const val CHANNEL = 4
        private const val SELECTED = 5
        private const val ACTIVE = 6
        private const val HEADER = 7
        const val TEXT = 8
        private const val MUTED = 9
        private const val LINK = 10
        private const val MENTIONED = 11
        private const val MENTION = 12

        /** [theme]'s colors for the preview; with no theme, the built-in [base]'s. */
        fun colorsOf(context: Context, theme: ThemeDefinition?, base: String): IntArray {
            val themeBase = theme?.base ?: base
            val themed = ContextThemeWrapper(context.applicationContext, ThemeLinks.baseStyle(themeBase))
            val resolved = theme?.let { ThemeLinks.link(ThemeResolver.resolve(it), ThemeLinks.baseColors(context, themeBase)).attrs }.orEmpty()
            val value = TypedValue()
            return IntArray(ATTR_IDS.size) { i ->
                val id = ATTR_IDS[i]
                resolved[themed.resources.getResourceEntryName(id)]
                    ?: if (themed.theme.resolveAttribute(id, value, true)) value.data else 0
            }
        }
    }
}
