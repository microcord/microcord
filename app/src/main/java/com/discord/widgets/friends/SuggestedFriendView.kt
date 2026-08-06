package com.discord.widgets.friends

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.utilities.icon.IconUtils
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
                if (avatar.height > 0) "$base?size=${IconUtils.getMediaProxySize(avatar.height)}" else base
            }
            else -> IconUtils.getDefaultAvatar(IconUtils.getDefaultAvatarIndex(userId, discriminator))
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
        this.publicName.visibility = if (publicName.isNullOrBlank()) GONE else VISIBLE
    }

    fun setUser(user: FriendUserUi, publicName: String? = null) {
        if (user.avatarUrl == null) setAvatarUrl(user.id, user.discriminator, null) else avatar.setImageURI(user.avatarUrl)
        setUsername(user.username)
        setPublicName(publicName)
        contentDescription = listOfNotNull(user.username, publicName).joinToString(", ")
        decline.contentDescription = context.getString(R.string.decline)
        send.contentDescription = context.getString(R.string.add_friend)
    }

    fun setOnDeclineClicked(callback: () -> Unit) {
        decline.setOnClickListener { callback() }
    }

    fun setOnSendClicked(callback: () -> Unit) {
        send.setOnClickListener { callback() }
    }
}
