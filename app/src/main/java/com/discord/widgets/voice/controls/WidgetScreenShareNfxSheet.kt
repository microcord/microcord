package com.discord.widgets.voice.controls

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.Serializable

class WidgetScreenShareNfxSheet : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(context).inflate(R.layout.widget_screen_share_nfx_sheet, null)
        view.findViewById<android.view.View>(R.id.screen_share_nfx_cta).setOnClickListener {
            val projectionManager = requireContext()
                .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        }
        view.findViewById<android.view.View>(R.id.screen_share_nfx_cancel_button).setOnClickListener { dismiss() }
        dialog.setContentView(view)
        return dialog
    }

    @Deprecated("Required by the API-30 media projection contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK) dismiss()
    }

    data class VoiceBottomSheetParams(
        val forwardToFullscreenIfVideoActivated: Boolean,
        val featureContext: WidgetVoiceBottomSheet.FeatureContext,
    ) : Serializable

    companion object {
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ARG_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val ARG_VOICE_BOTTOM_SHEET_PARAMS = "ARG_VOICE_BOTTOM_SHEET_PARAMS"
        private const val REQUEST_SCREEN_CAPTURE = 5000
        private var shown = false

        fun canShow() = !shown

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            guildId: Long,
            voiceBottomSheetParams: VoiceBottomSheetParams? = null,
        ) {
            shown = true
            WidgetScreenShareNfxSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_GUILD_ID, guildId)
                    putSerializable(ARG_VOICE_BOTTOM_SHEET_PARAMS, voiceBottomSheetParams)
                }
            }.show(fragmentManager, WidgetScreenShareNfxSheet::class.java.name)
        }
    }
}
