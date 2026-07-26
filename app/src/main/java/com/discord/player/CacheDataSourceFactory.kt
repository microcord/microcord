package com.discord.player

import android.content.Context
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import java.io.File

/**
 * The process-wide on-disk media cache.
 *
 * Discord media is re-watched constantly — the same GIF scrolls past a dozen times — so playback
 * reads through a 100 MB least-recently-used cache in the app cache directory rather than
 * re-downloading. It lives in `cacheDir`, which the system may reclaim under storage pressure; that
 * is the right trade for a pure optimisation.
 *
 * [SimpleCache] must be a singleton: two instances over the same directory corrupt each other's
 * index, and the constructor actively locks the folder.
 *
 * Reference: `b.a.p.l` (`SimpleCacheProvider`).
 */
object SimpleCacheProvider {

    private const val CACHE_DIRECTORY_NAME = "app_media_player"

    /** 100 MB, matching the reference's evictor limit. */
    const val MAX_CACHE_BYTES = 104_857_600L

    @Volatile
    private var cache: SimpleCache? = null

    @JvmStatic
    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIRECTORY_NAME),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}

/**
 * Builds the read-through cache chain used for every progressive media load.
 *
 * On a cache miss the upstream [DefaultDataSourceFactory] fetches and a [CacheDataSink] writes the
 * bytes back; on a hit a plain [FileDataSource] serves them.
 *
 * Two flags matter:
 * - `FLAG_BLOCK_ON_CACHE` makes a second reader of the same URL wait for the first download instead
 *   of starting its own — the common case when a video is opened from the chat list and the
 *   fullscreen viewer immediately opens the same URL.
 * - `FLAG_IGNORE_CACHE_ON_ERROR` means a corrupt or unwritable cache degrades to a direct stream
 *   rather than failing playback.
 *
 * Reference: `b.a.p.j` (`CacheDataSourceFactory`).
 */
class CacheDataSourceFactory(
    private val context: Context,
    private val upstreamFactory: DefaultDataSourceFactory,
    /** Largest single cache fragment. The reference uses the whole cache size, i.e. one file. */
    private val maxCacheFileSize: Long = SimpleCacheProvider.MAX_CACHE_BYTES,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val cache = SimpleCacheProvider.get(context)
        return CacheDataSource(
            cache,
            upstreamFactory.createDataSource(),
            FileDataSource(),
            CacheDataSink(cache, maxCacheFileSize),
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            null,
        )
    }
}
