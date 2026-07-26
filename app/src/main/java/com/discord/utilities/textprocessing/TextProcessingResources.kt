package com.discord.utilities.textprocessing

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Small resource helpers shared by the text processing pipeline.
 *
 * The reference release keeps these in `utilities/color`, `utilities/dimen`, `utilities/icon` and
 * `models/domain/emoji`. Those packages do not exist in this reconstruction yet, so the pipeline
 * carries its own copies; they should be folded into the shared utilities once those packages are
 * reconstructed.
 */

/** Resolves a theme attribute (`?attr/...`) to a concrete colour. */
@ColorInt
internal fun themedColor(context: Context, @AttrRes attribute: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attribute, value, true)
    return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
}

/** Converts dp to px using the given context. */
internal fun dpToPixels(context: Context, dp: Int): Int =
    (dp * context.resources.displayMetrics.density).roundToInt()

/**
 * Converts dp to px without a context.
 *
 * Only used for node defaults that are chosen before a render context exists. Falls back to a 1:1
 * mapping when no display metrics are available, which is the case in JVM unit tests.
 */
internal fun dpToPixels(dp: Int): Int = (dp * systemDensity()).roundToInt()

private fun systemDensity(): Float =
    runCatching { Resources.getSystem().displayMetrics.density }.getOrDefault(1f)

/**
 * CDN image helpers for emoji rendering.
 *
 * Mirrors `ModelEmojiCustom.getImageUri` and `IconUtils.getMediaProxySize` from the reference.
 */
internal object EmojiImages {

    private const val ANIMATED_EXTENSION = "gif"

    /** Sizes the media proxy is willing to serve; requests are rounded up to the next one. */
    private val MEDIA_PROXY_SIZES = intArrayOf(
        16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128, 160, 240, 256, 300,
        320, 480, 512, 600, 640, 1024, 1280, 1536, 2048, 3072, 4096,
    )

    /** WebP is broken on API 28/29 for animated-capable decoding, matching the reference choice. */
    private val staticExtension: String
        get() = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) "png" else "webp"

    fun mediaProxySize(sizePx: Int): Int =
        MEDIA_PROXY_SIZES.firstOrNull { it >= sizePx } ?: MEDIA_PROXY_SIZES.last()

    fun customEmojiUrl(emojiId: Long, animated: Boolean, sizePx: Int): String {
        val extension = if (animated) ANIMATED_EXTENSION else staticExtension
        return "https://cdn.discordapp.com/emojis/$emojiId.$extension?size=$sizePx&quality=lossless"
    }
}

/**
 * Converts a URL to its ASCII (punycode) form so a spoofed unicode host cannot be shown as a
 * legitimate one. Mirrors `StringUtilsKt.toPunyCodeASCIIUrl`.
 */
internal fun toPunyCodeAsciiUrl(url: String): String {
    val parsed = java.net.URL(url)
    val ascii = java.net.URL(
        parsed.protocol,
        java.net.IDN.toASCII(parsed.host, java.net.IDN.ALLOW_UNASSIGNED),
        parsed.port,
        parsed.file,
    ).toString()
    return parsed.ref?.let { "$ascii#$it" } ?: ascii
}
