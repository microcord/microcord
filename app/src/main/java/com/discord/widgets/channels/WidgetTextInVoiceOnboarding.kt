package com.discord.widgets.channels

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetTextInVoiceOnboardingBinding
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.google.android.material.R as MaterialR

class WidgetTextInVoiceOnboarding : AppBottomSheet() {
    private var binding: WidgetTextInVoiceOnboardingBinding? = null
    private var onCtaClicked: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_text_in_voice_onboarding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetTextInVoiceOnboardingBinding.bind(view).also { this.binding = it }
        setBottomSheetCollapsedStateDisabled()
        TextInVoiceOnboardingManager().markSeen()
        binding.textInVoiceOnboardingClose.setOnClickListener { dismiss() }
        binding.textInVoiceOnboardingCta.setOnClickListener {
            onCtaClicked?.invoke()
            dismiss()
        }
        configureMedia(VIDEO_URL)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun configureMedia(videoUrl: String) {
        val binding = checkNotNull(binding)
        view?.post {
            val height = view?.measuredHeight ?: 0
            val sheet = dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet)
            if (height > 0 && sheet != null) {
                sheet.layoutParams = sheet.layoutParams.apply { this.height = height + 1 }
            }
        }
        binding.textInVoiceOnboardingVideoContainer.clipToOutline = true
        binding.textInVoiceOnboardingVideo.setVideoPath(videoUrl)
        hideVideoOverlay()
        binding.textInVoiceOnboardingVideo.setOnClickListener {
            if (binding.textInVoiceOnboardingVideo.isPlaying) {
                showVideoOverlay()
                binding.textInVoiceOnboardingVideo.pause()
            } else {
                hideVideoOverlay()
                binding.textInVoiceOnboardingVideo.start()
            }
        }
        binding.textInVoiceOnboardingVideo.setOnPreparedListener { player ->
            player.start()
            player.isLooping = true
            binding.textInVoiceOnboardingVideo.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.textInVoiceOnboardingVideo.requestLayout()
        }
        binding.textInVoiceOnboardingVideo.setOnInfoListener { _: MediaPlayer, what: Int, _: Int ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                binding.textInVoiceOnboardingVideoPlaceholder.isInvisible = true
                binding.textInVoiceOnboardingVideo.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.textInVoiceOnboardingVideo.requestLayout()
            }
            false
        }
    }

    private fun hideVideoOverlay() {
        binding?.textInVoiceOnboardingVideoOverlay.fadeOut(200L, { it.scaleX(2f).scaleY(2f) })
    }

    private fun showVideoOverlay() {
        binding?.textInVoiceOnboardingVideoOverlay.fadeIn(
            200L,
            { view ->
                view.scaleX = 2f
                view.scaleY = 2f
            },
            { it.scaleX(1f).scaleY(1f) },
        )
    }

    companion object {
        const val VIDEO_URL =
            "https://cdn.discordapp.com/attachments/727325253608538113/942859496601043024/Text_in_Voice_NUX_Mobile_In-App.mp4"

        fun show(fragmentManager: FragmentManager, onCtaClicked: () -> Unit) {
            WidgetTextInVoiceOnboarding().apply { this.onCtaClicked = onCtaClicked }
                .show(fragmentManager, WidgetTextInVoiceOnboarding::class.java.name)
        }
    }
}
