package com.discord.dialogs

import android.os.Bundle
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.utilities.attachments.AttachmentLimits
import com.discord.app.AppDialog
import com.discord.i18n.i18nRender
import com.discord.stores.StoreStream
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Shown when an attachment exceeds the account's upload limit.
 *
 * Three mutually-shaped outcomes, decided entirely by [Companion.show]'s flags:
 * - **Compress and send** — offered only when the caller supplied a resend action and the file is
 *   actually compressible (a still image that has not already been compressed).
 * - **Get Nitro** — offered to non-premium users, since the limit is what Nitro raises.
 * - **Okay** — the dead end for a premium user with nothing compressible left to try, in which case
 *   the cancel button and the divider are hidden because "Okay" is the only action.
 *
 * The compression toggle writes straight through to the user setting, so agreeing here also changes
 * the default for future uploads — the reference behaves the same way.
 */
class ImageUploadFailedDialog : AppDialog(R.layout.image_upload_failed_dialog) {

    /** Runs when the user chooses "compress and send"; not parcelled, so lost on recreation. */
    private var onResendCompressed: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val arguments = argumentsOrDefault
        val limitBytes = arguments.getLong(PARAM_LIMIT_BYTES)
        val isTotal = arguments.getBoolean(PARAM_IS_TOTAL)
        // Nitro raises the per-file ceiling only, so a message that is too big overall must not be
        // sold a subscription that would not help.
        val isUserPremium = arguments.getBoolean(PARAM_IS_USER_PREMIUM) || isTotal ||
            !arguments.getBoolean(PARAM_NITRO_WOULD_HELP)
        val canCompress = onResendCompressed != null && arguments.getBoolean(PARAM_CAN_COMPRESS)

        val sendCompressed = view.findViewById<MaterialButton>(R.id.image_upload_failed_send_compress)
        sendCompressed.visibility = if (canCompress) View.VISIBLE else View.GONE
        sendCompressed.setOnClickListener {
            onResendCompressed?.invoke()
            dismiss()
        }

        // "Okay" replaces the cancel/compress pair when there is nothing else on offer.
        val onlyDismissRemains = isUserPremium && !canCompress
        view.findViewById<View>(R.id.image_upload_failed_divider).visibility =
            if (onlyDismissRemains) View.GONE else View.VISIBLE
        val okay = view.findViewById<MaterialButton>(R.id.image_upload_failed_okay)
        okay.visibility = if (onlyDismissRemains) View.VISIBLE else View.GONE
        okay.setOnClickListener { dismiss() }
        val cancel = view.findViewById<MaterialButton>(R.id.image_upload_failed_cancel)
        cancel.visibility = if (onlyDismissRemains) View.GONE else View.VISIBLE
        cancel.setOnClickListener { dismiss() }

        view.findViewById<RelativeLayout>(R.id.image_upload_failed_nitro_wrapper).visibility =
            if (isUserPremium) View.GONE else View.VISIBLE
        view.findViewById<MaterialButton>(R.id.image_upload_failed_nitro).setOnClickListener {
            WidgetSettingsPremium.launch(it.context)
            dismiss()
        }

        val compressionSettings = view.findViewById<RelativeLayout>(R.id.image_upload_failed_compression_settings_container)
        val compressionToggle = view.findViewById<SwitchMaterial>(R.id.image_upload_failed_compression_settings_checkbox)
        compressionSettings.visibility = if (canCompress) View.VISIBLE else View.GONE
        compressionSettings.setOnClickListener { compressionToggle.toggle() }
        compressionToggle.isChecked = StoreStream.getUserSettings().getIsAutoImageCompressionEnabled()
        compressionToggle.setOnCheckedChangeListener { _, isChecked ->
            StoreStream.getUserSettings().setIsAutoImageCompressionEnabled(isChecked)
        }

        val limitText: CharSequence = context
            ?.i18nRender(R.string.file_size_mb, "size" to limitBytes / BYTES_PER_MEGABYTE)
            ?: ""
        val bodyRes = when {
            isTotal -> R.string.upload_area_request_limit_help
            !isUserPremium && canCompress -> R.string.upload_area_too_large_help_mobile
            else -> R.string.upload_area_too_large_help
        }
        view.findViewById<TextView>(R.id.image_upload_failed_text).text =
            context?.i18nRender(bodyRes, "maxSize" to limitText)
    }

    companion object {
        private const val PARAM_IS_USER_PREMIUM = "PARAM_IS_USER_PREMIUM"
        private const val PARAM_CAN_COMPRESS = "PARAM_CAN_COMPRESS"
        private const val PARAM_LIMIT_BYTES = "PARAM_LIMIT_BYTES"
        private const val PARAM_IS_TOTAL = "PARAM_IS_TOTAL"
        private const val PARAM_NITRO_WOULD_HELP = "PARAM_NITRO_WOULD_HELP"
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L

        /**
         * @param isUserPremium also selects the alert variant the reference reports to analytics
         *   (`OVER_MAX_SIZE` for premium, `NITRO_UPSELL` otherwise).
         * @param isVideo / [isAlreadyCompressed] both disqualify the file from being compressed.
         */
        fun show(
            fragmentManager: FragmentManager,
            isUserPremium: Boolean,
            violation: AttachmentLimits.Violation,
            onResendCompressed: (() -> Unit)?,
            isVideo: Boolean = false,
            isAlreadyCompressed: Boolean = false,
        ) {
            ImageUploadFailedDialog().apply {
                this.onResendCompressed = onResendCompressed
                arguments = Bundle().apply {
                    putBoolean(PARAM_IS_USER_PREMIUM, isUserPremium)
                    putBoolean(PARAM_CAN_COMPRESS, !isVideo && !isAlreadyCompressed)
                    putLong(
                        PARAM_LIMIT_BYTES,
                        when (violation) {
                            is AttachmentLimits.Violation.FileTooLarge -> violation.limit
                            else -> AttachmentLimits.MAX_TOTAL_ATTACHMENT_SIZE
                        },
                    )
                    putBoolean(PARAM_IS_TOTAL, violation !is AttachmentLimits.Violation.FileTooLarge)
                    putBoolean(
                        PARAM_NITRO_WOULD_HELP,
                        violation is AttachmentLimits.Violation.FileTooLarge &&
                            AttachmentLimits.wouldNitroAllow(violation.size),
                    )
                }
            }.show(fragmentManager, ImageUploadFailedDialog::class.java.name)
        }
    }
}
