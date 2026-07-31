package com.discord.app

/**
 * Runtime permission requests exposed to every screen.
 *
 * Screens never talk to `ActivityCompat` directly: the hosting [AppActivity] owns a single
 * `RequestMultiplePermissions` launcher, and fragments/sheets delegate to it so results survive
 * configuration changes and cannot be interleaved.
 */
interface AppPermissionsRequests {

    /** `true` when both direct gallery access and the camera are already granted. */
    fun hasMedia(): Boolean

    fun hasGallery(): Boolean = hasMedia()

    fun hasCamera(): Boolean = hasMedia()

    fun requestVideoCallPermissions(onSuccess: () -> Unit)

    fun requestMedia(onSuccess: () -> Unit)

    fun requestGallery(onSuccess: () -> Unit) = requestMedia(onSuccess)

    fun requestCamera(onSuccess: () -> Unit) = requestCameraQRScanner(onSuccess)

    fun requestMediaDownload(onSuccess: () -> Unit)

    fun requestMicrophone(onFailure: (() -> Unit)?, onSuccess: () -> Unit)

    fun requestCameraQRScanner(onSuccess: () -> Unit)

    fun requestCameraQRScanner(onSuccess: () -> Unit, onFailure: (() -> Unit)?)

    fun requestContacts(onSuccess: () -> Unit, onFailure: () -> Unit)

    fun requestNotifications(onFailure: () -> Unit, onSuccess: () -> Unit)
}
