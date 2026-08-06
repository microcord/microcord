package com.discord.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.DimenRes
import androidx.annotation.MainThread
import com.discord.R
import com.discord.databinding.ViewVoiceUserBinding
import com.discord.api.user.AvatarDecorationData
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.view.extensions.fadeBy
import com.discord.views.user.avatarUrl
import com.facebook.imagepipeline.request.BasePostprocessor

internal enum class VoiceDisplayState { CONNECTED, SPEAKING, MUTED, RINGING, DISCONNECTED }

internal fun voiceDisplayState(user: StoreVoiceParticipants.VoiceUser): VoiceDisplayState = when {
    user.isSpeaking -> VoiceDisplayState.SPEAKING
    user.isRinging -> VoiceDisplayState.RINGING
    !user.isConnected -> VoiceDisplayState.DISCONNECTED
    user.voiceState?.let { it.selfMute || it.selfDeaf || it.mute } == true -> VoiceDisplayState.MUTED
    else -> VoiceDisplayState.CONNECTED
}

class VoiceUserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val binding = ViewVoiceUserBinding.inflate(LayoutInflater.from(context), this)

    init {
        clipChildren = false
    }

    private var defaultVoiceStateBackground = android.R.color.transparent
    private var state: VoiceDisplayState? = null
    private var voiceUser: StoreVoiceParticipants.VoiceUser? = null
    private var previousAvatarUrl: String? = null
    private var fadeWhenDisconnected = false
    private var animateAvatarWhenRinging = false
    private var bitmapListener: (Bitmap, String?) -> Unit = { _, _ -> }
    /**
     * Pulses the whole tile while the user is being rung. Each run fades out and
     * back once; it re-arms itself so the pulse continues for as long as the call
     * is ringing.
     */
    private val ringAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.1f).apply {
        duration = RING_PULSE_DURATION_MS
        repeatMode = ObjectAnimator.REVERSE
        repeatCount = 1
        interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                val wasCancelled = cancelled
                cancelled = false
                if (!wasCancelled && isRinging()) animation.start()
            }
        })
    }

    init {
        context.obtainStyledAttributes(
            attrs,
            R.styleable.VoiceUserView,
        ).run {
            try {
                val size = getDimensionPixelSize(R.styleable.VoiceUserView_vuv_avatar_size, 0)
                if (size > 0) setAvatarSize(size)
                fadeWhenDisconnected = getBoolean(
                    R.styleable.VoiceUserView_vuv_fade_when_disconnected,
                    false,
                )
                animateAvatarWhenRinging = getBoolean(
                    R.styleable.VoiceUserView_vuv_animate_avatar_when_ringing,
                    false,
                )
            } finally {
                recycle()
            }
        }
    }

    /**
     * The tile sizes itself from its layout, so the avatar is requested unrestricted
     * unless a caller asks for a specific dimension; pinning it to a small bitmap
     * makes large tiles blurry.
     */
    @MainThread
    fun setVoiceUser(voiceUser: StoreVoiceParticipants.VoiceUser) =
        setVoiceUser(voiceUser, R.dimen.avatar_size_unrestricted)

    @MainThread
    fun setVoiceUser(voiceUser: StoreVoiceParticipants.VoiceUser, @DimenRes sizeDimenRes: Int) {
        if (this.voiceUser == voiceUser) return
        this.voiceUser = voiceUser
        val requestedSize = resources.getDimensionPixelSize(sizeDimenRes)
            .takeIf { it > 0 }
            ?.let(IconUtils::getMediaProxySize)
        val url = avatarUrl(voiceUser.user, voiceUser.guildMember, requestedSize, animate = false)
        if (url != previousAvatarUrl) {
            previousAvatarUrl = url
            IconUtils.setIcon(binding.voiceUserAvatar, url, sizeDimenRes, transform = { builder ->
                // The tile tints its background from the avatar, so the decoded bitmap
                // is handed back to whoever asked for it.
                builder.postprocessor = object : BasePostprocessor() {
                    override fun process(bitmap: Bitmap) {
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        post {
                            if (url == previousAvatarUrl) bitmapListener(copy, url)
                        }
                    }
                }
            })
        }
        binding.voiceUserNameDisplay.text = voiceUser.displayName
        setVoiceState(voiceDisplayState(voiceUser))
    }

    @MainThread
    fun a(voiceUser: StoreVoiceParticipants.VoiceUser, @DimenRes sizeDimenRes: Int) =
        setVoiceUser(voiceUser, sizeDimenRes)

    /** Voice tiles show decorations; the participant sheet rows do not, so this is opt-in. */
    @MainThread
    fun setAvatarDecoration(decoration: AvatarDecorationData?) =
        binding.voiceUserAvatarDecoration.bind(decoration)

    @MainThread
    fun setAvatarSize(avatarSize: Int) {
        binding.voiceUserAvatar.resize(avatarSize)
        binding.voiceUserNameDisplay.resize(avatarSize)
        requestLayout()
    }

    fun setDefaultVoiceStateBackground(resource: Int) {
        defaultVoiceStateBackground = resource
    }

    @MainThread
    fun setFadeWhenDisconnected(value: Boolean) {
        fadeWhenDisconnected = value
    }

    @MainThread
    fun setAnimateAvatarWhenRinging(value: Boolean) {
        animateAvatarWhenRinging = value
    }

    fun setOnBitmapLoadedListener(listener: (Bitmap, String?) -> Unit) {
        bitmapListener = listener
    }

    @MainThread
    fun setRingMargin(margin: Int) {
        binding.voiceUserAvatar.layoutParams = (binding.voiceUserAvatar.layoutParams as LayoutParams).apply {
            setMargins(margin, margin, margin, margin)
        }
        requestLayout()
    }

    override fun setSelected(selected: Boolean) {
        val changed = isSelected != selected
        super.setSelected(selected)
        // Only the name overlay reacts to selection, and it cross-fades rather than
        // popping in and out.
        if (changed) binding.voiceUserNameDisplay.fadeBy(isSelected)
    }

    private fun isRinging() = state == VoiceDisplayState.RINGING && animateAvatarWhenRinging

    private fun setVoiceState(newState: VoiceDisplayState) {
        if (state == newState) return
        state = newState
        setBackgroundResource(
            if (newState == VoiceDisplayState.SPEAKING) {
                R.drawable.drawable_voice_user_background_speaking
            } else {
                defaultVoiceStateBackground
            },
        )
        val alpha = if (newState == VoiceDisplayState.DISCONNECTED && fadeWhenDisconnected) 0.3f else 1f
        binding.voiceUserAvatar.alpha = alpha
        this.alpha = alpha
        if (isRinging()) {
            if (!ringAnimator.isRunning) ringAnimator.start()
        } else if (ringAnimator.isRunning) {
            ringAnimator.cancel()
            this.alpha = alpha
        }
    }

    private fun View.resize(size: Int) {
        layoutParams = layoutParams.apply { width = size; height = size }
    }

    private companion object {
        const val RING_PULSE_DURATION_MS = 1_000L
    }
}
