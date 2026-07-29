package com.discord.app

/**
 * Runtime permission requests exposed to every screen.
 *
 * Screens never talk to `ActivityCompat` directly: the hosting [AppActivity] owns a single
 * `RequestMultiplePermissions` launcher, and fragments/sheets delegate to it so results survive
 * configuration changes and cannot be interleaved.
 */
interface AppPermissionsRequests {

    /** `true` when the media permission group (camera + storage) is already granted. */
    fun hasMedia(): Boolean

    fun requestVideoCallPermissions(onSuccess: () -> Unit)

    fun requestMedia(onSuccess: () -> Unit)

    fun requestMediaDownload(onSuccess: () -> Unit)

    fun requestMicrophone(onFailure: (() -> Unit)?, onSuccess: () -> Unit)

    fun requestCameraQRScanner(onSuccess: () -> Unit)

    fun requestCameraQRScanner(onSuccess: () -> Unit, onFailure: (() -> Unit)?)

    fun requestContacts(onSuccess: () -> Unit, onFailure: () -> Unit)

    fun requestNotifications(onFailure: () -> Unit, onSuccess: () -> Unit)
}
