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

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        if (grantResults.containsValue(false)) {
            onDenied?.invoke(grantResults)
        } else {
            onGranted?.invoke()
        }
        onDenied = null
        onGranted = null
        requestStartedAtMillis = 0L
    }

    @MainThread
    override fun hasMedia(): Boolean = hasPermissions(mediaPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun hasGallery(): Boolean = hasPermissions(galleryPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun hasCamera(): Boolean = hasPermissions(cameraPermissions(Build.VERSION.SDK_INT))

    @MainThread
    override fun requestVideoCallPermissions(onSuccess: () -> Unit) {
        request(VIDEO_CALL, onSuccess) { grantResults ->
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
        request(MICROPHONE, onSuccess) {
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
    ) {
        if (hasPermissions(permissions)) {
            onSuccess()
            return
        }
        if (SystemClock.elapsedRealtime() - requestStartedAtMillis < REQUEST_DEBOUNCE_MILLIS) return
        onGranted = onSuccess
        onDenied = onFailure
        requestStartedAtMillis = SystemClock.elapsedRealtime()
        launcher.launch(permissions)
    }

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
    }
}
