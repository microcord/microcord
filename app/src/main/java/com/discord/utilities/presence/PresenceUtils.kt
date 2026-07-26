package com.discord.utilities.presence

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
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
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

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

    /** EN SPACE; wider than a normal space so the emoji does not crowd the text. */
    private const val EMOJI_TEXT_SEPARATOR = ' '

    private const val CUSTOM_STATUS_EMOJI_SIZE_DP = 16

    /** `DraweeSpanStringBuilder.DraweeSpan.ALIGN_CENTER`. */
    private const val DRAWEE_SPAN_ALIGN_CENTER = 2

    // region activity lookup

    /** The first activity of [type], or `null`. */
    fun List<Activity>.getActivityByType(type: ActivityType): Activity? =
        firstOrNull { it.type == type }

    /** The first activity that is not the custom status, i.e. the one worth describing. */
    val List<Activity>.primaryActivity: Activity?
        get() = firstOrNull { it.type != ActivityType.CUSTOM_STATUS }

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
     * Renders the presence into [textView], hiding the view entirely when there is
     * nothing to show so it does not reserve blank space in a list row.
     */
    @JvmStatic
    @JvmOverloads
    fun setPresenceText(
        presence: Presence?,
        isStreamingApplication: Boolean,
        textView: SimpleDraweeSpanTextView,
        showFallbackStatusText: Boolean = false,
        hideEmoji: Boolean = false,
    ) {
        val builder = getStatusDraweeSpanStringBuilder(
            context = textView.context,
            presence = presence,
            isStreamingApplication = isStreamingApplication,
            hideEmoji = hideEmoji,
            showFallbackStatusText = showFallbackStatusText,
        )
        textView.setDraweeSpanStringBuilder(builder)
        textView.visibility = if (builder.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // region internals

    /** One line describing what the user is doing, without any custom status. */
    private fun getActivityString(context: Context, activity: Activity?): CharSequence? =
        when (activity?.type) {
            ActivityType.PLAYING ->
                context.renderString(R.string.playing_game, "game" to activity.name)
            ActivityType.STREAMING -> context.renderString(
                R.string.streaming,
                "name" to (activity.details ?: activity.name),
            )
            ActivityType.LISTENING ->
                context.renderString(R.string.listening_to, "name" to activity.name)
            ActivityType.WATCHING -> context.renderString(
                R.string.watching,
                "name" to (activity.details ?: activity.name),
            )
            ActivityType.COMPETING ->
                context.renderString(R.string.competing, "name" to activity.name)
            else -> null
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
        ActivityPlatform.DESKTOP, ActivityPlatform.ANDROID, ActivityPlatform.IOS,
        ActivityPlatform.UNKNOWN,
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
        val request = ImageRequestBuilder
            .newBuilderWithSource(Uri.parse("https://cdn.discordapp.com/emojis/$id.$extension?size=32"))
            .setResizeOptions(ResizeOptions(sizePx, sizePx))
            .build()
        val controller = Fresco.newDraweeControllerBuilder()
            .setImageRequest(request)
            .setAutoPlayAnimations(animate)
            .build()
        val start = length
        append('#')
        setImageSpan(
            context,
            GenericDraweeHierarchyBuilder(context.resources).build(),
            controller,
            start,
            start,
            sizePx,
            sizePx,
            false,
            DRAWEE_SPAN_ALIGN_CENTER,
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
