package com.discord.utilities.images

import com.discord.api.message.attachment.MessageAttachment
import com.discord.utilities.icon.IconUtils

/**
 * Rewrites of media-proxy links that the client has to make before anything can display them.
 *
 * Two of these exist because the proxy will happily hand back something Android cannot draw. AVIF
 * is the sharp case: the format only decodes natively from API 31, and the proxy will transcode on
 * request, so a link is asked for as WebP rather than left to fail on older devices. The other is
 * animation, which for WebP has to be requested explicitly — the same URL without it yields a
 * single frame, which is what makes it usable as a still preview.
 *
 * Everything here preserves the rest of the query untouched, `ex`/`is`/`hm` included: those sign
 * the link and dropping one turns a valid URL into a 403.
 */
object MediaProxyUrl {

    private const val PARAM_FORMAT = "format"
    private const val PARAM_ANIMATED = "animated"

    /** The parameters that sign an attachment link, and mean nothing outside of that. */
    private val SIGNATURE_PARAMS = setOf("ex", "is", "hm")

    /**
     * The display URL for [attachment], transcoded and un-frozen as the format requires.
     *
     * @param url the link being displayed, which is the proxy copy rather than the CDN original —
     *   only the proxy can transcode.
     */
    fun forAttachment(attachment: MessageAttachment, url: String): String {
        var result = url
        if (attachment.isAvif) result = withParam(result, PARAM_FORMAT, IconUtils.staticImageExtension)
        if (attachment.isAnimatableStill) result = withParam(result, PARAM_ANIMATED, "true")
        return result
    }

    /** Same rewrite keyed off the filename, for embeds, which carry no attachment flags. */
    @JvmStatic
    @JvmOverloads
    fun forMedia(url: String, animated: Boolean = false): String {
        var result = url
        if (isAvif(url)) result = withParam(result, PARAM_FORMAT, IconUtils.staticImageExtension)
        if (animated && (isAvif(url) || isWebp(url))) result = withParam(result, PARAM_ANIMATED, "true")
        return result
    }

    /**
     * The link with its signature stripped.
     *
     * For cache keys and equality only: two URLs for the same asset differ every time the signature
     * is refreshed, so comparing them raw makes an unchanged image look like a new one. Never send
     * the result anywhere — it will be rejected.
     */
    fun withoutSignature(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val kept = url.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in SIGNATURE_PARAMS }
        val base = url.substring(0, queryStart)
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    fun isAvif(url: String): Boolean = hasExtension(url, "avif")

    fun isWebp(url: String): Boolean = hasExtension(url, "webp")

    private fun hasExtension(url: String, extension: String): Boolean =
        url.substringBefore('?').endsWith(".$extension", ignoreCase = true)

    /** Replaces [name] when the link already carries it, so a rewrite is never applied twice. */
    private fun withParam(url: String, name: String, value: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return "$url?$name=$value"
        val params = url.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != name }
        return url.substring(0, queryStart) + "?" + (params + "$name=$value").joinToString("&")
    }
}
