package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.UploadProgress
import java.util.Collections
import rx.Observable

class StoreMessageUploads(private val observationDeck: ObservationDeck) : StoreV2() {
    sealed class MessageUploadState {
        object None : MessageUploadState()
        data class Preprocessing(val numFiles: Int, val displayName: String?, val mimeType: String?) : MessageUploadState()
        data class Uploading(val uploads: List<FileUpload>, val progress: UploadProgress) : MessageUploadState()
    }

    private val uploads = HashMap<String, MessageUploadState>()
    private var snapshot: Map<String, MessageUploadState> = emptyMap()

    fun getUploadProgress(nonce: String): MessageUploadState = snapshot[nonce] ?: MessageUploadState.None

    fun observeUploadProgress(nonce: String): Observable<MessageUploadState> =
        observationDeck.connectRx(this) { getUploadProgress(nonce) }.distinctUntilChanged()
    fun observeAll(): Observable<Map<String, MessageUploadState>> =
        observationDeck.connectRx(this) { snapshot }.distinctUntilChanged()

    @StoreThread
    fun onPreprocessing(nonce: String, numFiles: Int, displayName: String?, mimeType: String?) =
        update(nonce, MessageUploadState.Preprocessing(numFiles, displayName, mimeType))

    @StoreThread
    fun bindUpload(nonce: String, files: List<FileUpload>, progress: UploadProgress = UploadProgress(0, files.sumOf { it.contentLength.coerceAtLeast(0) })) =
        update(nonce, MessageUploadState.Uploading(files.toList(), progress))

    @StoreThread
    fun onProgress(nonce: String, progress: UploadProgress) {
        val state = uploads[nonce] as? MessageUploadState.Uploading ?: return
        update(nonce, state.copy(progress = progress))
    }

    @StoreThread fun onSuccess(nonce: String) = cleanup(nonce)
    @StoreThread fun onFailure(nonce: String) = cleanup(nonce)
    @StoreThread fun onCancel(nonce: String) = cleanup(nonce)
    @StoreThread fun clear() { if (uploads.isNotEmpty()) { uploads.clear(); markChanged() } }

    override fun snapshotData() {
        snapshot = Collections.unmodifiableMap(uploads.mapValues { (_, state) ->
            if (state is MessageUploadState.Uploading) state.copy(uploads = state.uploads.toList()) else state
        })
    }

    private fun update(nonce: String, state: MessageUploadState) {
        uploads[nonce] = state
        markChanged()
    }

    private fun cleanup(nonce: String) {
        if (uploads.remove(nonce) != null) markChanged()
    }
}
