package com.discord.utilities.stickers

import android.content.Context
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.StickerFormatType
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.file.DownloadUtils
import com.discord.utilities.icon.IconUtils
import java.io.File
import rx.Observable

object StickerUtils {
    const val ALWAYS_ANIMATE = 0
    const val ANIMATE_ON_INTERACTION = 1
    const val NEVER_ANIMATE = 2
    const val DEFAULT_STICKER_SIZE_DP = 160

    /**
     * `DimenUtils.dpToPixels(160)` in the reference — resolved off the *system* display metrics, so
     * it needs no `Context` and can therefore be the default value of [getCDNAssetUrl]'s `size`,
     * exactly as the reference declares it.
     */
    /**
     * Density is capped at 2 here alone: a sticker is drawn large and often several at a time, and
     * the reference trades the last of the sharpness for the memory on a 3x screen.
     */
    val DEFAULT_STICKER_SIZE_PX: Int by lazy {
        IconUtils.proxySizePx(DEFAULT_STICKER_SIZE_DP, maxDensity = 2f)
    }

    /**
     * Builds the CDN URL for [sticker].
     *
     * [passthrough] is load-bearing for animation: `media.discordapp.net` only serves the *original*
     * APNG when `passthrough=true`. With `passthrough=false` the media proxy re-encodes the asset
     * into a single still frame, so anything downloaded that way can never animate. The reference
     * therefore defaults it to `true` (`StickerUtils.getCDNAssetUrl$default(..., mask 4)` →
     * `iconst_1`) and only passes `false` at the call sites that deliberately want a still image.
     */
    @JvmOverloads
    fun getCDNAssetUrl(
        sticker: BaseSticker,
        size: Int? = DEFAULT_STICKER_SIZE_PX,
        passthrough: Boolean = true,
    ): String = when (sticker.formatType) {
        StickerFormatType.PNG, StickerFormatType.APNG -> buildString {
            append("https://media.discordapp.net/stickers/")
            append(sticker.getStickerId())
            append(sticker.getFileExtension())
            append("?passthrough=")
            append(passthrough)
            size?.let { append("&size=${IconUtils.getMediaProxySize(it)}") }
            // WebP is the only format the proxy can hand back untouched, so it is the only one
            // worth asking not to recompress.
            if (sticker.getFileExtension() == ".${IconUtils.WEBP_IMAGE_EXTENSION}") {
                append("&quality=lossless")
            }
        }
        StickerFormatType.LOTTIE ->
            "https://discord.com/stickers/${sticker.getStickerId()}${sticker.getFileExtension()}"
        StickerFormatType.UNKNOWN -> ""
    }

    /**
     * Downloads the sticker asset that will be handed to the APNG/Lottie renderers, so it must use
     * the pass-through URL — see [getCDNAssetUrl].
     */
    fun fetchSticker(context: Context, sticker: BaseSticker): Observable<DownloadUtils.DownloadState> {
        val directory = File(context.cacheDir, "stickers")
        // The reference creates this directory here and it is load-bearing: FileOutputStream will
        // not create a missing parent, so without it every download fails and the sticker is left
        // showing its placeholder forever (the failure is only logged at warn level). PNG stickers
        // still render because they go through the media proxy rather than this download path, so
        // the symptom presents as "animated stickers don't work" rather than as stickers breaking.
        if (!directory.exists()) directory.mkdirs()
        val fileName = "${sticker.getStickerId()}${sticker.getFileExtension()}"
        val file = File(directory, fileName)
        return if (file.isFile && file.length() > 0L) {
            Observable.just(DownloadUtils.DownloadState.Completed(file))
        } else {
            DownloadUtils.downloadFile(context, getCDNAssetUrl(sticker), fileName, directory)
        }
    }
}
