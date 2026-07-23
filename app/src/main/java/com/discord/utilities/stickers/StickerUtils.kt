package com.discord.utilities.stickers

import android.content.Context
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.StickerFormatType
import com.discord.utilities.file.DownloadUtils
import java.io.File
import rx.Observable

object StickerUtils {
    const val ALWAYS_ANIMATE = 0
    const val ANIMATE_ON_INTERACTION = 1
    const val NEVER_ANIMATE = 2
    const val DEFAULT_STICKER_SIZE_DP = 160

    fun getDefaultStickerSizePx(context: Context): Int =
        (DEFAULT_STICKER_SIZE_DP * context.resources.displayMetrics.density).toInt()

    @JvmOverloads
    fun getCDNAssetUrl(
        sticker: BaseSticker,
        size: Int? = null,
        passthrough: Boolean = false,
    ): String = when (sticker.formatType) {
        StickerFormatType.PNG, StickerFormatType.APNG -> buildString {
            append("https://media.discordapp.net/stickers/")
            append(sticker.getStickerId())
            append(sticker.getFileExtension())
            append("?passthrough=")
            append(passthrough)
            size?.let { append("&size=${mediaProxySize(it)}") }
        }
        StickerFormatType.LOTTIE ->
            "https://discord.com/stickers/${sticker.getStickerId()}${sticker.getFileExtension()}"
        StickerFormatType.UNKNOWN -> ""
    }

    fun fetchSticker(context: Context, sticker: BaseSticker): Observable<DownloadUtils.DownloadState> {
        val directory = File(context.cacheDir, "stickers")
        val fileName = "${sticker.getStickerId()}${sticker.getFileExtension()}"
        val file = File(directory, fileName)
        return if (file.isFile && file.length() > 0L) {
            Observable.just(DownloadUtils.DownloadState.Completed(file))
        } else {
            DownloadUtils.downloadFile(context, getCDNAssetUrl(sticker), fileName, directory)
        }
    }

    internal fun effectiveAnimationSetting(setting: Int, reducedMotion: Boolean): Int =
        if (setting == ALWAYS_ANIMATE && reducedMotion) ANIMATE_ON_INTERACTION else setting

    private fun mediaProxySize(size: Int): Int {
        return MEDIA_PROXY_SIZES.first { it >= size }
    }

    private val MEDIA_PROXY_SIZES = intArrayOf(
        16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
        160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048, 3072, 4096,
    )
}
