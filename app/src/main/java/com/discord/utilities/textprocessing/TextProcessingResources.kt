package com.discord.utilities.textprocessing

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * Small resource helpers shared by the text processing pipeline.
 *
 * The reference release keeps these in `utilities/color`, `utilities/dimen`, `utilities/icon` and
 * `models/domain/emoji`. Those packages do not exist in this reconstruction yet, so the pipeline
 * carries its own copies; they should be folded into the shared utilities once those packages are
 * reconstructed.
 */

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

    /** WebP is broken on API 28/29 for animated-capable decoding, matching the reference choice. */
    private val staticExtension: String
        get() = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) "png" else "webp"

    fun customEmojiUrl(emojiId: Long, animated: Boolean, sizePx: Int): String {
        val extension = if (animated) ANIMATED_EXTENSION else staticExtension
        return "https://cdn.discordapp.com/emojis/$emojiId.$extension?size=$sizePx&quality=lossless"
    }
}

/**
 * Converts a URL to its ASCII (punycode) form so a spoofed unicode host cannot be shown as a
 * legitimate one. Mirrors `StringUtilsKt.toPunyCodeASCIIUrl`.
 */
private val ALLOWED_URL_SCHEMES = setOf("http", "https", "steam", "mailto", "tel", "sms")

internal fun toPunyCodeAsciiUrl(url: String): String {
    val protocol = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    require(protocol in ALLOWED_URL_SCHEMES) { "Unsupported URL scheme" }
    if (protocol != "http" && protocol != "https") return url
    val parsed = java.net.URL(url)
    val ascii = java.net.URL(
        parsed.protocol,
        java.net.IDN.toASCII(parsed.host, java.net.IDN.ALLOW_UNASSIGNED),
        parsed.port,
        parsed.file,
    ).toString()
    return parsed.ref?.let { "$ascii#$it" } ?: ascii
}
