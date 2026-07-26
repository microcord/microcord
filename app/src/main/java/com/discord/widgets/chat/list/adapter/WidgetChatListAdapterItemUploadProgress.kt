package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import com.discord.R
import com.discord.i18n.i18nPlural
import com.discord.stores.StoreMessageUploads
import com.discord.stores.StoreStream
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.views.FailedUploadList
import com.discord.views.UploadProgressView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.UploadProgressEntry
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.functions.FuncN

/**
 * Progress card for a message whose attachments are still uploading.
 *
 * The row subscribes to the upload itself rather than being re-bound by the list: byte counts
 * arrive far more often than the chat list diffs, so the progress bar is driven straight from the
 * request body's counter, throttled to [MODEL_THROTTLE_MS].
 *
 * Up to three files each get their own bar; beyond that they collapse into one aggregate bar,
 * because the card would otherwise grow without bound.
 */
class WidgetChatListAdapterItemUploadProgress(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_upload_progress, adapter) {

    private val progressCancelCentered: ImageView =
        itemView.findViewById(R.id.progress_cancel_centered)
    private val progressCancelTop: ImageView = itemView.findViewById(R.id.progress_cancel_top)
    private val uploadProgress1: UploadProgressView = itemView.findViewById(R.id.upload_progress_1)
    private val uploadProgress2: UploadProgressView = itemView.findViewById(R.id.upload_progress_2)
    private val uploadProgress3: UploadProgressView = itemView.findViewById(R.id.upload_progress_3)

    override var subscription: Subscription? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as UploadProgressEntry

        ModelProvider.get(entry.messageNonce, MODEL_THROTTLE_MS)
            .onBackpressureLatest()
            .ui()
            .appSubscribe(
                errorClass = WidgetChatListAdapterItemUploadProgress::class.java,
                subscriptionHandler = { subscription = it },
                onNext = ::configureUI,
            )

        val cancel = View.OnClickListener {
            StoreStream.getMessages().cancelMessageSend(entry.channelId, entry.messageNonce)
        }
        progressCancelTop.setOnClickListener(cancel)
        progressCancelCentered.setOnClickListener(cancel)
    }

    private fun configureUI(uploadState: Model) {
        val isFew = uploadState is Model.Few
        if (uploadState is Model.Few) {
            setUploadState(uploadProgress1, uploadState.uploads[0])
            setUploadState(uploadProgress2, uploadState.uploads[1])
            if (uploadState.uploads.size == MAX_DETAILED_UPLOADS) {
                uploadProgress3.visibility = View.VISIBLE
                setUploadState(uploadProgress3, uploadState.uploads[2])
            } else {
                uploadProgress3.visibility = View.GONE
            }
        } else {
            setUploadState(uploadProgress1, uploadState)
            uploadProgress2.visibility = View.GONE
            uploadProgress3.visibility = View.GONE
        }

        // Cancelling is only offered while there is still something left to send.
        val isCancellable = when (uploadState) {
            is Model.Preprocessing -> true
            is Model.Single -> uploadState.progress < COMPLETE_PERCENT
            is Model.Few -> uploadState.uploads.any { it.progress < COMPLETE_PERCENT }
            is Model.Many -> uploadState.progress < COMPLETE_PERCENT
            Model.None -> false
        }
        if (isCancellable) {
            // A single bar is centred beside its cancel button; a stack of bars needs the button
            // pinned to the top instead.
            progressCancelTop.visibility = if (isFew) View.VISIBLE else View.GONE
            progressCancelCentered.visibility = if (isFew) View.GONE else View.VISIBLE
        } else {
            progressCancelTop.visibility = View.GONE
            progressCancelCentered.visibility = View.GONE
        }
    }

    private fun setUploadState(view: UploadProgressView, uploadState: Model) {
        val context = view.context
        when (uploadState) {
            Model.None -> {
                view.configure(context.getString(R.string.upload_queued), 0, null)
                view.setIcon(themedDrawable(context, R.attr.ic_uploads_generic))
            }
            is Model.Preprocessing -> {
                val title = uploadState.displayName ?: view.i18nPlural(
                    R.plurals.uploading_files_count,
                    uploadState.numFiles,
                    "count" to uploadState.numFiles,
                )
                view.configure(title, Model.PROGRESS_INDETERMINATE, null)
                view.setIcon(
                    uploadState.mimeType?.let { iconForFiletype(context, it) }
                        ?: themedDrawable(context, R.attr.ic_uploads_generic),
                )
            }
            is Model.Single -> {
                view.configure(
                    uploadState.name,
                    uploadState.progress,
                    FailedUploadList.sizeSubtitle(uploadState.sizeBytes),
                )
                view.setIcon(iconForFiletype(context, uploadState.mimeType))
            }
            is Model.Many -> {
                val title = view.i18nPlural(
                    R.plurals.uploading_files_count,
                    uploadState.numFiles,
                    "count" to uploadState.numFiles,
                )
                view.configure(title, uploadState.progress, FailedUploadList.sizeSubtitle(uploadState.sizeBytes))
                view.setIcon(themedDrawable(context, R.attr.ic_uploads_generic))
            }
            is Model.Few -> Unit // Never nested; handled by configureUI.
        }
    }

    /**
     * Reference: `FileUtilsKt.getIconForFiletype`. That utility has not been reconstructed yet, so
     * the same three-way mime split `FailedUploadList` uses is applied here.
     */
    private fun iconForFiletype(context: Context, mimeType: String): Int = themedDrawable(
        context,
        when {
            mimeType.startsWith("image") -> R.attr.ic_uploads_image
            mimeType.startsWith("video") -> R.attr.ic_uploads_video
            else -> R.attr.ic_uploads_file
        },
    )

    private fun themedDrawable(context: Context, attribute: Int): Int = TypedValue().let {
        context.theme.resolveAttribute(attribute, it, true)
        it.resourceId
    }

    /** What the card is showing right now. */
    sealed class Model {

        /** Nothing is in flight for this nonce (yet). */
        object None : Model()

        /** Files are being read and hashed; there is no byte count to report. */
        data class Preprocessing(
            val numFiles: Int,
            val displayName: String?,
            val mimeType: String?,
        ) : Model()

        /** One file, named and sized. */
        data class Single(
            val name: String,
            val mimeType: String,
            val sizeBytes: Long,
            val progress: Int,
        ) : Model()

        /** Two or three files, each with its own bar. */
        data class Few(val uploads: List<Single>) : Model()

        /** More files than fit; only the aggregate is shown. */
        data class Many(val numFiles: Int, val sizeBytes: Long, val progress: Int) : Model()

        companion object {
            /** Shown as a spinner rather than a filled bar. */
            const val PROGRESS_INDETERMINATE = -1
            const val SIZE_UNKNOWN = -1L
        }
    }

    object ModelProvider {

        /** Beyond this many files the card shows one aggregate bar instead of one bar per file. */
        private const val MAX_DETAILED_UPLOADS = 3

        fun get(nonce: String, throttleIntervalMs: Long): Observable<Model> =
            StoreStream.getMessageUploads()
                .observeUploadProgress(nonce)
                .switchMap<Model> { state ->
                    when (state) {
                        is StoreMessageUploads.MessageUploadState.None ->
                            Observable.just(Model.None)
                        is StoreMessageUploads.MessageUploadState.Preprocessing ->
                            Observable.just(
                                Model.Preprocessing(state.numFiles, state.displayName, state.mimeType),
                            )
                        is StoreMessageUploads.MessageUploadState.Uploading -> when {
                            state.uploads.size == 1 ->
                                singleUpload(state.uploads.first(), throttleIntervalMs)
                            state.uploads.size <= MAX_DETAILED_UPLOADS ->
                                fewUploads(state.uploads, throttleIntervalMs)
                            else -> manyUploads(state.uploads, throttleIntervalMs)
                        }
                    }
                }

        /**
         * A file whose length is unknown cannot report a percentage, so it emits once with an
         * indeterminate bar instead of subscribing to a counter that has nothing to divide by.
         */
        private fun singleUpload(upload: FileUpload, throttleIntervalMs: Long): Observable<Model.Single> {
            if (upload.contentLength <= 0L) {
                return Observable.just(
                    Model.Single(
                        upload.name,
                        upload.mimeType,
                        Model.SIZE_UNKNOWN,
                        Model.PROGRESS_INDETERMINATE,
                    ),
                )
            }
            return upload.bytesWrittenObservable
                .leadingEdgeThrottle(throttleIntervalMs, TimeUnit.MILLISECONDS)
                .map { bytesWritten -> percentage(bytesWritten, upload.contentLength) }
                .distinctUntilChanged()
                .map { progress ->
                    Model.Single(upload.name, upload.mimeType, upload.contentLength, progress)
                }
        }

        private fun fewUploads(
            uploads: List<FileUpload>,
            throttleIntervalMs: Long,
        ): Observable<Model.Few> = Observable.combineLatest(
            uploads.map { singleUpload(it, throttleIntervalMs) },
            FuncN<Model.Few> { values -> Model.Few(values.map { it as Model.Single }) },
        )

        private fun manyUploads(
            uploads: List<FileUpload>,
            throttleIntervalMs: Long,
        ): Observable<Model.Many> {
            // One file of unknown length makes the whole total meaningless.
            val totalContentLength = uploads.fold(0L) { total, upload ->
                if (total < 0L || upload.contentLength <= 0L) -1L else total + upload.contentLength
            }
            if (totalContentLength <= 0L) {
                return Observable.just(
                    Model.Many(uploads.size, Model.SIZE_UNKNOWN, Model.PROGRESS_INDETERMINATE),
                )
            }
            return Observable.combineLatest(
                uploads.map {
                    it.bytesWrittenObservable
                        .leadingEdgeThrottle(throttleIntervalMs, TimeUnit.MILLISECONDS)
                },
                FuncN<Long> { values -> values.sumOf { it as Long } },
            )
                .map { totalBytesWritten -> percentage(totalBytesWritten, totalContentLength) }
                .distinctUntilChanged()
                .map { progress -> Model.Many(uploads.size, totalContentLength, progress) }
        }

        private fun percentage(bytesWritten: Long, contentLengthBytes: Long): Int =
            if (contentLengthBytes > 0L) {
                (bytesWritten.toFloat() / contentLengthBytes * 100).toInt()
            } else {
                0
            }
    }

    private companion object {
        /** Byte counts arrive per network write; the card only needs ten frames a second. */
        private const val MODEL_THROTTLE_MS = 100L
        private const val MAX_DETAILED_UPLOADS = 3
        private const val COMPLETE_PERCENT = 100
    }
}
