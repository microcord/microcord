package com.discord.app

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.utilities.view.ToastManager

/**
 * Activity-scoped implementation of [AppPermissionsRequests].
 *
 * A single `RequestMultiplePermissions` launcher is registered while the activity is being created,
 * which is the only point at which the Activity Result API allows registration. Requests are
 * debounced so a double tap cannot enqueue two system dialogs.
 */
class AppPermissions(private val activity: ComponentActivity) : AppPermissionsRequests {

    private var onGranted: (() -> Unit)? = null
    private var onDenied: ((Map<String, Boolean>) -> Unit)? = null
    private var requestStartedAtMillis: Long = 0L
    private var requiredOfRequest: Array<String> = emptyArray()

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val granted = areRequiredGranted(requiredOfRequest, grantResults, ::isGranted)
        val grantedCallback = onGranted
        val deniedCallback = onDenied
        onDenied = null
        onGranted = null
        requestStartedAtMillis = 0L
        if (granted) grantedCallback?.invoke() else deniedCallback?.invoke(grantResults)
    }

    @MainThread
    override fun hasMedia(): Boolean = hasPermissions(mediaPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun hasGallery(): Boolean = hasPermissions(galleryPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun hasCamera(): Boolean = hasPermissions(cameraPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun requestVideoCallPermissions(onSuccess: () -> Unit) {
        request(VIDEO_CALL, bluetoothPermissions(Build.VERSION.SDK_INT), onSuccess) { grantResults ->
            when {
                grantResults[Manifest.permission.RECORD_AUDIO] != true -> toast(R.string.permission_microphone_denied)
                else -> toast(R.string.no_camera_access)
            }
        }
    }

    @MainThread
    override fun requestMedia(onSuccess: () -> Unit) {
        request(mediaPermissions(Build.VERSION.SDK_INT), onSuccess) { toast(R.string.permission_media_denied) }
    }

    @MainThread
    override fun requestGallery(onSuccess: () -> Unit) {
        request(galleryPermissions(Build.VERSION.SDK_INT), onSuccess) { toast(R.string.permission_media_denied) }
    }

    @MainThread
    override fun requestCamera(onSuccess: () -> Unit) {
        request(cameraPermissions(Build.VERSION.SDK_INT), onSuccess) { toast(R.string.no_camera_access) }
    }

    @MainThread
    override fun requestMediaDownload(onSuccess: () -> Unit) {
        onSuccess()
    }

    @MainThread
    override fun requestMicrophone(onFailure: (() -> Unit)?, onSuccess: () -> Unit) {
        request(MICROPHONE, bluetoothPermissions(Build.VERSION.SDK_INT), onSuccess) {
            if (onFailure == null) toast(R.string.permission_microphone_denied) else onFailure()
        }
    }

    @MainThread
    override fun requestCameraQRScanner(onSuccess: () -> Unit) = requestCameraQRScanner(onSuccess, null)

    @MainThread
    override fun requestCameraQRScanner(onSuccess: () -> Unit, onFailure: (() -> Unit)?) {
        request(CAMERA, onSuccess) {
            if (onFailure == null) toast(R.string.permission_qr_scanner_denied) else onFailure()
        }
    }

    @MainThread
    override fun requestContacts(onSuccess: () -> Unit, onFailure: () -> Unit) {
        request(CONTACTS, onSuccess) { onFailure() }
    }

    @MainThread
    override fun requestNotifications(onFailure: () -> Unit, onSuccess: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            onSuccess()
        } else {
            request(arrayOf(Manifest.permission.POST_NOTIFICATIONS), onSuccess) { onFailure() }
        }
    }

    private fun request(
        permissions: Array<String>,
        onSuccess: () -> Unit,
        onFailure: (Map<String, Boolean>) -> Unit,
    ) = request(permissions, emptyArray(), onSuccess, onFailure)

    /**
     * @param optional asked for in the same dialog but not required to succeed. Bluetooth is the
     *   case this exists for: a call still connects without it, it just cannot route to a headset,
     *   so refusing it must not stop the user joining.
     */
    private fun request(
        required: Array<String>,
        optional: Array<String>,
        onSuccess: () -> Unit,
        onFailure: (Map<String, Boolean>) -> Unit,
    ) {
        val permissions = required + optional
        if (hasPermissions(permissions)) {
            onSuccess()
            return
        }
        if (SystemClock.elapsedRealtime() - requestStartedAtMillis < REQUEST_DEBOUNCE_MILLIS) return
        requiredOfRequest = required
        onGranted = onSuccess
        onDenied = onFailure
        requestStartedAtMillis = SystemClock.elapsedRealtime()
        launcher.launch(permissions)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(activity, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val toastManager = ToastManager()

    private fun toast(@StringRes messageResId: Int) {
        toastManager.show(activity, messageResId)
    }

    internal companion object {
        /** Guards against a double tap enqueueing two system permission dialogs. */
        const val REQUEST_DEBOUNCE_MILLIS = 350L

        val MICROPHONE = arrayOf(Manifest.permission.RECORD_AUDIO)

        /**
         * Routing a call to a Bluetooth headset needs this from API 31; before that the install-time
         * `BLUETOOTH` covers it. Without it every call lands on the speaker or the earpiece.
         */
        fun bluetoothPermissions(sdkInt: Int): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            else emptyArray()
        val VIDEO_CALL = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val CAMERA = arrayOf(Manifest.permission.CAMERA)
        val CONTACTS = arrayOf(Manifest.permission.READ_CONTACTS)

        fun galleryPermissions(sdkInt: Int): Array<String> = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            sdkInt >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> emptyArray()
        }

        fun cameraPermissions(sdkInt: Int): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.M) CAMERA else emptyArray()

        fun mediaPermissions(sdkInt: Int): Array<String> =
            galleryPermissions(sdkInt) + cameraPermissions(sdkInt)

        internal fun areAllGranted(grantResults: Map<String, Boolean>): Boolean =
            grantResults.isNotEmpty() && grantResults.values.all { it }

        /** A permission absent from the results was never asked for, so its existing state stands. */
        internal fun areRequiredGranted(
            required: Array<String>,
            grantResults: Map<String, Boolean>,
            isGranted: (String) -> Boolean,
        ): Boolean = if (required.isEmpty()) areAllGranted(grantResults) else required.all {
            grantResults[it] ?: isGranted(it)
        }
    }
}
