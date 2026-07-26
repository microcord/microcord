package com.discord.widgets.voice.controls

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import com.discord.widgets.voice.stream.StreamNavigator
import java.io.Serializable

/**
 * The one-time "new feature: screen share" sheet.
 *
 * It is shown at most once per install — [show] records that immediately, so a user who kills the
 * app mid-sheet does not get it again — and it is the entry point into the screen-capture consent
 * flow. Whichever way the user leaves the sheet (CTA, skip, or dismiss), they land back where they
 * came from rather than on an empty screen.
 */
class WidgetScreenShareNfxSheet : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_screen_share_nfx_sheet

    private val channelId: Long
        get() = requireArguments().getLong(ARG_CHANNEL_ID)

    private val guildId: Long
        get() = requireArguments().getLong(ARG_GUILD_ID)

    private val voiceBottomSheetParams: VoiceBottomSheetParams?
        get() = requireArguments().getSerializable(ARG_VOICE_BOTTOM_SHEET_PARAMS) as? VoiceBottomSheetParams

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.screen_share_nfx_cta).setOnClickListener {
            StreamNavigator.requestStartStream(this)
        }
        view.findViewById<View>(R.id.screen_share_nfx_cancel_button).setOnClickListener {
            dismiss()
            maybeNavigateToVoiceBottomSheet()
        }
    }

    @Deprecated("Required by the pre-AndroidX-Activity media projection contract")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        StreamNavigator.handleActivityResult(requestCode, resultCode, data) { consentIntent ->
            StreamNavigator.startStream(consentIntent)
            // The reference additionally re-enters `WidgetCallFullscreen` here when it is not
            // already the top screen. That fragment is being reconstructed separately; once it
            // exists, launch it from this branch.
            dismiss()
        }
    }

    /** Backing out of the sheet counts as a skip, not as an abandoned flow. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        maybeNavigateToVoiceBottomSheet()
    }

    /**
     * Returns the user to the voice controls they opened this sheet from.
     *
     * Only sheets opened *from* the voice bottom sheet carry [VoiceBottomSheetParams]; opened from
     * anywhere else there is nothing to go back to and this is a no-op.
     *
     * The reference has a second branch here that opens `WidgetCallPreviewFullscreen` instead when
     * the `TextInVoiceFeatureFlag` is on. Neither that flag nor that screen exists in this
     * reconstruction yet, so only the bottom-sheet branch is wired.
     */
    private fun maybeNavigateToVoiceBottomSheet() {
        val params = voiceBottomSheetParams ?: return
        WidgetVoiceBottomSheet.show(
            parentFragmentManager,
            channelId,
            params.forwardToFullscreenIfVideoActivated,
            params.featureContext,
        )
    }

    /**
     * Where to return once the sheet closes. Serializable because it travels through fragment
     * arguments and has to survive process death.
     */
    data class VoiceBottomSheetParams(
        val forwardToFullscreenIfVideoActivated: Boolean,
        val featureContext: WidgetVoiceBottomSheet.FeatureContext,
    ) : Serializable

    companion object {
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ARG_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val ARG_VOICE_BOTTOM_SHEET_PARAMS = "ARG_VOICE_BOTTOM_SHEET_PARAMS"
        private const val CACHE_KEY_SHOWN = "CACHE_KEY_SCREEN_SHARE_NFX_SHEET_SHOWN"

        /** Whether the sheet has never been shown on this install. */
        @JvmStatic
        fun canShow(): Boolean =
            !SharedPreferencesProvider.get().getBoolean(CACHE_KEY_SHOWN, false)

        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            guildId: Long,
            voiceBottomSheetParams: VoiceBottomSheetParams? = null,
        ) {
            WidgetScreenShareNfxSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_GUILD_ID, guildId)
                    putSerializable(ARG_VOICE_BOTTOM_SHEET_PARAMS, voiceBottomSheetParams)
                }
            }.show(fragmentManager, WidgetScreenShareNfxSheet::class.java.name)

            // Recorded as soon as it is shown, not when it is dismissed, so an app kill mid-sheet
            // does not resurrect it.
            SharedPreferencesProvider.get().edit().putBoolean(CACHE_KEY_SHOWN, true).apply()
        }
    }
}
