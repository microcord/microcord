package com.discord.widgets.friends

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.ImageRequestBuilder

class SuggestedFriendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val avatar: SimpleDraweeView
    private val decline: AppCompatImageView
    private val publicName: TextView
    private val send: AppCompatImageView
    private val username: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.suggested_friend_view, this)
        avatar = requireNotNull(findViewById(R.id.suggested_friend_avatar))
        decline = requireNotNull(findViewById(R.id.suggested_friend_decline))
        publicName = requireNotNull(findViewById(R.id.suggested_friend_public_name))
        send = requireNotNull(findViewById(R.id.suggested_friend_send_request))
        username = requireNotNull(findViewById(R.id.suggested_friend_username))
    }

    fun setAvatarUrl(userId: Long, discriminator: Int?, avatarHash: String?) {
        val url = when {
            userId == -1L -> avatarHash
            avatarHash != null -> {
                val extension = if (avatarHash.startsWith("a_")) "gif" else "webp"
                val base = "https://cdn.discordapp.com/avatars/$userId/$avatarHash.$extension"
                if (avatar.height > 0) "$base?size=${mediaProxySize(avatar.height)}" else base
            }
            else -> "asset://asset/images/default_avatar_${discriminator?.rem(5) ?: 0}.png"
        }
        avatar.controller = if (url == null) {
            null
        } else {
            Fresco.newDraweeControllerBuilder()
                .setOldController(avatar.controller)
                .setImageRequest(ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).build())
                .setAutoPlayAnimations(true)
                .build()
        }
    }

    fun setUsername(username: String) {
        this.username.text = username
    }

    fun setPublicName(publicName: String?) {
        this.publicName.text = publicName
    }

    fun setOnDeclineClicked(callback: () -> Unit) {
        decline.setOnClickListener { callback() }
    }

    fun setOnSendClicked(callback: () -> Unit) {
        send.setOnClickListener { callback() }
    }

    private fun mediaProxySize(requested: Int) = MEDIA_PROXY_SIZES.first { it >= requested }

    private companion object {
        val MEDIA_PROXY_SIZES = intArrayOf(
            16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
            160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048,
            3072, 4096,
        )
    }
}
