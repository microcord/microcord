package com.discord.utilities.voice

import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.MainThread
import com.discord.app.AppComponent
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.rtcconnection.RtcConnection
import com.discord.rtcconnection.VoiceTransport
import com.discord.rtcconnection.mediaengine.ThumbnailEmitter
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreRtcConnection
import com.discord.stores.StoreStream
import com.discord.stores.StoreStreamRtcConnection
import com.discord.stores.StoreUser
import com.discord.tooltips.FloatingViewManager
import com.discord.tooltips.TooltipManager
import com.discord.utilities.images.ImageEncoder
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subscriptions.CompositeSubscription

class ScreenShareManager @JvmOverloads constructor(
    private val appComponent: AppComponent,
    val channelId: Long,
    val guildId: Long?,
    private val storeApplicationStreaming: StoreApplicationStreaming = StoreStream.getApplicationStreaming(),
    private val storeRtcConnection: StoreRtcConnection = StoreStream.getRtcConnection(),
    private val storeStreamRtcConnection: StoreStreamRtcConnection = StoreStream.getStreamRtcConnection(),
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val restApi: RestAPIInterface = RestAPI.api,
    private val imageEncoder: ImageEncoder = ImageEncoder(),
    @Suppress("unused")
    private val tooltipManager: TooltipManager =
        TooltipManager.Provider.get(FloatingViewManager.Provider.get()),
) {
    data class State(
        val activeStream: StoreApplicationStreaming.ActiveApplicationStream?,
        val streamRtcConnection: StoreStreamRtcConnection.State,
        val meId: Long?,
    )

    private val thumbnailBitmapSubject = BehaviorSubject.create<Bitmap>()
    private val compositeSubscription = CompositeSubscription()
    private var previousState: State? = null

    init {
        subscribeToStores()
    }

    private fun createThumbnailEmitter() = ThumbnailEmitter(
        width = THUMBNAIL_WIDTH_PX,
        height = THUMBNAIL_HEIGHT_PX,
        periodMs = PREVIEW_INTERVAL_MS,
        initialDelayMs = PREVIEW_DELAY_MS,
        onNextThumbnail = thumbnailBitmapSubject::onNext,
    )

    @MainThread
    private fun handleStartStream(intent: Intent) {
        storeApplicationStreaming.createStream(channelId, guildId)
        VoiceTransport.getOrNull()?.setScreenshare(intent, createThumbnailEmitter())
        uploadScreenSharePreviews()
    }

    @MainThread
    private fun handleStateUpdate(state: State) {
        val previous = previousState
        if (streamKeyChanged(previous, state)) {
            VoiceTransport.getOrNull()?.setScreenshare(null)
        }
        if (streamDisconnected(previous, state)) stopStream()

        if (state.activeStream?.stream?.ownerId != state.meId) return
        previousState = state
    }

    private fun subscribeToStores() {
        Observable.combineLatest(
            storeApplicationStreaming.observeActiveStream(),
            storeStreamRtcConnection.observeState(),
            storeUser.observeMe(),
        ) { activeStream, streamRtcConnection, me ->
            State(activeStream, streamRtcConnection, me.id)
        }
            .distinctUntilChanged()
            .ui(appComponent)
            .appSubscribe(
                errorClass = ScreenShareManager::class.java,
                subscriptionHandler = compositeSubscription::add,
                onNext = ::handleStateUpdate,
            )
    }

    private fun uploadScreenSharePreviews() {
        thumbnailBitmapSubject
            .concatMap { bitmap ->
                val activeStream = previousState?.activeStream ?: return@concatMap Observable.empty<Void>()
                restApi.postStreamPreview(
                    activeStream.stream.encodedStreamKey,
                    RestAPIParams.Thumbnail(imageEncoder.encodeBitmapAsJpegDataUrl(bitmap, JPEG_QUALITY)),
                )
            }
            .bindToComponentLifecycle(appComponent)
            .restSubscribeOn(false)
            .appSubscribe(
                errorClass = ScreenShareManager::class.java,
                subscriptionHandler = compositeSubscription::add,
                onNext = {},
            )
    }

    fun release() = compositeSubscription.unsubscribe()

    fun startStream(intent: Intent) {
        storeRtcConnection.observeRtcConnectionMetadata()
            .filter { metadata -> metadata.matchesTarget() }
            .take(1)
            .ui(appComponent)
            .appSubscribe(
                errorClass = ScreenShareManager::class.java,
                subscriptionHandler = compositeSubscription::add,
                onNext = { handleStartStream(intent) },
            )
    }

    fun stopStream() {
        val activeStream = previousState?.activeStream ?: return
        storeApplicationStreaming.stopStream(activeStream.stream.encodedStreamKey)
    }

    private fun RtcConnection.Metadata?.matchesTarget(): Boolean =
        this?.channelId == channelId && guildId == this.guildId

    companion object {
        const val JPEG_QUALITY = 92
        const val PREVIEW_DELAY_MS = 5_000L
        const val PREVIEW_INTERVAL_MS = 300_000L
        const val THUMBNAIL_HEIGHT_PX = 288
        const val THUMBNAIL_WIDTH_PX = 512

        internal fun streamKeyChanged(previous: State?, current: State): Boolean =
            previous?.activeStream != null &&
                previous.activeStream.stream.encodedStreamKey !=
                current.activeStream?.stream?.encodedStreamKey

        internal fun streamConnected(previous: State?, current: State): Boolean =
            previous?.streamRtcConnection?.isConnected != true && current.streamRtcConnection.isConnected

        internal fun streamDisconnected(previous: State?, current: State): Boolean =
            previous?.streamRtcConnection?.streamKey != null && current.streamRtcConnection.streamKey == null
    }
}
