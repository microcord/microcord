package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetAddMediaToOriginalPostSheetBinding
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.images.MGImages

/**
 * RN's offer, when the author sends media into their own forum post before anyone replied, to put it
 * on the post itself (where the forum list takes its thumbnail from) rather than in a reply. It
 * previews the post with the media, uploads with the sheet still open, and closes once the post has it.
 */
class WidgetAddMediaToOriginalPostSheet : AppBottomSheet() {
    private var request: Request? = null

    private class Request(
        val channelId: Long,
        val firstMessageId: Long,
        val uploads: List<UploadAttachment>,
        val onAdded: () -> Unit,
        val onDontAdd: () -> Unit,
    )

    override fun getContentViewResId() = R.layout.widget_add_media_to_original_post_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The uploads and callbacks live with the composer that opened it; a recreated sheet has neither.
        val request = request ?: return dismiss()
        val binding = WidgetAddMediaToOriginalPostSheetBinding.bind(view)
        bindPreview(binding, request)
        binding.addMediaToPostAdd.setOnClickListener {
            setUploading(binding, true)
            ForumPostCreateManager.addMediaToOriginalPost(
                requireContext(),
                request.channelId,
                request.firstMessageId,
                request.uploads,
                onAdded = {
                    request.onAdded()
                    dismissAllowingStateLoss()
                },
                onFailed = { if (view.isAttachedToWindow) setUploading(binding, false) },
            )
        }
        binding.addMediaToPostDontAdd.setOnClickListener {
            dismiss()
            request.onDontAdd()
        }
        binding.addMediaToPostCancel.setOnClickListener { dismiss() }
    }

    private fun bindPreview(binding: WidgetAddMediaToOriginalPostSheetBinding, request: Request) {
        val post = StoreStream.getChannels().getChannel(request.channelId)
        val first = StoreStream.getForumPostMessages().getForumPostFirstMessage(request.channelId)
        val author = first?.author
        binding.addMediaPostAuthor.text = author?.globalName ?: author?.username
        binding.addMediaPostTime.text = postedAgo(SnowflakeUtils.toTimestamp(request.firstMessageId))
        binding.addMediaPostTitle.text = post?.name
        binding.addMediaPostContent.text = first?.content
        binding.addMediaPostContent.visibility = if (first?.content.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.addMediaPostCount.text = (post?.messageCount ?: 0).toString()
        val media = request.uploads.firstOrNull { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") }
        MGImages.setRoundingParams(binding.addMediaPostThumbnail, cornerRadius = 8 * resources.displayMetrics.density, circle = false)
        MGImages.setImage(binding.addMediaPostThumbnail, media?.uri?.toString())
    }

    private fun postedAgo(time: Long): String {
        val minutes = ((System.currentTimeMillis() - time) / 60_000L).coerceAtLeast(1L)
        val (resource, count) = when {
            minutes < 60 -> R.string.thread_browser_timestamp_minutes to minutes
            minutes < 60 * 24 -> R.string.thread_browser_timestamp_hours to minutes / 60
            minutes < 60 * 24 * 30 -> R.string.thread_browser_timestamp_days to minutes / (60 * 24)
            else -> return getString(R.string.thread_browser_timestamp_more_than_month)
        }
        return getString(resource).replace("{count}", count.toString())
    }

    private fun setUploading(binding: WidgetAddMediaToOriginalPostSheetBinding, uploading: Boolean) {
        // Stays brand coloured with a spinner in place of its label, as RN's does.
        binding.addMediaToPostAdd.isClickable = !uploading
        binding.addMediaToPostAdd.text = if (uploading) null else getString(R.string.forum_add_media_to_original_post_add)
        binding.addMediaToPostProgress.visibility = if (uploading) View.VISIBLE else View.GONE
        binding.addMediaToPostDontAdd.isEnabled = !uploading
        binding.addMediaToPostCancel.isEnabled = !uploading
        isCancelable = !uploading
    }

    companion object {
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            firstMessageId: Long,
            uploads: List<UploadAttachment>,
            onAdded: () -> Unit,
            onDontAdd: () -> Unit,
        ) = WidgetAddMediaToOriginalPostSheet().apply {
            request = Request(channelId, firstMessageId, uploads, onAdded, onDontAdd)
        }.show(fragmentManager, WidgetAddMediaToOriginalPostSheet::class.java.name)
    }
}
