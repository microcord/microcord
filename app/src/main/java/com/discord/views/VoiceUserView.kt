package com.discord.views

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.MainThread
import com.discord.R
import com.discord.stores.StoreVoiceParticipants
import com.discord.views.user.avatarUrl
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.BasePostprocessor
import com.facebook.imagepipeline.request.ImageRequestBuilder

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
    private val avatar: SimpleDraweeView
    private val nameDisplay: TextView
    private var defaultVoiceStateBackground = android.R.color.transparent
    private var state: VoiceDisplayState? = null
    private var voiceUser: StoreVoiceParticipants.VoiceUser? = null
    private var previousAvatarUrl: String? = null
    private var fadeWhenDisconnected = false
    private var animateAvatarWhenRinging = false
    private var bitmapListener: (Bitmap, String?) -> Unit = { _, _ -> }
    private val ringAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.1f).apply {
        duration = 1_000
        repeatMode = ObjectAnimator.REVERSE
        repeatCount = 1
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_voice_user, this)
        avatar = requireNotNull(findViewById(R.id.voice_user_avatar))
        nameDisplay = requireNotNull(findViewById(R.id.voice_user_name_display))
        context.obtainStyledAttributes(
            attrs,
            intArrayOf(
                R.attr.vuv_animate_avatar_when_ringing,
                R.attr.vuv_avatar_size,
                R.attr.vuv_fade_when_disconnected,
            ),
        ).run {
            try {
                val size = getDimensionPixelSize(1, 0)
                if (size > 0) setAvatarSize(size)
                fadeWhenDisconnected = getBoolean(2, false)
                animateAvatarWhenRinging = getBoolean(0, false)
            } finally {
                recycle()
            }
        }
    }

    @MainThread
    fun setVoiceUser(voiceUser: StoreVoiceParticipants.VoiceUser) =
        setVoiceUser(voiceUser, R.dimen.avatar_size_standard)

    @MainThread
    fun setVoiceUser(voiceUser: StoreVoiceParticipants.VoiceUser, @DimenRes sizeDimenRes: Int) {
        if (this.voiceUser == voiceUser) return
        this.voiceUser = voiceUser
        val requestedSize = resources.getDimensionPixelSize(sizeDimenRes).takeIf { it > 0 }
        val url = avatarUrl(voiceUser.user, voiceUser.guildMember, requestedSize)
        if (url != previousAvatarUrl) {
            previousAvatarUrl = url
            val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).apply {
                postprocessor = object : BasePostprocessor() {
                    override fun process(bitmap: Bitmap) {
                        bitmapListener(bitmap.copy(Bitmap.Config.ARGB_8888, false), url)
                    }
                }
            }.build()
            avatar.controller = Fresco.newDraweeControllerBuilder()
                .setOldController(avatar.controller)
                .setImageRequest(request)
                .setAutoPlayAnimations(true)
                .build()
        }
        nameDisplay.text = voiceUser.displayName
        setVoiceState(voiceDisplayState(voiceUser))
    }

    @MainThread
    fun a(voiceUser: StoreVoiceParticipants.VoiceUser, @DimenRes sizeDimenRes: Int) =
        setVoiceUser(voiceUser, sizeDimenRes)

    @MainThread
    fun setAvatarSize(avatarSize: Int) {
        avatar.resize(avatarSize)
        nameDisplay.resize(avatarSize)
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
        avatar.layoutParams = (avatar.layoutParams as LayoutParams).apply {
            setMargins(margin, margin, margin, margin)
        }
        requestLayout()
    }

    override fun setSelected(selected: Boolean) {
        val changed = isSelected != selected
        super.setSelected(selected)
        if (!changed) return
        nameDisplay.animate().cancel()
        if (selected) {
            nameDisplay.visibility = View.VISIBLE
            nameDisplay.animate().alpha(1f).setDuration(200).start()
        } else {
            nameDisplay.animate().alpha(0f).setDuration(200).withEndAction {
                nameDisplay.visibility = View.GONE
            }.start()
        }
    }

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
        avatar.alpha = alpha
        this.alpha = alpha
        if (newState == VoiceDisplayState.RINGING && animateAvatarWhenRinging) {
            if (!ringAnimator.isRunning) ringAnimator.start()
        } else if (ringAnimator.isRunning) {
            ringAnimator.cancel()
            this.alpha = alpha
        }
    }

    private fun View.resize(size: Int) {
        layoutParams = layoutParams.apply { width = size; height = size }
    }
}
