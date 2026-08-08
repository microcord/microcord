package com.discord.rtcconnection.mediaengine

import android.content.Context
import com.discord.app.AppLog
import com.discord.play_delivery.PlayAssetDeliveryNativeWrapper

/**
 * Makes the Krisp models reachable by `libdiscord.so`.
 *
 * The engine opens them through the [android.content.res.AssetManager] as `thz/<model>` — a Play
 * asset pack's `assets/` merge into the app's own, so a pack and a bundled asset are the same thing
 * to it. Discord delivers them on demand, which a rebuilt app cannot do, so they ship in
 * `assets/thz` instead.
 *
 * The location published to [PlayAssetDeliveryNativeWrapper] is not used to build that asset path;
 * it only tells the engine a pack exists at all, and Krisp is skipped while it is empty.
 */
object KrispAssetPack {

    private const val TAG = "KrispAssetPack"
    private const val DIR = "thz"

    /** The exact assets the engine opens; it does not enumerate the directory. */
    private val MODELS = listOf(
        "c5.n.s.20949d.kw",
        "c5.s.w.c9ac8f.kw",
        "c6.f.s.ced125.kw",
        "VAD_model.kw",
    )

    fun install(context: Context) {
        val app = context.applicationContext
        val present = MODELS.all { name ->
            runCatching { app.assets.openFd("$DIR/$name").use { it.length > 0 } }
                .onFailure { AppLog.e(TAG, "Krisp model $name is missing; noise cancellation stays off", it) }
                .getOrDefault(false)
        }
        PlayAssetDeliveryNativeWrapper.setKrispAssetPackLocation(
            if (present) app.applicationInfo.sourceDir else "",
        )
    }

    val isInstalled: Boolean
        get() = PlayAssetDeliveryNativeWrapper.getKrispAssetPackLocation().orEmpty().isNotEmpty()
}
