package com.discord.widgets.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.ToastManager
import com.google.android.material.chip.Chip
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView

/**
 * Fullscreen camera viewfinder that turns a scanned QR code into navigation.
 *
 * The camera is only started once the permission has been granted, and a denial closes the screen
 * rather than leaving a dead black viewfinder. A successful scan always finishes the screen,
 * whatever it did with the result, because the user asked for one code and got it.
 *
 * Only Discord's own codes are accepted. Anything else — a random URL, a Wi-Fi credential, a vCard —
 * is refused with [R.string.qr_code_invalid] instead of being opened, because a scanner that follows
 * arbitrary codes is a phishing vector.
 *
 * Reference: `com.discord.widgets.media.WidgetQRScanner`.
 */
class WidgetQRScanner : AppFragment(R.layout.widget_qr_scanner), ZXingScannerView.ResultHandler {

    private lateinit var qrScanner: ZXingScannerView
    private lateinit var qrScannerChip: Chip

    private val toastManager = ToastManager()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        qrScanner = view.findViewById(R.id.qr_scanner)
        qrScannerChip = view.findViewById(R.id.qr_scanner_chip)

        qrScanner.setFormats(ZXingScannerView.ALL_FORMATS)
        qrScanner.setResultHandler(this)
        qrScanner.visibility = View.VISIBLE
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setScreenStatusBarColor(ContextCompat.getColor(requireContext(), R.color.black))
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.login_with_qr)

        // Shown when the caller knows the user has already started a login elsewhere and needs to
        // be told that rescanning is what continues it.
        val showHelpChip = arguments?.getBoolean(EXTRA_SHOW_HELP_CHIP)
            ?: mostRecentIntent.getBooleanExtra(EXTRA_SHOW_HELP_CHIP, false)
        qrScannerChip.visibility = if (showHelpChip) View.VISIBLE else View.GONE

        requestCameraQRScanner(
            onSuccess = { qrScanner.startCamera(MAIN_BACK_CAMERA) },
            onFailure = { appActivity?.finish() },
        )
    }

    override fun onPause() {
        super.onPause()
        qrScanner.stopCamera()
    }

    override fun onDestroyView() {
        qrScanner.stopCamera()
        toastManager.close()
        super.onDestroyView()
    }

    override fun handleResult(rawResult: Result?) {
        val text = rawResult?.text ?: return
        val uri = Uri.parse(text)
        if (!isDiscordLink(text, uri)) {
            toastManager.show(requireContext(), R.string.qr_code_invalid)
            return
        }

        // The reference splits here: a `/ra/<fingerprint>` path opens `WidgetRemoteAuth` directly
        // with the captured fingerprint, and everything else goes through UriHandler. That screen
        // has not been reconstructed, so remote-auth codes currently take the same route — the app's
        // own `/ra/` deep-link filter is the only consumer that exists. Restore the direct launch
        // when `widgets/auth/WidgetRemoteAuth` lands.
        UriHandler.handle(requireContext(), text)
        requireActivity().finish()
    }

    /**
     * Whether the scanned text is one of ours.
     *
     * Two shapes qualify: a well-formed `https` URL on a Discord-owned host, and a `discord://`
     * app link. The reference uses `AppUriMatcher` and `IntentUtils.isDiscordAppUri` for the two
     * halves; this project's equivalents are [UriHandler.isImplicitlyTrustedDomain] and the app
     * scheme hosts that [com.discord.utilities.intent.DeepLinkParser] accepts.
     */
    private fun isDiscordLink(text: String, uri: Uri): Boolean {
        val host = uri.host
        if (URLUtil.isValidUrl(text) && host != null && UriHandler.isImplicitlyTrustedDomain(host)) {
            return true
        }
        return uri.scheme.equals(APP_SCHEME, ignoreCase = true) && host in APP_SCHEME_HOSTS
    }

    companion object {
        private const val EXTRA_SHOW_HELP_CHIP = "SHOW_HELP_CHIP"

        /** ZXing's camera index for the rear camera, which is the only one worth scanning with. */
        private const val MAIN_BACK_CAMERA = 0

        private const val APP_SCHEME = "discord"
        private val APP_SCHEME_HOSTS = setOf("app", "action")

        /**
         * @param showHelpChip explain that rescanning continues an already-started login, used when
         *   the user is sent back here mid-handshake.
         */
        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, showHelpChip: Boolean = false) {
            val extras = Intent(Intent.ACTION_VIEW).putExtra(EXTRA_SHOW_HELP_CHIP, showHelpChip)
            presentScreen(context, WidgetQRScanner::class.java, extras)
        }
    }
}
