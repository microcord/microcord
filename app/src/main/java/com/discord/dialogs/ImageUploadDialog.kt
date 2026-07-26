package com.discord.dialogs

import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.media_picker.MediaPicker
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

/**
 * Preview step between picking an image and uploading it: shows the chosen image at the size it
 * will actually be used, and offers "Crop" or "Upload".
 *
 * The dialog does not upload anything itself — [cropResultCallback] receives a `data:` URL, which is
 * how avatars, guild icons and emoji are all submitted.
 *
 * GIFs skip the crop affordance entirely: uCrop would flatten the animation, so for `image/gif` the
 * crop row is made invisible (not gone, so the layout does not jump) and only upload remains.
 */
class ImageUploadDialog : AppDialog(R.layout.image_upload_dialog) {

    /**
     * How large the preview is drawn, and whether it is circular.
     *
     * The order matters: [USER_AVATAR] and [GUILD_AVATAR] are the two circular previews, which the
     * reference selects by ordinal.
     */
    enum class PreviewType(@DimenRes val previewSizeDimenId: Int) {
        EMOJI(R.dimen.emoji_size),
        USER_AVATAR(R.dimen.avatar_size_extra_large_account),
        GUILD_AVATAR(R.dimen.avatar_size_xxlarge),
        GUILD_SUBSCRIPTION_ROLE_AVATAR(R.dimen.avatar_size_huge);

        val isCircular: Boolean
            get() = this == USER_AVATAR || this == GUILD_AVATAR
    }

    lateinit var uri: Uri
    lateinit var provider: MediaPicker.Provider
    var mimeType: String? = null
    var cropResultCallback: ((String) -> Unit)? = null
    var previewType: PreviewType? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val previewType = this.previewType
        val mimeType = this.mimeType
        // Shown through the companion the fields are always set; a restored instance has lost them
        // and has nothing to preview.
        if (previewType == null || mimeType == null) {
            dismiss()
            return
        }

        val isGif = mimeType == GIF_MIME_TYPE
        val cropButton = view.findViewById<TextView>(R.id.notice_crop)
        cropButton.visibility = if (isGif) View.INVISIBLE else View.VISIBLE
        if (!isGif) {
            cropButton.setOnClickListener { requestCrop() }
        }

        view.findViewById<MaterialButton>(R.id.notice_upload).setOnClickListener {
            MGImages.requestDataUrl(context, uri, mimeType, cropResultCallback)
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.CANCEL_BUTTON).setOnClickListener { dismiss() }

        val previewSize = resources.getDimensionPixelSize(previewType.previewSizeDimenId)
        val preview = view.findViewById<SimpleDraweeView>(R.id.notice_image)
        preview.layoutParams = preview.layoutParams.apply {
            height = previewSize
            width = previewSize
        }
        if (previewType.isCircular) {
            MGImages.setRoundingParams(
                view = preview,
                cornerRadius = previewSize.toFloat(),
                circle = true,
                overlayColor = themedColor(R.attr.primary_600),
            )
        }
        MGImages.setImage(preview, uri.toString(), previewSize, previewSize)
    }

    private fun requestCrop() {
        MediaPicker.requestCrop(
            provider = provider,
            inputUri = uri,
            theme = MediaPicker.CropTheme(
                toolbarColor = themedColor(R.attr.color_brand_500),
                statusBarColor = themedColor(R.attr.color_brand_630),
                toolbarWidgetColor = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.white),
                activeControlsColor = themedColor(R.attr.color_brand_500),
            ),
        )
        dismiss()
    }

    private fun themedColor(attributeResId: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attributeResId, typedValue, true)
        return if (typedValue.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    companion object {
        private const val GIF_MIME_TYPE = "image/gif"

        /**
         * Entry point used by `MGImages.prepareImageUpload`.
         *
         * @param provider the screen that receives the crop activity result.
         * @param cropResultCallback receives the `data:` URL of the image to upload.
         */
        fun show(
            fragmentManager: FragmentManager,
            uri: Uri,
            provider: MediaPicker.Provider,
            mimeType: String,
            cropResultCallback: ((String) -> Unit)?,
            previewType: PreviewType,
        ) {
            ImageUploadDialog().apply {
                this.uri = uri
                this.provider = provider
                this.mimeType = mimeType
                this.cropResultCallback = cropResultCallback
                this.previewType = previewType
            }.show(fragmentManager, ImageUploadDialog::class.java.name)
        }
    }
}
