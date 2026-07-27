package com.discord.widgets.guild_role_subscriptions

import android.content.Intent
import android.net.Uri
import com.discord.app.AppFragment
import com.discord.dialogs.ImageUploadDialog
import com.discord.media_picker.MediaPicker

/**
 * The confirm/crop dialog every role-subscription image field shows after the chooser returns.
 *
 * Reference: `MGImages.prepareImageUpload(uri, mimeType, fragmentManager, provider, callback,
 * previewType)`. That helper is not reconstructed — `MGImages` documents the omission — but
 * everything it needs does exist, so this calls [ImageUploadDialog] directly. Fold the two together
 * when `MGImages.prepareImageUpload` lands.
 *
 * Every role-subscription image (plan cover, tier artwork, member badge) previews at
 * [ImageUploadDialog.PreviewType.GUILD_SUBSCRIPTION_ROLE_AVATAR], matching the reference.
 */
internal fun AppFragment.showRoleSubscriptionImageUploadDialog(
    uri: Uri,
    mimeType: String,
    onImageSelected: (String) -> Unit,
) {
    ImageUploadDialog.show(
        fragmentManager = parentFragmentManager,
        uri = uri,
        provider = mediaPickerProvider(),
        mimeType = mimeType,
        cropResultCallback = onImageSelected,
        previewType = ImageUploadDialog.PreviewType.GUILD_SUBSCRIPTION_ROLE_AVATAR,
    )
}

/**
 * Adapts an [AppFragment] to [MediaPicker.Provider].
 *
 * `AppFragment` supplies all three members but does not declare the interface, and its `imageFile`
 * is a property rather than the `getImageFile()` the interface asks for, so the two cannot be
 * unified without editing `AppFragment`.
 */
private fun AppFragment.mediaPickerProvider(): MediaPicker.Provider {
    val fragment = this
    return object : MediaPicker.Provider {
        override fun requireContext() = fragment.requireContext()

        override fun getImageFile() = fragment.imageFile

        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) {
            fragment.startActivityForResult(intent, requestCode)
        }
    }
}
