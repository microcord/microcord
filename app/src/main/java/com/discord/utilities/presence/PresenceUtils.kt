package com.discord.utilities.presence

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityEmoji
import com.discord.api.activity.ActivityParty
import com.discord.api.activity.ActivityPlatform
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.models.presence.Presence
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.setVitoSpan
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder

/**
 * Renders a presence as the one line of text shown under a username, and exposes the
 * activity lookups the rest of the app needs.
 *
 * The status line is a priority list, not a concatenation: a custom status wins over a
 * rich presence, which wins over the plain online/idle/dnd label. The custom status
 * emoji is rendered as a leading image span, which is why the text is built as a
 * [DraweeSpanStringBuilder] rather than a plain string.
 */
object PresenceUtils {

    /**
     * Orders a user's activities so the most interesting one comes last.
     *
     * Ties are broken by creation time so the ordering is stable across presence updates.
     */
    @JvmStatic
    val ACTIVITY_COMPARATOR: Comparator<Activity> =
        compareBy({ it.type }, { it.isRichPresence() }, { it.createdAt })

    private const val STATUS_ICON_SIZE_DP = 12
    private const val STATUS_ICON_GAP_DP = 4

    /** EN SPACE; wider than a normal space so the emoji does not crowd the text. */
    private const val EMOJI_TEXT_SEPARATOR = ' '

    private const val CUSTOM_STATUS_EMOJI_SIZE_DP = 16

    /** `DraweeSpanStringBuilder.DraweeSpan.ALIGN_CENTER`. */
    private const val DRAWEE_SPAN_ALIGN_CENTER = 2

    // region activity lookup

    /** The first activity of [type], or `null`. */
    fun List<Activity>.getActivityByType(type: ActivityType): Activity? =
        firstOrNull { it.type == type }

    /** The first activity that is not a custom or hang status, i.e. the one worth describing. */
    val List<Activity>.primaryActivity: Activity?
        get() = firstOrNull { it.type != ActivityType.CUSTOM_STATUS && it.type != ActivityType.HANG }

    val List<Activity>.spotifyListeningActivity: Activity?
        get() = firstOrNull { it.isSpotifyActivity() }

    val List<Activity>.stageChannelActivity: Activity?
        get() = firstOrNull { it.isStageChannelActivity() }

    val Presence.primaryActivity: Activity? get() = activities?.primaryActivity

    val Presence.customStatusActivity: Activity?
        get() = activities?.getActivityByType(ActivityType.CUSTOM_STATUS)

    val Presence.playingActivity: Activity?
        get() = activities?.getActivityByType(ActivityType.PLAYING)

    val Presence.streamingActivity: Activity?
        get() = activities?.getActivityByType(ActivityType.STREAMING)

    val Presence.spotifyListeningActivity: Activity?
        get() = activities?.spotifyListeningActivity

    val Presence.stageChannelActivity: Activity? get() = activities?.stageChannelActivity

    /** The status text with no "Playing …" wrapper, for rows that pair it with [Activity.statusIcon]. */
    fun Activity.statusText(context: Context): String? =
        statusText(context.getString(R.string.embedded_activities_in_activity))

    /** Whether any activity should show the rich-presence icon next to the status text. */
    fun shouldShowRichPresenceIcon(presence: Presence?): Boolean =
        presence?.activities?.any { it.isRichPresence() } == true

    // endregion

    // region party sizes

    /** How many people are currently in the activity's party. */
    val ActivityParty.currentSize: Long get() = size?.firstOrNull() ?: 0L

    /** How many people the activity's party can hold. */
    val ActivityParty.maxSize: Long get() = size?.lastOrNull() ?: 0L

    /** How many party slots are still free, used to render "Join" affordances. */
    val ActivityParty.numOpenSlots: Long get() = maxSize - currentSize

    // endregion

    /**
     * Whether the user is online only from a phone, which is what the mobile indicator
     * around the avatar means.
     */
    val ClientStatuses.isMobile: Boolean
        get() = mobileStatus() == ClientStatus.ONLINE &&
            desktopStatus() != ClientStatus.ONLINE &&
            webStatus() != ClientStatus.ONLINE

    /** The plain "Online"/"Idle"/"Do Not Disturb"/"Offline" label for a presence. */
    @StringRes
    fun getStatusStringResForPresence(presence: Presence?): Int = when (presence?.status) {
        ClientStatus.ONLINE -> R.string.status_online
        ClientStatus.IDLE -> R.string.status_idle
        ClientStatus.DND -> R.string.status_dnd
        else -> R.string.status_offline
    }

    /**
     * The heading shown above a rich-presence card, e.g. "Playing on Xbox" or
     * "Listening to Spotify".
     */
    fun getActivityHeader(context: Context, activity: Activity): CharSequence = when (activity.type) {
        ActivityType.PLAYING -> {
            val platform = activity.platform?.displayName()
            if (platform != null) {
                context.renderString(
                    R.string.user_activity_header_playing_on_platform,
                    "platform" to platform,
                )
            } else {
                context.renderString(R.string.user_activity_header_playing)
            }
        }
        ActivityType.STREAMING -> context.renderString(
            R.string.user_activity_header_live_on_platform,
            "platform" to activity.name,
        )
        ActivityType.LISTENING -> context.renderString(
            R.string.user_activity_header_listening,
            "name" to activity.name,
        )
        ActivityType.WATCHING -> context.renderString(
            R.string.user_activity_header_watching,
            "name" to activity.name,
        )
        ActivityType.COMPETING -> context.renderString(
            R.string.user_activity_header_competing,
            "name" to activity.name,
        )
        else -> context.renderString(R.string.user_activity_header_playing)
    }

    /**
     * The status line for [presence], or `null` when there is nothing to say.
     *
     * @param isStreamingApplication true when the user is streaming into the current
     *   voice channel, which replaces the activity text with the stream description.
     * @param showFallbackStatusText when true, falls back to the plain online/idle label
     *   instead of returning `null`.
     */
    @JvmOverloads
    fun getStatusText(
        context: Context,
        presence: Presence?,
        isStreamingApplication: Boolean,
        showFallbackStatusText: Boolean = false,
    ): CharSequence? {
        presence?.customStatusActivity?.state?.let { return it }
        if (isStreamingApplication) return getApplicationStreamingString(context, presence)
        getActivityString(context, presence?.primaryActivity)?.let { return it }
        if (showFallbackStatusText) {
            return context.renderString(getStatusStringResForPresence(presence))
        }
        return null
    }

    /**
     * [getStatusText] preceded by the custom-status emoji, ready to hand to a
     * [SimpleDraweeSpanTextView].
     */
    @JvmOverloads
    fun getStatusDraweeSpanStringBuilder(
        context: Context,
        presence: Presence?,
        isStreamingApplication: Boolean,
        hideEmoji: Boolean,
        showFallbackStatusText: Boolean = false,
        animateCustomStatusEmoji: Boolean = false,
    ): DraweeSpanStringBuilder {
        val builder = DraweeSpanStringBuilder()
        val emoji = if (hideEmoji) null else presence?.customStatusActivity?.emoji
        val renderedEmoji = emoji != null &&
            builder.appendCustomStatusEmoji(context, emoji, animateCustomStatusEmoji)
        val statusText = getStatusText(context, presence, isStreamingApplication, showFallbackStatusText)
        if (statusText != null) {
            if (renderedEmoji) builder.append(EMOJI_TEXT_SEPARATOR)
            builder.append(statusText)
        }
        return builder
    }

    /**
     * Renders the one-line status a row shows under a name, and returns the glyph that belongs
     * beside it — surfaces differ in whether they have an icon slot or use a compound drawable.
     *
     * A custom status owns the text while the activity still owns the icon, so someone playing a
     * game with a status set reads as "<controller> feeling cute". Goes through a
     * [DraweeSpanStringBuilder] because a custom-status emoji is an image, not text.
     */
    @JvmStatic
    @JvmOverloads
    @DrawableRes
    fun setStatusLine(
        presence: Presence?,
        isApplicationStreaming: Boolean,
        textView: SimpleDraweeSpanTextView,
        showFallbackStatusText: Boolean = false,
    ): Int? {
        val activity = presence?.primaryActivity
        val custom = presence?.customStatusActivity
        val builder = DraweeSpanStringBuilder()
        val customText = custom?.state?.takeIf { it.isNotEmpty() }
        if (custom != null && (customText != null || custom.emoji != null)) {
            val renderedEmoji = custom.emoji?.let {
                builder.appendCustomStatusEmoji(textView.context, it, false)
            } == true
            customText?.let {
                if (renderedEmoji) builder.append(EMOJI_TEXT_SEPARATOR)
                builder.append(it)
            }
        } else if (isApplicationStreaming) {
            builder.append(presence?.playingActivity?.name.orEmpty())
        } else {
            activity?.statusText(textView.context)?.let(builder::append)
        }
        val describedActivity = builder.isNotEmpty()
        if (!describedActivity && showFallbackStatusText) {
            builder.append(textView.context.getString(getStatusStringResForPresence(presence)))
        }
        textView.setDraweeSpanStringBuilder(builder)
        textView.visibility = if (builder.isNotEmpty()) View.VISIBLE else View.GONE
        if (!describedActivity) return null
        return if (isApplicationStreaming) R.drawable.ic_status_streaming_16dp else activity?.statusIcon()
    }

    /** [setStatusLine] for rows with no icon slot of their own. */
    @JvmStatic
    @JvmOverloads
    fun setStatusLineWithLeadingIcon(
        presence: Presence?,
        isApplicationStreaming: Boolean,
        textView: SimpleDraweeSpanTextView,
        showFallbackStatusText: Boolean = false,
    ) {
        val icon = setStatusLine(presence, isApplicationStreaming, textView, showFallbackStatusText)
        val context = textView.context
        val drawable = icon?.let(context::getDrawable)?.mutate()?.apply {
            val size = (context.resources.displayMetrics.density * STATUS_ICON_SIZE_DP).toInt()
            setBounds(0, 0, size, size)
            if (icon != R.drawable.ic_status_streaming_16dp) {
                setTint(ColorCompat.getThemedColor(context, R.attr.colorTextPositive))
            }
        }
        textView.setCompoundDrawablesRelative(drawable, null, null, null)
        textView.compoundDrawablePadding =
            (context.resources.displayMetrics.density * STATUS_ICON_GAP_DP).toInt()
    }

    /**
     * The custom status alone, for surfaces that render the activity separately — a profile shows
     * what the user is doing in the card below, so repeating it above reads as a duplicate.
     */
    @JvmStatic
    @JvmOverloads
    fun setCustomStatusText(
        presence: Presence?,
        textView: SimpleDraweeSpanTextView,
        animateCustomStatusEmoji: Boolean = false,
    ) {
        val builder = DraweeSpanStringBuilder()
        val custom = presence?.customStatusActivity
        val renderedEmoji = custom?.emoji?.let {
            builder.appendCustomStatusEmoji(textView.context, it, animateCustomStatusEmoji)
        } == true
        custom?.state?.takeIf { it.isNotEmpty() }?.let {
            if (renderedEmoji) builder.append(EMOJI_TEXT_SEPARATOR)
            builder.append(it)
        }
        textView.setDraweeSpanStringBuilder(builder)
        textView.visibility = if (builder.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // region internals

    /**
     * One line describing what the user is doing, without any custom status.
     *
     * A stage listening session reads as "Watching", and Spotify and Crunchyroll name the track
     * and episode rather than the service.
     */
    private fun getActivityString(context: Context, activity: Activity?): CharSequence? {
        if (activity == null) return null
        val text = activity.statusText(context) ?: return null
        if (activity.isEmbeddedActivity()) return text
        return when (activity.statusPhrasing()) {
            ActivityType.PLAYING -> context.renderString(R.string.playing_game, "game" to text)
            ActivityType.STREAMING -> context.renderString(R.string.streaming, "name" to text)
            ActivityType.LISTENING -> context.renderString(R.string.listening_to, "name" to text)
            ActivityType.WATCHING -> context.renderString(R.string.watching, "name" to text)
            ActivityType.COMPETING -> context.renderString(R.string.competing, "name" to text)
            else -> null
        }
    }

    private fun getApplicationStreamingString(context: Context, presence: Presence?): CharSequence {
        val game = presence?.playingActivity
            ?: return context.renderString(R.string.streaming_a_game)
        return context.renderString(R.string.streaming, "name" to game.name)
    }

    /** PlayStation reports a bare code; everything else uses the enum's own name. */
    private fun ActivityPlatform.displayName(): String? = when (this) {
        ActivityPlatform.PS4 -> "PS4"
        ActivityPlatform.PS5 -> "PS5"
        ActivityPlatform.XBOX -> "Xbox"
        ActivityPlatform.SAMSUNG -> "Samsung"
        ActivityPlatform.SPOTIFY -> "Spotify"
        ActivityPlatform.META_QUEST -> "Meta Quest"
        ActivityPlatform.DESKTOP, ActivityPlatform.ANDROID, ActivityPlatform.IOS,
        ActivityPlatform.EMBEDDED, ActivityPlatform.UNKNOWN,
        -> null
    }

    /**
     * Appends the custom-status emoji, returning whether anything was drawn.
     *
     * Custom emoji become a Fresco image span; unicode emoji are appended as text
     * because the emoji-sprite renderer (`EmojiNode`) has not been reconstructed yet.
     */
    private fun DraweeSpanStringBuilder.appendCustomStatusEmoji(
        context: Context,
        emoji: ActivityEmoji,
        animate: Boolean,
    ): Boolean {
        val id = emoji.id
        if (id == null) {
            val name = emoji.name
            if (name.isNullOrEmpty()) return false
            append(name)
            return true
        }
        val sizePx = (context.resources.displayMetrics.density * CUSTOM_STATUS_EMOJI_SIZE_DP).toInt()
        val extension = if (emoji.animated) "gif" else "png"
        val start = length
        append('#')
        setVitoSpan(
            context = context,
            url = "https://cdn.discordapp.com/emojis/$id.$extension?size=32",
            start = start,
            end = start,
            width = sizePx,
            height = sizePx,
            alignment = DRAWEE_SPAN_ALIGN_CENTER,
            autoPlay = animate,
            scaleType = ScalingUtils.ScaleType.FIT_CENTER,
        )
        return true
    }

    /**
     * Resolves a Discord string resource: substitutes `{placeholder}` arguments, drops
     * the `!!` do-not-translate markers, and turns `**bold**` into a real bold span.
     */
    private fun Context.renderString(
        @StringRes resId: Int,
        vararg args: Pair<String, CharSequence?>,
    ): CharSequence {
        var text = getString(resId)
        args.forEach { (key, value) -> text = text.replace("{$key}", value?.toString() ?: "") }
        return applyBoldMarkers(text.replace("!!", ""))
    }

    private fun applyBoldMarkers(text: String): CharSequence {
        if (!text.contains(BOLD_MARKER)) return text
        val builder = SpannableStringBuilder()
        var index = 0
        while (index < text.length) {
            val open = text.indexOf(BOLD_MARKER, index)
            val close = if (open < 0) -1 else text.indexOf(BOLD_MARKER, open + BOLD_MARKER.length)
            if (open < 0 || close < 0) {
                builder.append(text, index, text.length)
                break
            }
            builder.append(text, index, open)
            val start = builder.length
            builder.append(text, open + BOLD_MARKER.length, close)
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
            )
            index = close + BOLD_MARKER.length
        }
        return builder
    }

    private const val BOLD_MARKER = "**"

    // endregion
}
