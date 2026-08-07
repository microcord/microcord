package com.discord.widgets.forums

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableString
import android.text.Spannable
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.Gravity
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.databinding.ForumBrowserPostViewBinding
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.reaction.MessageReaction
import com.discord.models.member.GuildMember
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.drawee.view.SimpleDraweeView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import com.discord.utilities.user.UserUtils.getDisplayName

class ForumBrowserPostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ForumBrowserPostViewBinding.inflate(LayoutInflater.from(context), this)
    private val avatarSizePx = dpToPixels(16)
    private val overlapAmountPx = dpToPixels(4)

    var layout: ForumLayout = ForumLayout.LIST

    fun configurePost(post: PostData) {
        binding.formPostTitle.text = post.channel.name
        configureFirstMessage(post)
        configureOwner(post)
        configureAttachment(post)
        configureMediaGrid(post)
        configureReaction(post.mostCommonReaction, post.isDefaultReaction, post.firstMessage?.id)
        configureMessageCount(post)
        configureTags(post)
        configureReadState(post)
        val isTyping = post.typingUsers.isNotEmpty()
        if (isTyping) configureTyping(post)
        binding.timestamp.text = ForumUtils.getActiveAgoTimestamp(context, post.mostRecentMessageTimestamp)
        binding.timestamp.visibility = if (isTyping || binding.timestamp.text == null) View.GONE else View.VISIBLE
        binding.typingUserSummary.visibility = if (isTyping) View.VISIBLE else View.GONE
        binding.typingIndicator.visibility = if (isTyping) View.VISIBLE else View.GONE
        if (!isTyping) binding.typingIndicator.stop()
        binding.pinnedIcon.visibility = if (post.isPinned) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 23) foreground = themedDrawable(R.attr.bg_pressed_highlight)
    }

    private fun configureFirstMessage(post: PostData) {
        val message = post.firstMessage
        if (message == null || message.content == null) {
            binding.forumPostDescText.visibility = View.GONE
            return
        }
        binding.forumPostDescText.visibility = View.VISIBLE
        val label = when {
            post.isFirstMessageBlocked -> context.getString(R.string.reply_quote_message_blocked)
            message.hasStickers() -> context.getString(R.string.sticker)
            message.hasEmbeds() -> context.getString(R.string.embed_links)
            else -> null
        }
        if (label == null) {
            binding.forumPostDescText.setDraweeSpanStringBuilder(renderMessage(message.content, post))
        } else {
            binding.forumPostDescText.text = SpannableString("$label\u00a0").apply {
                setSpan(StyleSpan(Typeface.ITALIC), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun renderMessage(content: String, post: PostData): DraweeSpanStringBuilder {
        var rendered = content
        post.firstMessage?.mentions.orEmpty().forEach { mention ->
            val member = post.guildMembers[mention.id]
            rendered = rendered.replace(Regex("<@!?${mention.id}>"), "@${mention.getDisplayName(member?.nick)}")
        }
        post.channelNames.forEach { (id, name) -> rendered = rendered.replace("<#$id>", "#$name") }
        post.guildRoles.forEach { (id, role) -> rendered = rendered.replace("<@&$id>", "@${role.name}") }
        rendered = rendered.replace(Regex("<a?:([A-Za-z0-9_]+):\\d+>"), ":$1:")
        return DraweeSpanStringBuilder(rendered).also(::applyInlineMarkdown)
    }

    private fun applyInlineMarkdown(text: DraweeSpanStringBuilder) {
        applyMarkup(text, Regex("\\*\\*(.+?)\\*\\*")) { StyleSpan(Typeface.BOLD) }
        applyMarkup(text, Regex("__(.+?)__")) { StyleSpan(Typeface.BOLD) }
        applyMarkup(text, Regex("~~(.+?)~~")) { StrikethroughSpan() }
        applyMarkup(text, Regex("`([^`]+)`")) { TypefaceSpan("monospace") }
        applyMarkup(text, Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { StyleSpan(Typeface.ITALIC) }
    }

    private fun applyMarkup(text: DraweeSpanStringBuilder, pattern: Regex, span: () -> Any) {
        while (true) {
            val match = pattern.find(text) ?: return
            val value = match.groupValues[1]
            text.replace(match.range.first, match.range.last + 1, value)
            text.setSpan(span(), match.range.first, match.range.first + value.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun configureOwner(post: PostData) {
        val owner = post.owner
        binding.forumPostOwnerAvatar.visibility = if (owner == null) View.GONE else View.VISIBLE
        binding.forumPostOwnerName.visibility = binding.forumPostOwnerAvatar.visibility
        if (owner == null) return
        val member = post.guildMembers[owner.id]
        binding.forumPostOwnerName.text = member?.nick ?: owner.username
        binding.forumPostOwnerName.setTextColor(GuildMember.getColor(member, ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)))
        MGImages.setRoundingParams(binding.forumPostOwnerAvatar, cornerRadius = 0f, circle = true)
        IconUtils.setIcon(
            binding.forumPostOwnerAvatar,
            owner,
            R.dimen.avatar_size_profile_small,
            guildMember = member,
        )
    }

    private fun configureAttachment(post: PostData) {
        if (post.showsMediaGrid()) {
            binding.inlineMediaImagePreview.visibility = View.GONE
            return
        }
        val attachment = post.firstMessage?.attachments?.firstOrNull { !it.isSpoiler }
        if (attachment == null) {
            binding.inlineMediaImagePreview.visibility = View.GONE
            return
        }
        binding.inlineMediaImagePreview.visibility = View.VISIBLE
        if (attachment.type == MessageAttachmentType.FILE) {
            binding.inlineMediaImagePreview.setImageResource(fileDrawable(attachment.filename))
            return
        }
        val size = dpToPixels(64)
        val resized = "${attachment.proxyUrl}?width=$size&height=$size"
        val extension = if (Build.VERSION.SDK_INT == 28 || Build.VERSION.SDK_INT == 29) "png" else "webp"
        val requests = arrayOf(
            ImageRequest.fromUri("$resized&format=$extension"),
            ImageRequest.fromUri(resized),
        )
        binding.inlineMediaImagePreview.controller = Fresco.newDraweeControllerBuilder()
            .setOldController(binding.inlineMediaImagePreview.controller)
            .setFirstAvailableImageRequests(requests)
            .build()
    }

    private fun configureMediaGrid(post: PostData) {
        val media = post.firstMessage?.attachments.orEmpty()
            .filter { it.type != MessageAttachmentType.FILE }
        val showsGrid = post.showsMediaGrid() && media.isNotEmpty()
        binding.mediaPostGrid.visibility = if (showsGrid) View.VISIBLE else View.GONE
        if (showsGrid) {
            // A gallery post is taller than a media-channel post, which is fixed at 16:9.
            binding.mediaPostGrid.layoutParams.height =
                dpToPixels(if (post.isMediaPost) MEDIA_GRID_HEIGHT_DP else GALLERY_GRID_HEIGHT_DP)
        }
        if (binding.mediaPostGrid.visibility != View.VISIBLE) {
            binding.mediaPostGrid.removeAllViews()
            return
        }
        binding.mediaPostGrid.tag = post.channel.id
        binding.mediaPostGrid.post {
            if (binding.mediaPostGrid.tag != post.channel.id) return@post
            val root = binding.mediaPostGrid
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) return@post
            root.removeAllViews()
            val gap = dpToPixels(2)
            val shown = media.take(4)

            fun addTile(index: Int, left: Int, top: Int, tileWidth: Int, tileHeight: Int) {
                val attachment = shown[index]
                val tile = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(tileWidth, tileHeight).also {
                        it.leftMargin = left
                        it.topMargin = top
                    }
                    setBackgroundColor(Color.BLACK)
                }
                if (attachment.isSpoiler) {
                    tile.addView(TextView(context).apply {
                        text = context.getString(R.string.spoiler).uppercase()
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    val source = "${attachment.proxyUrl}?width=640&height=360&format=webp"
                    if ((attachment.height ?: 0) > (attachment.width ?: 0)) {
                        tile.addView(SimpleDraweeView(context).apply {
                            hierarchy.actualImageScaleType = ScalingUtils.ScaleType.CENTER_CROP
                            alpha = 0.2f
                            setImageURI(source)
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                    tile.addView(SimpleDraweeView(context).apply {
                        hierarchy.actualImageScaleType = if ((attachment.height ?: 0) > (attachment.width ?: 0)) {
                            ScalingUtils.ScaleType.FIT_CENTER
                        } else {
                            ScalingUtils.ScaleType.CENTER_CROP
                        }
                        setImageURI(source)
                    }, FrameLayout.LayoutParams(-1, -1))
                    if (attachment.type == MessageAttachmentType.VIDEO) {
                        tile.addView(ImageView(context).apply {
                            setImageResource(R.drawable.ic_play_circle_outline_white_24dp)
                            contentDescription = context.getString(R.string.video)
                        }, FrameLayout.LayoutParams(dpToPixels(32), dpToPixels(32), Gravity.CENTER))
                    }
                }
                root.addView(tile)
            }

            mediaGridRects(shown.size, width, height, gap).forEachIndexed { index, rect ->
                addTile(index, rect.left, rect.top, rect.width, rect.height)
            }
            val extra = media.size - shown.size
            if (extra > 0) {
                root.addView(TextView(context).apply {
                    text = "+$extra"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dpToPixels(8), 0, dpToPixels(8), 0)
                    setBackgroundColor(Color.argb(180, 0, 0, 0))
                }, FrameLayout.LayoutParams(-2, dpToPixels(24), Gravity.END or Gravity.BOTTOM).apply {
                    rightMargin = dpToPixels(4)
                    bottomMargin = dpToPixels(4)
                })
            }
        }
    }

    private fun fileDrawable(filename: String): Int {
        val extension = filename.substringAfterLast('.', "")
        return when {
            extension.matches(Regex("(?:avi|flv|wmv|mov|mp4)")) -> R.drawable.ic_file_video
            extension == "pdf" -> R.drawable.ic_file_acrobat
            extension == "ae" -> R.drawable.ic_file_ae
            extension == "sketch" -> R.drawable.ic_file_sketch
            extension == "ai" -> R.drawable.ic_file_ai
            extension.matches(Regex("(?:rar|zip|7z|tar|tar\\.gz)")) -> R.drawable.ic_file_archive
            extension.matches(CODE_EXTENSION) -> R.drawable.ic_file_code
            extension.matches(Regex("(?:txt|rtf|doc|docx|md|pages|ppt|pptx|pptm|key|log)")) ->
                R.drawable.ic_file_document
            extension.matches(Regex("(?:xls|xlsx|numbers|csv|xliff)")) -> R.drawable.ic_file_spreadsheet
            extension.matches(Regex("(?:html|xhtml|htm|js|xml|xls|xsd|css|styl)")) -> R.drawable.ic_file_webcode
            else -> R.drawable.ic_file_unknown
        }
    }

    private fun configureReaction(value: MessageReaction?, isDefault: Boolean, messageId: Long?) {
        binding.reaction.setMeTextColor(
            if (isDefault) {
                requireNotNull(ContextCompat.getColorStateList(context, R.color.status_red_500))
            } else {
                ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            },
        )
        binding.reaction.visibility = if (value != null && messageId != null) View.VISIBLE else View.GONE
        if (value != null && messageId != null) binding.reaction.configure(value, messageId, true)
    }

    private fun configureMessageCount(post: PostData) {
        val count = messageCountText(post.messageCount)
        binding.messageCountContainer.visibility = if (count == null) View.GONE else View.VISIBLE
        binding.messageCount.text = count
        val unread = unreadCountText(post.unreadCount)?.let {
            context.getString(R.string.forum_post_message_count_short_parenthetical).replace("{count}", it)
        }
        binding.unreadMessageCount.text = unread
        binding.unreadMessageCount.visibility = if (post.readState.hasSeenBefore && unread != null) View.VISIBLE else View.GONE
    }

    private fun configureReadState(post: PostData) {
        // The reference marks a post nobody has opened with a badge beside the author, not a dot.
        val neverSeen = !post.readState.hasSeenBefore && !post.isMe
        binding.forumPostNewBadge.visibility = if (neverSeen) View.VISIBLE else View.GONE
        binding.neverSeenBefore.visibility = View.GONE
        val alpha = if (post.readState.hasUnread) 1f else 0.8f
        binding.forumPostOwnerAvatar.alpha = alpha
        binding.forumPostOwnerName.alpha = alpha
        binding.formPostTitle.setTextColor(
            ColorCompat.getThemedColor(context, if (post.readState.hasUnread) R.attr.colorHeaderPrimary else R.attr.primary_300),
        )
        binding.forumPostDescText.setTextColor(
            ColorCompat.getThemedColor(context, if (post.readState.hasUnread) R.attr.colorHeaderSecondary else R.attr.primary_300),
        )
    }

    private fun configureTyping(post: PostData) {
        binding.typingUserSummary.configure(post.typingUsers, false)
        binding.typingIndicator.start()
        val shown = minOf(post.typingUsers.size, 3)
        val params = binding.typingIndicator.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart = max(0, (avatarSizePx - overlapAmountPx) * shown - dpToPixels(8))
        binding.typingIndicator.layoutParams = params
    }

    /**
     * A gallery row swaps the inline thumbnail for the collage, and falls back to the list body
     * when the post has no media to show — as the reference does.
     */
    private fun PostData.showsMediaGrid(): Boolean = isMediaPost || layout == ForumLayout.GALLERY

    private fun themedDrawable(attribute: Int): Drawable? =
        context.getThemedDrawableRes(attribute).takeIf { it != 0 }
            ?.let { AppCompatResources.getDrawable(context, it) }


    private companion object {
        const val MEDIA_GRID_HEIGHT_DP = 192
        const val GALLERY_GRID_HEIGHT_DP = 225

        val CODE_EXTENSION = Regex(
            "(?:c\\+\\+|cpp|cc|c|h|hpp|mm|m|json|js|rb|rake|py|asm|fs|pyc|dtd|cgi|bat|rss|" +
                "java|graphml|idb|lua|o|gml|prl|sls|conf|cmake|make|sln|vbe|cxx|wbf|vbs|" +
                "r|wml|php|bash|applescript|fcgi|yaml|ex|exs|sh|ml|actionscript)",
        )
    }
    /** Chips are recycled rather than re-inflated: a row rebinds on every list diff. */
    private fun configureTags(post: PostData) {
        val container = binding.forumPostTags
        val chips = post.tags.visible
        val needed = chips.size + if (post.tags.overflow > 0) 1 else 0
        container.visibility = if (needed == 0) View.GONE else View.VISIBLE
        while (container.childCount < needed) {
            val params = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { flexShrink = 0f }
            // Set on the params rather than the view: a child has no layoutParams until it is added.
            if (container.childCount > 0) params.marginStart = dpToPixels(4)
            container.addView(ForumPostTagView(context), params)
        }
        for (index in 0 until container.childCount) {
            val view = container.getChildAt(index) as ForumPostTagView
            view.visibility = if (index < needed) View.VISIBLE else View.GONE
            when {
                index < chips.size -> view.configureTag(chips[index])
                index == chips.size && post.tags.overflow > 0 -> view.configureOverflow(post.tags.overflow)
            }
        }
    }

}

internal data class MediaGridRect(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun mediaGridRects(count: Int, width: Int, height: Int, gap: Int): List<MediaGridRect> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(MediaGridRect(0, 0, width, height))
    val halfWidth = (width - gap) / 2
    val halfHeight = (height - gap) / 2
    if (count == 2) return listOf(
        MediaGridRect(0, 0, halfWidth, height),
        MediaGridRect(halfWidth + gap, 0, halfWidth, height),
    )
    val rects = mutableListOf(
        MediaGridRect(0, 0, halfWidth, if (count == 3) height else halfHeight),
        MediaGridRect(halfWidth + gap, 0, halfWidth, halfHeight),
        MediaGridRect(halfWidth + gap, halfHeight + gap, halfWidth, halfHeight),
    )
    if (count >= 4) rects += MediaGridRect(0, halfHeight + gap, halfWidth, halfHeight)
    return rects


}
