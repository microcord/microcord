package com.discord.utilities.images

import android.content.Context
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * Shared transport for the APNG assets Fresco cannot decode (nameplates, avatar decorations,
 * profile effects).
 *
 * These are immutable, CDN-cacheable and re-fetched every time their view reattaches, so they get a
 * disk cache; without one, reselecting a DM or reopening a profile re-downloads the whole animation.
 */
object CollectibleAssets {

    @Volatile
    private var installed: OkHttpClient? = null

    fun init(context: Context) {
        val cache = runCatching {
            Cache(File(context.cacheDir, "collectible-assets"), CACHE_BYTES)
        }.getOrNull()
        installed = OkHttpClient.Builder().apply { cache?.let(::cache) }.build()
    }

    val client: OkHttpClient
        get() = installed ?: synchronized(this) {
            installed ?: OkHttpClient().also { installed = it }
        }

    private const val CACHE_BYTES = 64L * 1024 * 1024
}
