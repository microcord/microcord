package com.discord.widgets.guild_role_subscriptions

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.api.role.GuildRole
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

class GuildSubscriptionRoleImageUploadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val title: TextView
    private val description: TextView
    private val uploadButton: MaterialButton
    private val image: SimpleDraweeView
    private val editImage: ImageView
    private var avatarSize = resources.getDimensionPixelSize(R.dimen.avatar_size_huge)
    private var onImageRemovedListener: (() -> Unit)? = null
    private var onImageChooserClickListener: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild_role_subscription_image_upload, this)
        title = requireNotNull(findViewById(R.id.guild_subscription_custom_image_title))
        description = requireNotNull(findViewById(R.id.guild_subscription_upload_image_label))
        uploadButton = requireNotNull(findViewById(R.id.guild_subscription_upload_image))
        image = requireNotNull(findViewById(R.id.guild_subscription_image))
        editImage = requireNotNull(findViewById(R.id.guild_subscription_edit_image))

        context.obtainStyledAttributes(attrs, R.styleable.GuildSubscriptionRoleImageUploadView).run {
            image.hierarchy.actualImageScaleType = if (
                getBoolean(
                    R.styleable.GuildSubscriptionRoleImageUploadView_use_center_scale_type,
                    false,
                )
            ) {
                ScalingUtils.ScaleType.CENTER
            } else {
                ScalingUtils.ScaleType.CENTER_CROP
            }
            when (getInt(R.styleable.GuildSubscriptionRoleImageUploadView_image_style, 0)) {
                0 -> image.setBackgroundResource(themedResource(R.attr.primary_700_circle))
                1 -> image.setBackgroundResource(R.drawable.drawable_rect_rounded_bg_tertiary)
            }
            title.text = getString(R.styleable.GuildSubscriptionRoleImageUploadView_title)
                ?: context.getString(R.string.guild_role_subscription_tier_detail_custom_image_label)
            description.text = getString(R.styleable.GuildSubscriptionRoleImageUploadView_description)
                ?: context.getString(R.string.guild_role_subscription_tier_detail_custom_image_description)
            avatarSize = getDimensionPixelSize(
                R.styleable.GuildSubscriptionRoleImageUploadView_image_size,
                avatarSize,
            )
            recycle()
        }
        val chooseImage = OnClickListener { onImageChooserClickListener?.invoke() }
        uploadButton.setOnClickListener(chooseImage)
        image.setOnClickListener(chooseImage)
        editImage.setOnClickListener(chooseImage)
    }

    fun configureUI(onImageRemoved: () -> Unit, onImageChooserClicked: () -> Unit) {
        onImageRemovedListener = onImageRemoved
        onImageChooserClickListener = onImageChooserClicked
    }

    fun updateImage(memberIcon: String?, guildRole: GuildRole?) {
        val uri = memberIcon?.takeUnless(String::isBlank) ?: roleIconUri(guildRole)
        updateImage(uri)
    }

    private fun updateImage(imageUrl: String?) {
        val uri = imageUrl?.takeUnless(String::isBlank)
        image.setImageURI(uri)
        updateImageVisibility(uri != null)
    }

    private fun updateImageVisibility(hasImage: Boolean) {
        editImage.visibility = if (hasImage) View.VISIBLE else View.GONE
        uploadButton.setText(
            if (hasImage) R.string.avatar_upload_edit_media
            else R.string.guild_role_subscription_tier_detail_custom_image_button,
        )
    }

    private fun roleIconUri(role: GuildRole?): String? = when {
        !role?.icon.isNullOrBlank() ->
            "https://cdn.discordapp.com/role-icons/${role!!.id}/${role.icon}.webp?size=${
                mediaProxySize(resources.getDimensionPixelSize(R.dimen.avatar_size_huge))
            }"
        !role?.unicodeEmoji.isNullOrBlank() -> unicodeEmojiUri(role!!.unicodeEmoji!!)
        else -> null
    }

    private fun unicodeEmojiUri(value: String): String? {
        val codePoints = value.codePoints().toArray().joinToString("_") { it.toString(16) }
        val resource = resources.getIdentifier("emoji_$codePoints", "raw", context.packageName)
        return resource.takeIf { it != 0 }?.let { "res:///$it" }
    }

    private fun themedResource(attribute: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.resourceId
    }

    companion object {
        private val MEDIA_PROXY_SIZES = intArrayOf(
            16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
            160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048,
            3072, 4096,
        )

        internal fun mediaProxySize(size: Int) = MEDIA_PROXY_SIZES.firstOrNull { it >= size } ?: 4096

        internal fun storeAssetImage(applicationId: Long?, imageAssetId: Long, size: Int): String? =
            applicationId?.let {
                "https://cdn.discordapp.com/app-assets/$it/store/$imageAssetId.jpg?size=${mediaProxySize(size)}"
            }
    }
}
