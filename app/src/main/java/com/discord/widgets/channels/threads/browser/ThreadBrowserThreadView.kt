package com.discord.widgets.channels.threads.browser

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.role.GuildRole
import com.discord.databinding.ThreadBrowserThreadViewBinding
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.ThemedColor
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ThreadBrowserThreadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val binding = ThreadBrowserThreadViewBinding.inflate(LayoutInflater.from(context), this)

    fun setThreadData(threadData: ThreadData) {
        binding.threadName.text = threadData.channel.name.orEmpty()
        val owner = threadData.owner ?: CoreUser(threadData.channel.ownerId)
        val ownerMember = threadData.guildMembers[owner.id]
        binding.threadMessage.text = startedBy(GuildMember.getNickOrUsername(ownerMember, owner), ownerMember)
        binding.threadTimestamp.text = null
        binding.threadTimestamp.visibility = View.GONE
        binding.threadTimestampSeparator.visibility = View.GONE

        when (threadData) {
            is ThreadData.ActiveThread -> configureActiveThreadUI(threadData)
            is ThreadData.ArchivedThread -> configureArchivedThreadUI(threadData)
        }
    }

    private fun configureActiveThreadUI(data: ThreadData.ActiveThread) {
        val recentMessage = data.message
        val content = recentMessage?.content
        if (recentMessage == null || content == null) {
            binding.threadTimestampSeparator.visibility = View.GONE
            binding.threadTimestamp.visibility = View.GONE
            if (data.owner == null) {
                binding.threadMessage.setText(R.string.thread_browser_no_recent_messages)
                configureAvatar(null)
            } else {
                configureAvatar(data.owner, data.guildMembers[data.owner.id])
            }
            return
        }

        val authorApi = requireNotNull(recentMessage.author)
        val author = CoreUser(authorApi)
        val authorMember = data.guildMembers[author.id]
        val preview = when {
            data.isMessageBlocked -> boldPrefix(context.getString(R.string.reply_quote_message_blocked))
            recentMessage.hasStickers() -> boldPrefix(context.getString(R.string.reply_quote_sticker_mobile))
            recentMessage.hasAttachments() || recentMessage.hasEmbeds() ->
                boldPrefix(context.getString(R.string.reply_quote_no_text_content_mobile))
            content.isNotBlank() -> renderMessage(content, recentMessage, data)
            else -> null
        }
        preview?.let {
            val authorName = GuildMember.getNickOrUsername(authorMember, author)
            it.insert(0, "$authorName: ")
            applyMemberStyle(it, authorName.length, authorMember)
            binding.threadMessage.setDraweeSpanStringBuilder(it)
        }

        binding.threadTimestampSeparator.visibility = View.VISIBLE
        binding.threadTimestamp.visibility = View.VISIBLE
        configureAvatar(author, authorMember)
        binding.threadTimestamp.text = formatActivityTimestamp(snowflakeTimestamp(recentMessage.id))
    }

    private fun configureArchivedThreadUI(data: ThreadData.ArchivedThread) {
        val metadata = data.channel.threadMetadata
        val time = when (data.timestampMode) {
            TimestampMode.ArchivedAt -> parseUtcDate(metadata?.archiveTimestamp)
            TimestampMode.CreatedAt -> snowflakeTimestamp(data.channel.id)
        } ?: return
        val template = context.getString(
            if (data.timestampMode == TimestampMode.ArchivedAt) {
                R.string.thread_browser_archive_time
            } else {
                R.string.thread_browser_creation_time
            },
        )
        binding.threadTimestamp.text = template.replace("{time}", formatDateTimestamp(time).toString())
        binding.threadTimestamp.visibility = View.VISIBLE
        configureAvatar(data.owner, data.owner?.let { data.guildMembers[it.id] })
    }

    private fun configureAvatar(user: User?, member: GuildMember? = null) {
        binding.threadIcon.visibility = if (user == null) View.VISIBLE else View.GONE
        binding.threadAvatar.visibility = if (user == null) View.GONE else View.VISIBLE
        if (user != null) {
            MGImages.setRoundingParams(binding.threadAvatar, cornerRadius = 0f, circle = true)
            IconUtils.setIcon(binding.threadAvatar, user, R.dimen.avatar_size_reply, guildMember = member)
        }
    }

    private fun startedBy(username: String, member: GuildMember?): SpannableStringBuilder {
        val text = context.getString(R.string.thread_browser_started_by).replace("$[](authorHook)", username)
        return SpannableStringBuilder(text).also {
            val start = text.indexOf(username)
            if (start >= 0) applyMemberStyle(it, start, start + username.length, member)
        }
    }

    private fun renderMessage(content: String, source: Message, data: ThreadData.ActiveThread): DraweeSpanStringBuilder {
        var rendered = content
        source.mentions.orEmpty().forEach { mention ->
            val user = CoreUser(mention)
            val displayName = GuildMember.getNickOrUsername(data.guildMembers[user.id], user)
            rendered = rendered.replace(Regex("<@!?${user.id}>"), "@$displayName")
        }
        data.channelNames.forEach { (id, channelName) -> rendered = rendered.replace("<#$id>", "#$channelName") }
        data.guildRoles.forEach { (id, role) -> rendered = rendered.replace("<@&$id>", "@${role.name}") }
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

    private fun applyMarkup(
        text: DraweeSpanStringBuilder,
        pattern: Regex,
        span: () -> Any,
    ) {
        while (true) {
            val match = pattern.find(text) ?: return
            val value = match.groupValues[1]
            text.replace(match.range.first, match.range.last + 1, value)
            text.setSpan(
                span(),
                match.range.first,
                match.range.first + value.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun boldPrefix(text: String) = DraweeSpanStringBuilder("$text\u00a0").apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyMemberStyle(text: Spannable, length: Int, member: GuildMember?) =
        applyMemberStyle(text, 0, length, member)

    private fun applyMemberStyle(text: Spannable, start: Int, end: Int, member: GuildMember?) {
        text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (member != null) {
            text.setSpan(
                ForegroundColorSpan(GuildMember.getColor(member, ThemedColor.resolve(context, R.attr.colorHeaderPrimary, Color.WHITE))),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun formatActivityTimestamp(time: Long): CharSequence = when (val bucket = activityTimestampBucket(time)) {
        is TimestampBucket.Minutes -> context.getString(R.string.thread_browser_timestamp_minutes)
            .replace("{count}", bucket.count.toString())
        is TimestampBucket.Hours -> context.getString(R.string.thread_browser_timestamp_hours)
            .replace("{count}", bucket.count.toString())
        is TimestampBucket.Days -> context.getString(R.string.thread_browser_timestamp_days)
            .replace("{count}", bucket.count.toString())
        TimestampBucket.MoreThanMonth -> context.getString(R.string.thread_browser_timestamp_more_than_month)
    }

    private fun formatDateTimestamp(time: Long): CharSequence {
        val age = System.currentTimeMillis() - time
        return when {
            age < HOUR -> context.getString(R.string.thread_browser_timestamp_minutes)
                .replace("{count}", maxOf(1L, age / MINUTE).toString())
            age < DAY -> context.getString(R.string.thread_browser_timestamp_hours)
                .replace("{count}", (age / HOUR).toString())
            else -> DateUtils.formatDateTime(
                context,
                time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
            )
        }
    }

    sealed class ThreadData(
        open val channel: Channel,
        open val owner: User?,
        open val guildMembers: Map<Long, GuildMember>,
    ) {
        data class ActiveThread(
            override val channel: Channel,
            override val owner: User?,
            val message: Message?,
            val myUserId: Long,
            override val guildMembers: Map<Long, GuildMember>,
            val guildRoles: Map<Long, GuildRole>,
            val channelNames: Map<Long, String>,
            val isMessageBlocked: Boolean,
        ) : ThreadData(channel, owner, guildMembers)

        data class ArchivedThread(
            override val channel: Channel,
            override val guildMembers: Map<Long, GuildMember>,
            override val owner: User?,
            val timestampMode: TimestampMode,
        ) : ThreadData(channel, owner, guildMembers)
    }

    enum class TimestampMode { ArchivedAt, CreatedAt }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val MONTH = 30 * DAY

        fun snowflakeTimestamp(id: Long) = SnowflakeUtils.toTimestamp(id)

        private val TRAILING_SUBMILLI_DIGITS = Regex("(\\.\\d{3})\\d+Z$")

        // SimpleDateFormat isn't thread-safe; cached per-thread since this is re-parsed on every
        // row bind while the (scrollable) thread browser list is open.
        private val utcDateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun parseUtcDate(value: String?): Long? {
            if (value == null) return null
            val normalized = value.replace(TRAILING_SUBMILLI_DIGITS, "$1Z")
            return runCatching { utcDateFormat.get().parse(normalized)?.time }.getOrNull()
        }
    }
}

internal sealed interface TimestampBucket {
    data class Minutes(val count: Long) : TimestampBucket
    data class Hours(val count: Long) : TimestampBucket
    data class Days(val count: Long) : TimestampBucket
    data object MoreThanMonth : TimestampBucket
}

internal fun activityTimestampBucket(time: Long, now: Long = System.currentTimeMillis()): TimestampBucket {
    val age = now - time
    return when {
        age < 60_000L -> TimestampBucket.Minutes(1)
        age < 3_600_000L -> TimestampBucket.Minutes(age / 60_000L)
        age < 86_400_000L -> TimestampBucket.Hours(age / 3_600_000L)
        age < 2_592_000_000L -> TimestampBucket.Days(age / 86_400_000L)
        else -> TimestampBucket.MoreThanMonth
    }
}
