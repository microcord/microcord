package com.discord.utilities.images

import android.graphics.Bitmap
import android.net.Uri
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber
import com.facebook.imagepipeline.image.CloseableImage
import rx.Observable
import rx.schedulers.Schedulers
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Pulls decoded bitmaps out of the Fresco pipeline for consumers that need a real [Bitmap] rather
 * than a Drawee (notifications, share sheets, avatar compositing).
 *
 * Reference: `com/discord/utilities/images/MGImagesBitmap.java`.
 */
object MGImagesBitmap {

    /** A request for one image, optionally rounded to a circle before it is handed back. */
    data class ImageRequest(val imageUri: String, val roundAsCircle: Boolean)

    /**
     * The decoded results keyed by request URI.
     *
     * [close] recycles the bitmaps when [recycleBitmaps] is set; the batch [getBitmaps] path
     * deliberately disables that because the caller keeps the bitmaps alive.
     */
    class CloseableBitmaps(
        private val underlyingMap: Map<String, Bitmap>,
        private val recycleBitmaps: Boolean = true,
    ) : Map<String, Bitmap> by underlyingMap, Closeable {

        override fun close() {
            if (!recycleBitmaps) return
            underlyingMap.values.forEach { it.recycle() }
        }

        operator fun plus(other: CloseableBitmaps): CloseableBitmaps = CloseableBitmaps(
            underlyingMap + other.underlyingMap,
            recycleBitmaps && other.recycleBitmaps,
        )
    }

    class DecodeException(imageUri: String) : Exception("Unable to decode image: $imageUri.")

    class ImageNotFoundException(imageUri: String) : Exception("404 image not found: $imageUri")

    class MissingBitmapException(imageUri: String) :
        Exception("Unable to decode image as bitmap: $imageUri")

    /** Decodes every non-blank request in parallel and collects them into one closeable map. */
    @JvmStatic
    fun getBitmaps(imageRequests: Set<ImageRequest>): Observable<CloseableBitmaps> =
        Observable.from(imageRequests.filter { it.imageUri.isNotBlank() })
            .flatMap { request ->
                getBitmap(request.imageUri, request.roundAsCircle)
                    .map { bitmap -> request.imageUri to bitmap }
            }
            .toMap({ it.first }, { it.second })
            .map { CloseableBitmaps(it, recycleBitmaps = false) }
            .subscribeOn(Schedulers.computation())

    /**
     * Emits a copy of the decoded bitmap for [imageUri], then completes.
     *
     * A copy is required because the pipeline reclaims the underlying reference as soon as the
     * subscriber returns.
     */
    fun getBitmap(imageUri: String, imageIsCircle: Boolean): Observable<Bitmap> {
        if (!isValidUri(imageUri)) {
            return Observable.error(IllegalArgumentException("invalid uri: $imageUri"))
        }
        val requestBuilder = MGImages.getImageRequest(imageUri, 0, 0, false)
        if (imageIsCircle) {
            requestBuilder.setPostprocessor(RoundAsCirclePostprocessor(imageUri))
        }
        val dataSource = Fresco.getImagePipeline()
            .fetchDecodedImage(requestBuilder.build(), null)
        return Observable.unsafeCreate<Bitmap> { subscriber ->
            dataSource.subscribe(
                object : BaseBitmapDataSubscriber() {
                    override fun onNewResultImpl(bitmap: Bitmap?) {
                        if (bitmap == null) {
                            subscriber.onError(MissingBitmapException(imageUri))
                            return
                        }
                        subscriber.onNext(Bitmap.createBitmap(bitmap))
                        subscriber.onCompleted()
                    }

                    override fun onFailureImpl(
                        source: DataSource<CloseableReference<CloseableImage>>,
                    ) {
                        val failure = source.failureCause
                        if (failure?.message?.contains("404") == true) {
                            subscriber.onError(ImageNotFoundException(imageUri))
                            return
                        }
                        subscriber.onError(failure ?: DecodeException(imageUri))
                    }
                },
                Executor { it.run() },
            )
        }
    }

    private fun isValidUri(imageUri: String): Boolean {
        val uri = Uri.parse(imageUri)
        if (uri.scheme.isNullOrBlank()) return false
        if (uri.host.isNullOrBlank()) return false
        return !uri.path.isNullOrBlank()
    }
}
