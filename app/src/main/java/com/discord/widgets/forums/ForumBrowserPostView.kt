package com.discord.widgets.forums

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.reaction.MessageReaction
import com.discord.models.member.GuildMember
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ReactionView
import com.discord.views.typing.TypingDots
import com.discord.views.user.UserSummaryView
import com.discord.views.user.avatarUrl
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.ImageRequest
import kotlin.math.max

class ForumBrowserPostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val title: TextView
    private val description: SimpleDraweeSpanTextView
    private val ownerAvatar: SimpleDraweeView
    private val ownerName: TextView
    private val tag: ForumPostTagView
    private val imagePreview: SimpleDraweeView
    private val messageCount: TextView
    private val messageCountContainer: LinearLayout
    private val neverSeen: ImageView
    private val pinned: ImageView
    private val reaction: ReactionView
    private val timestamp: TextView
    private val typingDots: TypingDots
    private val typingUsers: UserSummaryView
    private val unreadCount: TextView
    private val avatarSizePx = dpToPixels(16)
    private val overlapAmountPx = dpToPixels(4)

    init {
        inflate(context, R.layout.forum_browser_post_view, this)
        title = requireNotNull(findViewById(R.id.form_post_title))
        description = requireNotNull(findViewById(R.id.forum_post_desc_text))
        ownerAvatar = requireNotNull(findViewById(R.id.forum_post_owner_avatar))
        ownerName = requireNotNull(findViewById(R.id.forum_post_owner_name))
        tag = requireNotNull(findViewById(R.id.forum_post_tag))
        imagePreview = requireNotNull(findViewById(R.id.inline_media_image_preview))
        messageCount = requireNotNull(findViewById(R.id.message_count))
        messageCountContainer = requireNotNull(findViewById(R.id.message_count_container))
        neverSeen = requireNotNull(findViewById(R.id.never_seen_before))
        pinned = requireNotNull(findViewById(R.id.pinned_icon))
        reaction = requireNotNull(findViewById(R.id.reaction))
        timestamp = requireNotNull(findViewById(R.id.timestamp))
        typingDots = requireNotNull(findViewById(R.id.typing_indicator))
        typingUsers = requireNotNull(findViewById(R.id.typing_user_summary))
        unreadCount = requireNotNull(findViewById(R.id.unread_message_count))
    }

    fun configurePost(post: PostData) {
        title.text = post.channel.name
        configureFirstMessage(post)
        configureOwner(post)
        configureAttachment(post)
        configureReaction(post.mostCommonReaction, post.isDefaultReaction, post.firstMessage?.id)
        configureMessageCount(post)
        tag.visibility = if (post.tag == null) View.GONE else View.VISIBLE
        post.tag?.let(tag::configureTag)
        configureReadState(post)
        val isTyping = post.typingUsers.isNotEmpty()
        if (isTyping) configureTyping(post)
        timestamp.text = activeAgoText(post.mostRecentMessageTimestamp)
        timestamp.visibility = if (isTyping || timestamp.text == null) View.GONE else View.VISIBLE
        typingUsers.visibility = if (isTyping) View.VISIBLE else View.GONE
        typingDots.visibility = if (isTyping) View.VISIBLE else View.GONE
        if (!isTyping) typingDots.stop()
        pinned.visibility = if (post.isPinned) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 23) foreground = themedDrawable(R.attr.bg_pressed_highlight)
    }

    private fun configureFirstMessage(post: PostData) {
        val message = post.firstMessage
        if (message == null || message.content == null) {
            description.visibility = View.GONE
            return
        }
        description.visibility = View.VISIBLE
        val label = when {
            post.isFirstMessageBlocked -> context.getString(R.string.reply_quote_message_blocked)
            message.hasStickers() -> context.getString(R.string.sticker)
            message.hasEmbeds() -> context.getString(R.string.embed_links)
            else -> null
        }
        description.text = if (label == null) message.content else SpannableString("$label\u00a0").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun configureOwner(post: PostData) {
        val owner = post.owner
        ownerAvatar.visibility = if (owner == null) View.GONE else View.VISIBLE
        ownerName.visibility = ownerAvatar.visibility
        if (owner == null) return
        val member = post.guildMembers[owner.id]
        ownerName.text = member?.nick ?: owner.username
        ownerName.setTextColor(GuildMember.getColor(member, ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)))
        ownerAvatar.setImageURI(avatarUrl(owner, member))
    }

    private fun configureAttachment(post: PostData) {
        val attachment = post.firstMessage?.attachments?.firstOrNull { !it.isSpoiler }
        if (attachment == null) {
            imagePreview.visibility = View.GONE
            return
        }
        imagePreview.visibility = View.VISIBLE
        if (attachment.type == MessageAttachmentType.FILE) {
            imagePreview.setImageResource(fileDrawable(attachment.filename))
            return
        }
        val size = dpToPixels(64)
        val resized = "${attachment.proxyUrl}?width=$size&height=$size"
        val extension = if (Build.VERSION.SDK_INT == 28 || Build.VERSION.SDK_INT == 29) "png" else "webp"
        val requests = arrayOf(
            ImageRequest.fromUri("$resized&format=$extension"),
            ImageRequest.fromUri(resized),
        )
        imagePreview.controller = Fresco.newDraweeControllerBuilder()
            .setOldController(imagePreview.controller)
            .setFirstAvailableImageRequests(requests)
            .build()
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
        reaction.setMeTextColor(
            if (isDefault) {
                requireNotNull(ContextCompat.getColorStateList(context, R.color.status_red_500))
            } else {
                ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            },
        )
        reaction.visibility = if (value != null && messageId != null) View.VISIBLE else View.GONE
        if (value != null && messageId != null) reaction.configure(value, messageId, true)
    }

    private fun configureMessageCount(post: PostData) {
        val count = messageCountText(post.messageCount)
        messageCountContainer.visibility = if (count == null) View.GONE else View.VISIBLE
        messageCount.text = count
        val unread = unreadCountText(post.unreadCount)?.let {
            context.getString(R.string.forum_post_message_count_short).replace("{count}", it)
        }
        unreadCount.text = unread
        unreadCount.visibility = if (post.readState.hasSeenBefore && unread != null) View.VISIBLE else View.GONE
    }

    private fun configureReadState(post: PostData) {
        neverSeen.visibility = if (!post.readState.hasSeenBefore && !post.isMe) View.VISIBLE else View.GONE
        val alpha = if (post.readState.hasUnread) 1f else 0.8f
        ownerAvatar.alpha = alpha
        ownerName.alpha = alpha
        title.setTextColor(
            ColorCompat.getThemedColor(context, if (post.readState.hasUnread) R.attr.colorHeaderPrimary else R.attr.colorInteractiveNormal),
        )
        description.setTextColor(
            ColorCompat.getThemedColor(context, if (post.readState.hasUnread) R.attr.colorHeaderSecondary else R.attr.colorInteractiveNormal),
        )
    }

    private fun configureTyping(post: PostData) {
        typingUsers.configure(post.typingUsers, false)
        typingDots.start()
        val shown = minOf(post.typingUsers.size, 3)
        val params = typingDots.layoutParams as ViewGroup.MarginLayoutParams
        params.marginStart = max(0, (avatarSizePx - overlapAmountPx) * shown - dpToPixels(8))
        typingDots.layoutParams = params
    }

    private fun activeAgoText(timestamp: Long?): CharSequence? {
        if (timestamp == null) return null
        val elapsed = System.currentTimeMillis() - timestamp
        if (elapsed < 0) return null
        val (resource, count) = when {
            elapsed < HOUR -> R.string.form_post_active_ago_timestamp_minutes to elapsed / MINUTE
            elapsed < DAY -> R.string.form_post_active_ago_timestamp_hours to elapsed / HOUR
            elapsed < THIRTY_DAYS -> R.string.form_post_active_ago_timestamp_days to elapsed / DAY
            else -> return context.getString(R.string.form_post_active_ago_timestamp_more_than_month)
        }
        return context.getString(resource).replace("{count}", count.toString())
    }

    private fun themedDrawable(attribute: Int): Drawable? =
        context.getThemedDrawableRes(attribute).takeIf { it != 0 }
            ?.let { AppCompatResources.getDrawable(context, it) }


    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
        const val THIRTY_DAYS = 2_592_000_000L
        val CODE_EXTENSION = Regex(
            "(?:c\\+\\+|cpp|cc|c|h|hpp|mm|m|json|js|rb|rake|py|asm|fs|pyc|dtd|cgi|bat|rss|" +
                "java|graphml|idb|lua|o|gml|prl|sls|conf|cmake|make|sln|vbe|cxx|wbf|vbs|" +
                "r|wml|php|bash|applescript|fcgi|yaml|ex|exs|sh|ml|actionscript)",
        )
    }
}
