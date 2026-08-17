package com.discord.utilities.images

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.facebook.cache.disk.DiskCacheConfig
import com.facebook.common.internal.Supplier
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.common.executors.UiThreadImmediateExecutorService
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.ListVitoImagePerfListener
import com.facebook.fresco.vito.core.impl.DefaultImageDecodeOptionsProviderImpl
import com.facebook.fresco.vito.core.impl.ImagePipelineUtilsImpl
import com.facebook.fresco.vito.provider.components.FrescoVitoComponents
import com.facebook.fresco.vito.provider.impl.DefaultFrescoVitoProvider
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.cache.DefaultBitmapMemoryCacheParamsSupplier
import com.facebook.imagepipeline.cache.MemoryCacheParams
import com.facebook.imagepipeline.core.DownsampleMode
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.request.ImageRequest
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Process-wide Fresco configuration.
 *
 * Reference: `com/discord/utilities/images/MGImagesConfig.java`.
 *
 * Two on-disk caches are configured because [MGImages.getImageRequest] routes almost every request
 * into Fresco's *small* image cache, keeping avatars and guild icons from being evicted by large
 * attachments and animated content. Both caches live under the app cache directory and are capped
     * at [MAX_DISK_CACHE_SIZE].
     *
     * Vito defaults to the small-image disk cache so emoji and avatars share one working set.
     */
object MGImagesConfig {

    private const val CACHE_DIR = "app_images_cache"
    private const val CACHE_DIR_SMALL = "app_images_cache_small"

    /** 40 MiB per disk cache. */
    private const val MAX_DISK_CACHE_SIZE = 41_943_040L

    /**
     * A single cached bitmap may occupy at most `1 / MAX_BITMAP_MEM_CACHE_SIZE_RATIO` of the whole
     * bitmap memory cache, so one large image cannot flush the entire working set.
     */
    private const val MAX_BITMAP_MEM_CACHE_SIZE_RATIO = 3

    private val PARAMS_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    fun init(context: Application) {
        val builder = ImagePipelineConfig.newBuilder(context)
            .setDownsampleMode(DownsampleMode.ALWAYS)
            .setMainDiskCacheConfig(newDiskCacheConfig(context, CACHE_DIR))
            .setSmallImageDiskCacheConfig(newDiskCacheConfig(context, CACHE_DIR_SMALL))
            .setBitmapMemoryCacheParamsSupplier(getAppBitmapMemoryCacheParamsSupplier(context))
            .setNetworkFetcher(OkHttpNetworkFetcher())
        builder.experiment()
            .setShouldDownscaleFrameToDrawableDimensions(true)
        val config = builder.build()
        Fresco.initialize(context, config)
        ImageOptions.setDefaults(
            ImageOptions.create()
                .cacheChoice(ImageRequest.CacheChoice.SMALL)
                .fadeDurationMs(0)
                .releaseOnWindowDetached(false)
                .build(),
        )
        FrescoVitoComponents.setImplementation(
            DefaultFrescoVitoProvider(
                DefaultFrescoVitoConfig(),
                Fresco.getImagePipeline(),
                ImagePipelineUtilsImpl(
                    DefaultImageDecodeOptionsProviderImpl { ImageDecodeOptions.defaults() },
                ),
                config.executorSupplier.forLightweightBackgroundTasks(),
                UiThreadImmediateExecutorService.getInstance(),
                { _, _ -> },
                ListVitoImagePerfListener(),
            ),
        )
    }

    /**
     * Drops both memory caches on the trim levels that indicate real pressure.
     *
     * `TRIM_MEMORY_UI_HIDDEN` is deliberately not handled: the process is still foreground-warm and
     * the user is likely to come straight back.
     */
    fun onTrimMemory(level: Int) {
        val shouldTrim = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (!shouldTrim) return
        if (!Fresco.hasBeenInitialized()) return
        Fresco.getImagePipeline().clearMemoryCaches()
    }

    /**
     * Fresco's device-class defaults for the three memory tiers (total size, entry count and
     * eviction queue), plus the two values Discord overrides: the per-entry ceiling and a five
     * minute re-evaluation interval so the cache tracks changes in available heap.
     */
    private fun getAppBitmapMemoryCacheParamsSupplier(
        context: Context,
    ): DefaultBitmapMemoryCacheParamsSupplier {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return object : DefaultBitmapMemoryCacheParamsSupplier(activityManager) {
            override fun get(): MemoryCacheParams {
                val defaults = super.get()
                return MemoryCacheParams(
                    defaults.maxCacheSize,
                    defaults.maxCacheEntries,
                    defaults.maxEvictionQueueSize,
                    defaults.maxEvictionQueueEntries,
                    defaults.maxCacheSize / MAX_BITMAP_MEM_CACHE_SIZE_RATIO,
                    PARAMS_CHECK_INTERVAL_MS,
                )
            }
        }
    }

    private fun newDiskCacheConfig(context: Context, directoryName: String): DiskCacheConfig =
        DiskCacheConfig.newBuilder(context)
            .setBaseDirectoryPathSupplier(Supplier<File> { context.cacheDir })
            .setBaseDirectoryName(directoryName)
            .setMaxCacheSize(MAX_DISK_CACHE_SIZE)
            .build()
}
