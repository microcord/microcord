package com.discord.utilities.textprocessing

import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

object MessageTextRenderer {
    data class Result(val text: DraweeSpanStringBuilder, val plainText: String)

    internal sealed class Segment {
        data class Text(val value: String) : Segment()
        data class Mention(val value: String, val roleColor: Int? = null) : Segment()
        data class CustomEmoji(val name: String, val id: Long, val animated: Boolean) : Segment()
    }

    private val tokenPattern = Regex(
        "<@!?(\\d+)>|<@&(\\d+)>|<#(\\d+)>|<(a?):([A-Za-z0-9_]+):(\\d+)>|@(everyone|here)",
    )

    fun render(message: Message, view: com.discord.utilities.view.text.SimpleDraweeSpanTextView): Result {
        val guildId = message.guildId
        val members = guildId?.let { StoreStream.getGuilds().getMembers()[it] }.orEmpty()
        val userNames = StoreStream.getUsers().getUsers().mapValues { it.value.username }.toMutableMap()
        message.mentions.orEmpty().forEach { user ->
            userNames[user.id] = members[user.id]?.nick?.takeUnless(String::isBlank)
                ?: user.member?.nick?.takeUnless(String::isBlank)
                ?: user.username
        }
        return render(
            message.content.orEmpty(),
            userNames,
            StoreStream.getChannels().getChannelNames(),
            guildId?.let { StoreStream.getGuilds().getRoles()[it] }.orEmpty(),
            view.context,
            view.textSize,
        )
    }

    internal fun resolve(
        content: String,
        userNames: Map<Long, String>,
        channelNames: Map<Long, String>,
        roles: Map<Long, GuildRole>,
    ): List<Segment> {
        val segments = ArrayList<Segment>()
        var cursor = 0
        tokenPattern.findAll(content).forEach { match ->
            if (match.range.first > cursor) segments += Segment.Text(content.substring(cursor, match.range.first))
            val groups = match.groupValues
            segments += when {
                groups[1].isNotEmpty() -> Segment.Mention("@${userNames[groups[1].toLong()] ?: "invalid-user"}")
                groups[2].isNotEmpty() -> roles[groups[2].toLong()]?.let { Segment.Mention("@${it.name}", it.color) }
                    ?: Segment.Text("deleted-role")
                groups[3].isNotEmpty() -> Segment.Mention("#${channelNames[groups[3].toLong()] ?: "deleted-channel"}")
                groups[6].isNotEmpty() -> Segment.CustomEmoji(groups[5], groups[6].toLong(), groups[4].isNotEmpty())
                else -> Segment.Mention(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < content.length) segments += Segment.Text(content.substring(cursor))
        return segments
    }

    private fun render(
        content: String,
        userNames: Map<Long, String>,
        channelNames: Map<Long, String>,
        roles: Map<Long, GuildRole>,
        context: android.content.Context,
        viewTextSizePx: Float,
    ): Result {
        val segments = resolve(content, userNames, channelNames, roles)
        val builder = DraweeSpanStringBuilder()
        val plain = StringBuilder()
        segments.forEach { segment ->
            when (segment) {
                is Segment.Text -> {
                    builder.append(segment.value)
                    plain.append(segment.value)
                }
                is Segment.Mention -> {
                    val start = builder.length
                    builder.append(segment.value)
                    plain.append(segment.value)
                    val foreground = segment.roleColor?.takeUnless { it == 0 }
                        ?: themedColor(context, R.attr.theme_chat_mention_foreground)
                    val background = segment.roleColor?.takeUnless { it == 0 }?.let { ColorUtils.setAlphaComponent(it, 25) }
                        ?: themedColor(context, R.attr.theme_chat_mention_background)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(foreground, 255)), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(background), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Segment.CustomEmoji -> {
                    appendCustomEmoji(builder, segment, context, viewTextSizePx)
                    plain.append(':').append(segment.name).append(':')
                }
            }
        }
        applyInlineMarkdown(builder)
        return Result(builder, stripInlineMarkdown(plain.toString()))
    }

    private fun appendCustomEmoji(
        builder: DraweeSpanStringBuilder,
        emoji: Segment.CustomEmoji,
        context: android.content.Context,
        textSizePx: Float,
    ) {
        val size = textSizePx.toInt().coerceAtLeast((16 * context.resources.displayMetrics.density).toInt())
        val extension = if (emoji.animated) "gif" else "png"
        val request = ImageRequestBuilder.newBuilderWithSource(
            Uri.parse("https://cdn.discordapp.com/emojis/${emoji.id}.$extension?size=48&quality=lossless"),
        ).setResizeOptions(ResizeOptions(size, size)).build()
        val controller = Fresco.newDraweeControllerBuilder()
            .setImageRequest(request)
            .setAutoPlayAnimations(true)
            .build()
        val start = builder.length
        builder.append('\u2002')
        builder.setImageSpan(
            context,
            GenericDraweeHierarchyBuilder(context.resources).build(),
            controller,
            start,
            start,
            size,
            size,
            false,
            2,
        )
    }

    private fun applyInlineMarkdown(text: DraweeSpanStringBuilder) {
        applyMarkup(text, Regex("\\*\\*(.+?)\\*\\*")) { StyleSpan(Typeface.BOLD) }
        applyMarkup(text, Regex("__(.+?)__")) { UnderlineSpan() }
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

    internal fun stripInlineMarkdown(value: String): String = listOf(
        Regex("\\*\\*(.+?)\\*\\*"), Regex("__(.+?)__"), Regex("~~(.+?)~~"),
        Regex("`([^`]+)`"), Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"),
    ).fold(value) { text, pattern -> text.replace(pattern, "$1") }

    private fun themedColor(context: android.content.Context, attribute: Int) = TypedValue().let {
        context.theme.resolveAttribute(attribute, it, true)
        it.data
    }
}
