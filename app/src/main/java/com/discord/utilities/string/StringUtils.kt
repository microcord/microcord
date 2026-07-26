@file:JvmName("StringUtils")

package com.discord.utilities.string

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StringRes
import com.discord.utilities.locale.LocaleManager
import java.net.IDN
import java.net.URL
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

/**
 * Image extension requested for static (non-animated) renditions.
 *
 * WebP is smaller, but the platform decoder on API 28/29 mis-renders some of Discord's static
 * WebP assets, so those two releases fall back to PNG.
 */
val STATIC_IMAGE_EXTENSION: String =
    if (Build.VERSION.SDK_INT in listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q)) "png" else "webp"

private val STRIP_ACCENTS_REGEX = Regex("[\\p{InCombiningDiacriticalMarks}]")

/** Formats a number using the app locale's grouping separators. */
fun Int.format(context: Context): String =
    NumberFormat.getInstance(LocaleManager().getPrimaryLocale(context)).format(this)

/**
 * Loads a string resource in an explicitly requested [locale] rather than the app's own.
 *
 * @return null when the resource does not exist for that locale.
 */
fun Context.getStringByLocale(@StringRes resId: Int, locale: String): CharSequence? = try {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale(locale))
    createConfigurationContext(configuration).resources.getString(resId)
} catch (e: Resources.NotFoundException) {
    null
}

/** Replaces path separators so the value is safe to use as a single file name component. */
fun CharSequence.filenameSanitized(): String = Regex("[/\\\\]").replace(this, "_")

/**
 * Removes diacritics so accented text still matches an unaccented search query.
 */
fun String.stripAccents(): String =
    STRIP_ACCENTS_REGEX.replace(Normalizer.normalize(this, Normalizer.Form.NFKD), "")

/**
 * Converts a URL's host to its Punycode (ASCII) form.
 *
 * Displaying and comparing the ASCII host is what stops visually identical Unicode hosts from
 * impersonating a trusted domain.
 */
fun String.toPunyCodeASCIIUrl(): String {
    val url = URL(this)
    val asciiUrl = URL(url.protocol, IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED), url.port, url.file)
    val builder = StringBuilder(asciiUrl.toString())
    url.ref?.let {
        builder.append('#')
        builder.append(it)
    }
    return builder.toString()
}

/** Applies [transform] when both the receiver and the transform are present, else returns "". */
fun String?.transformOrEmpty(transform: ((String?) -> String?)?): String =
    transform?.invoke(this) ?: this ?: ""

/**
 * Base32 (RFC 4648 alphabet, no padding) encoding, used for TOTP secrets.
 */
fun ByteArray.encodeToBase32String(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val output = StringBuilder((size + 7) * 8 / 5)
    var index = 0
    var bitOffset = 0
    while (index < size) {
        val current = this[index].toInt() and 0xFF
        val digit: Int
        if (bitOffset > 3) {
            index += 1
            val next = if (index < size) this[index].toInt() and 0xFF else 0
            bitOffset = (bitOffset + 5) % 8
            digit = ((current and (0xFF shr bitOffset)) shl bitOffset) or (next shr (8 - bitOffset))
        } else {
            val nextOffset = bitOffset + 5
            digit = (current shr (8 - nextOffset)) and 0x1F
            bitOffset = nextOffset % 8
            if (bitOffset == 0) index += 1
        }
        output.append(alphabet[digit])
    }
    return output.toString()
}
