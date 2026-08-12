package com.discord.widgets.chat.input.attachments

import android.content.DialogInterface
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetAttachmentBottomSheetBinding
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.time.TimeUtils
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.imagepipeline.image.ImageInfo
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

    private var _binding: WidgetAttachmentBottomSheetBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var attachment: Attachment<*>? = null
    private var attachmentImageInfo: ImageInfo? = null
    private var attachmentViewSize: Int? = null

    private var isSpoiler = false
    private var onFileRemoved: (() -> Unit)? = null
    private var onMarkSpoiler: ((Boolean) -> Unit)? = null
    private var onEditImage: (() -> Unit)? = null
    private var onDismissed: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_attachment_bottom_sheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attachment = argumentsOrDefault.getParcelable(EXTRA_ATTACHMENT)
        isSpoiler = argumentsOrDefault.getBoolean(EXTRA_SPOILER, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetAttachmentBottomSheetBinding.bind(view)

        binding.attachmentFilename.text = attachment?.displayName

        binding.attachmentDuration.apply {
            val durationMs = videoDurationMs()
            binding.attachmentVideoIndicator.visibility = if (durationMs == null) View.GONE else View.VISIBLE
            visibility = if (durationMs == null) View.GONE else View.VISIBLE
            if (durationMs != null) {
                binding.attachmentPreview.layoutParams =
                    (binding.attachmentPreview.layoutParams as ConstraintLayout.LayoutParams).apply {
                        dimensionRatio = "1"
                    }
                text = if (durationMs > 0) {
                    TimeUtils.toFriendlyStringSimple(durationMs)
                } else {
                    getString(R.string.video)
                }
            }
        }

        binding.attachmentPreview.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            attachmentViewSize = right - left
            updateSpoilerViewSize()
            v.contentDescription = attachment?.displayName
        }

        markSpoiler(isSpoiler)
        loadAttachment()

        binding.attachmentRemoveItem.setOnClickListener {
            onFileRemoved?.invoke()
            dismiss()
        }
        binding.attachmentMarkSpoiler.setOnClickListener {
            val next = !isSpoiler
            markSpoiler(next)
            onMarkSpoiler?.invoke(next)
        }
        binding.attachmentEditImage.apply {
            visibility = if (onEditImage == null || videoDurationMs() != null) View.GONE else View.VISIBLE
            setOnClickListener {
                val edit = onEditImage
                dismiss()
                Handler(Looper.getMainLooper()).post { edit?.invoke() }
            }
        }
        setPeekHeightBottomView(binding.attachmentRemoveItem)
    }

    override fun onDestroyView() {
        attachmentImageInfo = null
        attachmentViewSize = null
        _binding = null
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onFileRemoved = null
        onMarkSpoiler = null
        onEditImage = null
        onDismissed?.invoke()
    }

    private fun markSpoiler(spoiler: Boolean) {
        isSpoiler = spoiler
        binding.attachmentMarkSpoilerCheckbox.isChecked = spoiler
        binding.attachmentSpoilerCover.visibility = if (spoiler) View.VISIBLE else View.GONE
    }

    private fun loadAttachment() {
        val uri = attachment?.uri ?: return
        MGImages.setImage(
            view = binding.attachmentPreview,
            urls = listOf(uri.toString()),
            controllerListener = object : BaseControllerListener<ImageInfo>() {
                override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
                    val binding = _binding ?: return
                    attachmentImageInfo = imageInfo
                    val width = imageInfo?.width ?: 0
                    val height = imageInfo?.height ?: 0
                    val params = binding.attachmentPreview.layoutParams as ConstraintLayout.LayoutParams
                    // Portrait and square images are shown square; only landscape gets its own ratio,
                    // which is what stops a tall image from taking over the whole sheet.
                    params.dimensionRatio = if (width > height) "$width:$height" else "1"
                    binding.attachmentPreview.layoutParams = params
                    updateSpoilerViewSize()
                }
            },
        )
    }

    private fun videoDurationMs(): Long? {
        val attachment = attachment ?: return null
        val resolver = context?.contentResolver ?: return null
        val mimeType = runCatching { resolver.getType(attachment.uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                attachment.displayName.substringAfterLast('.', "").lowercase(),
            )
        if (mimeType?.startsWith("video/") != true) return null

        return runCatching {
            resolver.query(
                attachment.uri,
                arrayOf(MediaStore.Video.VideoColumns.DURATION),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun updateSpoilerViewSize() {
        val binding = _binding ?: return
        val imageInfo = attachmentImageInfo ?: return
        val viewSize = attachmentViewSize ?: return
        val (width, height) = EmbedResourceUtils.calculateScaledSize(
            imageInfo.width,
            imageInfo.height,
            viewSize,
            viewSize,
            resources,
        )
        binding.attachmentSpoilerCover.layoutParams =
            (binding.attachmentSpoilerCover.layoutParams as ConstraintLayout.LayoutParams).apply {
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
            onEditImage: (() -> Unit)? = null,
            onDismissed: (() -> Unit)? = null,
        ): AttachmentBottomSheet = AttachmentBottomSheet().apply {
            arguments = bundleOf(EXTRA_ATTACHMENT to attachment, EXTRA_SPOILER to spoiler)
            this.onFileRemoved = onFileRemoved
            this.onMarkSpoiler = onMarkSpoiler
            this.onEditImage = onEditImage
            this.onDismissed = onDismissed
            show(fragmentManager, AttachmentBottomSheet::class.java.name)
        }
    }
}
