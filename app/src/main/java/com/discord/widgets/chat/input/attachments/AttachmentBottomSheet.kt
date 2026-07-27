package com.discord.widgets.chat.input.attachments

import android.content.DialogInterface
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo
import com.google.android.material.checkbox.MaterialCheckBox
import com.lytefast.flexinput.model.Attachment

/**
 * Long-pressing a queued attachment opens this: a large preview, a spoiler toggle, and remove.
 *
 * Two things are sized at runtime rather than in XML, both because they depend on the decoded
 * image:
 *  - the preview's aspect ratio, so a landscape photo is not letterboxed into a square; and
 *  - the spoiler cover, which must match the *drawn* image rather than the preview's bounds — a
 *    cover the size of the view would blur the margins around a portrait image too.
 *
 * The sheet dismisses itself in `onPause`. It edits a live selection, and coming back from the
 * background to a sheet describing an attachment that may no longer be queued is worse than
 * re-opening it.
 */
class AttachmentBottomSheet : AppBottomSheet() {

    private var attachment: Attachment<*>? = null
    private var attachmentImageInfo: ImageInfo? = null
    private var attachmentViewSize: Int? = null

    private var isSpoiler = false
    private var onFileRemoved: (() -> Unit)? = null
    private var onMarkSpoiler: ((Boolean) -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    private lateinit var preview: SimpleDraweeView
    private lateinit var spoilerCover: FrameLayout
    private lateinit var spoilerCheckbox: MaterialCheckBox

    override fun getContentViewResId(): Int = R.layout.widget_attachment_bottom_sheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attachment = argumentsOrDefault.getParcelable(EXTRA_ATTACHMENT)
        isSpoiler = argumentsOrDefault.getBoolean(EXTRA_SPOILER, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preview = view.findViewById(R.id.attachment_preview)
        spoilerCover = view.findViewById(R.id.attachment_spoiler_cover)
        spoilerCheckbox = view.findViewById(R.id.attachment_mark_spoiler_checkbox)
        val filename = view.findViewById<TextView>(R.id.attachment_filename)
        val markSpoiler = view.findViewById<View>(R.id.attachment_mark_spoiler)
        val removeItem = view.findViewById<View>(R.id.attachment_remove_item)

        filename.text = attachment?.displayName

        // The duration chip belongs to video attachments; the published FlexInput's `Attachment`
        // carries no duration, so there is nothing to show. Hidden rather than left as its
        // placeholder text.
        view.findViewById<View>(R.id.attachment_duration).visibility = View.GONE

        preview.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            attachmentViewSize = right - left
            updateSpoilerViewSize()
            v.contentDescription = attachment?.displayName
        }

        markSpoiler(isSpoiler)
        loadAttachment()

        removeItem.setOnClickListener {
            onFileRemoved?.invoke()
            dismiss()
        }
        markSpoiler.setOnClickListener {
            val next = !isSpoiler
            markSpoiler(next)
            onMarkSpoiler?.invoke(next)
        }
        setPeekHeightBottomView(removeItem)
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onFileRemoved = null
        onMarkSpoiler = null
        onDismissed?.invoke()
    }

    private fun markSpoiler(spoiler: Boolean) {
        isSpoiler = spoiler
        spoilerCheckbox.isChecked = spoiler
        spoilerCover.visibility = if (spoiler) View.VISIBLE else View.GONE
    }

    private fun loadAttachment() {
        val uri = attachment?.uri ?: return
        MGImages.setImage(
            view = preview,
            urls = listOf(uri.toString()),
            controllerListener = object : BaseControllerListener<ImageInfo>() {
                override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
                    attachmentImageInfo = imageInfo
                    val width = imageInfo?.width ?: 0
                    val height = imageInfo?.height ?: 0
                    val params = preview.layoutParams as ConstraintLayout.LayoutParams
                    // Portrait and square images are shown square; only landscape gets its own ratio,
                    // which is what stops a tall image from taking over the whole sheet.
                    params.dimensionRatio = if (width > height) "$width:$height" else "1"
                    preview.layoutParams = params
                    updateSpoilerViewSize()
                }
            },
        )
    }

    private fun updateSpoilerViewSize() {
        val imageInfo = attachmentImageInfo ?: return
        val viewSize = attachmentViewSize ?: return
        val (width, height) = EmbedResourceUtils.calculateScaledSize(
            imageInfo.width,
            imageInfo.height,
            viewSize,
            viewSize,
            resources,
        )
        spoilerCover.layoutParams = (spoilerCover.layoutParams as ConstraintLayout.LayoutParams).apply {
            this.width = width
            this.height = height
        }
    }

    companion object {
        private const val EXTRA_ATTACHMENT = "EXTRA_ATTACHMENT"
        private const val EXTRA_SPOILER = "EXTRA_SPOILER"

        fun show(
            fragmentManager: FragmentManager,
            attachment: Attachment<*>,
            spoiler: Boolean,
            onFileRemoved: () -> Unit,
            onMarkSpoiler: (Boolean) -> Unit,
            onDismissed: (() -> Unit)? = null,
        ): AttachmentBottomSheet = AttachmentBottomSheet().apply {
            arguments = bundleOf(EXTRA_ATTACHMENT to attachment, EXTRA_SPOILER to spoiler)
            this.onFileRemoved = onFileRemoved
            this.onMarkSpoiler = onMarkSpoiler
            this.onDismissed = onDismissed
            show(fragmentManager, AttachmentBottomSheet::class.java.name)
        }
    }
}
