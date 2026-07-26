package com.discord.widgets.voice.stream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.fragment.app.Fragment
import com.discord.app.AppLog

/**
 * The single place a screen share is requested from and its permission result is handled.
 *
 * Screen capture on Android is a two-step handshake: the app asks the system for a
 * `MediaProjection` consent token via [requestStartStream], the user approves it in a system
 * dialog, and the resulting `Intent` comes back through the fragment's activity result. Routing
 * both halves through one object is what lets several screens (the NFX sheet, the voice bottom
 * sheet, the fullscreen call) start a stream without each re-deriving the request code.
 */
object StreamNavigator {

    /**
     * Chosen by the reference. It has to be stable because the result is delivered to whichever
     * fragment happens to be alive when the user answers the system dialog.
     */
    private const val START_SCREENSHARE_REQUEST_CODE = 420

    /**
     * Hands the consent token to the media engine so it actually starts publishing.
     *
     * In the reference this is `VoiceEngineServiceController.getINSTANCE().startStream(intent)`.
     * That controller is part of the RTC transport, which is being reconstructed separately, so it
     * installs itself here. While it is absent the consent dialog still runs and is still consented
     * to — no stream is published, and the attempt is logged rather than swallowed.
     */
    @JvmStatic
    var startStreamHandler: ((Intent) -> Unit)? = null

    /** Asks the system for screen-capture consent on [fragment]'s behalf. */
    @JvmStatic
    fun requestStartStream(fragment: Fragment?) {
        val context = fragment?.context ?: return
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        fragment.startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            START_SCREENSHARE_REQUEST_CODE,
        )
    }

    /**
     * Invokes [intentCallback] with the consent token, but only for an approved screen-capture
     * result. Every other activity result passes through untouched, so a caller can forward all of
     * its results here unconditionally.
     */
    @JvmStatic
    fun handleActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        intentCallback: (Intent) -> Unit,
    ) {
        if (resultCode == Activity.RESULT_OK &&
            requestCode == START_SCREENSHARE_REQUEST_CODE &&
            data != null
        ) {
            intentCallback(data)
        }
    }

    /** Publishes the stream using the consent token obtained by [requestStartStream]. */
    @JvmStatic
    fun startStream(consentIntent: Intent) {
        val handler = startStreamHandler
        if (handler == null) {
            AppLog.w(
                "StreamNavigator",
                "screen capture was consented to but no media engine is installed; " +
                    "StreamNavigator.startStreamHandler is unassigned",
            )
            return
        }
        handler(consentIntent)
    }
}
