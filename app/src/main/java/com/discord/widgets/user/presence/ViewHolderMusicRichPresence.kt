package com.discord.widgets.user.presence

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityPlatform
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.utilities.integrations.SpotifyHelper
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.isRichPresence
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.VitoImageView
import com.discord.i18n.i18nRender

/**
 * A listening activity, laid out like a now-playing bar: track, artists, album, and a scrub bar
 * showing how far through the track the user is.
 *
 * Only Spotify gets the scrub bar — it is the one source that reports both endpoints of the track —
 * so for any other music app the progress row is hidden and only the text remains. Tapping the card
 * hands off to the Spotify app; without it installed the button goes to the Play Store instead.
 */
class ViewHolderMusicRichPresence(
    root: View,
    richPresenceImageLarge: VitoImageView,
    richPresenceImageSmall: VitoImageView,
    richPresenceHeader: TextView,
    richPresenceTitle: TextView,
    richPresenceDetails: TextView,
    richPresenceTime: TextView,
    richPresenceTextContainer: View,
    richPresencePrimaryButton: Button,
    private val richPresenceSeekbar: SeekBar,
    private val richPresenceMusicDuration: TextView,
    private val richPresenceMusicElapsed: TextView,
) : ViewHolderUserRichPresence(
    root = root,
    richPresenceImageLarge = richPresenceImageLarge,
    richPresenceImageSmall = richPresenceImageSmall,
    richPresenceHeader = richPresenceHeader,
    richPresenceTitle = richPresenceTitle,
    richPresenceDetails = richPresenceDetails,
    richPresenceTime = richPresenceTime,
    richPresenceState = null,
    richPresenceTextContainer = richPresenceTextContainer,
    richPresencePrimaryButton = richPresencePrimaryButton,
    richPresenceSecondaryButton = null,
    richPresenceType = MUSIC_RICH_PRESENCE_TYPE,
) {

    private val clock: Clock = ClockFactory.get()

    /** The album art is the only artwork this card shows; the small badge slot stays empty. */
    override fun configureAssetUi(model: Activity?, streamContext: StreamContext?) {
        super.configureAssetUi(model, streamContext)
        richPresenceImageSmall?.visibility = View.GONE
    }

    @MainThread
    override fun configureUi(
        fragmentManager: FragmentManager,
        streamContext: StreamContext?,
        isMe: Boolean,
        user: User?,
        applicationContext: Context,
        richPresence: ModelRichPresence?,
        userInSameVoiceChannel: Boolean,
    ) {
        disposeTimer()
        val context = root.context
        val activity = richPresence?.primaryActivity
        if (activity == null || !activity.isRichPresence()) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE

        val isSpotify = activity.name.equals(ActivityPlatform.SPOTIFY.name, ignoreCase = true)
        // Spotify joins multiple artists with ';'; the copy reads as a list.
        val artists = activity.state?.replace(';', ',')

        richPresenceHeader.text = PresenceUtils.getActivityHeader(richPresenceHeader.context, activity)
        richPresenceTitle.setTextAndVisibilityBy(activity.details)
        // A missing value leaves its placeholder in the template rather than blanking the line, so
        // an activity without artist or album has to skip the line entirely.
        richPresenceDetails?.setTextAndVisibilityBy(
            artists?.takeIf(String::isNotBlank)?.let {
                context.i18nRender(R.string.user_activity_listening_artists, "artists" to it)
            },
        )
        richPresenceTime.setTextAndVisibilityBy(
            activity.assets?.largeText?.takeIf(String::isNotBlank)?.let {
                context.i18nRender(R.string.user_activity_listening_album, "album" to it)
            },
        )
        richPresenceTextContainer.isSelected = true
        configureAssetUi(activity, streamContext)

        richPresencePrimaryButton?.visibility = View.VISIBLE
        val progressVisibility = if (isSpotify) View.VISIBLE else View.GONE
        richPresenceSeekbar.visibility = progressVisibility
        richPresenceMusicDuration.visibility = progressVisibility
        richPresenceMusicElapsed.visibility = progressVisibility

        if (isMe) {
            // You cannot listen along with yourself.
            richPresencePrimaryButton?.setText(R.string.user_activity_cannot_play_self)
            richPresencePrimaryButton?.isEnabled = false
        } else {
            richPresencePrimaryButton?.text =
                context.i18nFormat(R.string.user_activity_play_on_platform, "platform" to activity.name)
            richPresencePrimaryButton?.isEnabled = true
        }

        if (SpotifyHelper.isSpotifyInstalled(context)) {
            richPresenceTitle.setOnClickListener { SpotifyHelper.launchTrack(it.context, activity) }
            richPresencePrimaryButton?.setOnClickListener {
                SpotifyHelper.launchTrack(it.context, activity)
            }
            richPresenceImageLarge.setOnClickListener {
                SpotifyHelper.launchAlbum(it.context, activity, user?.id ?: 0L, isMe)
            }
        } else {
            richPresencePrimaryButton?.setOnClickListener {
                SpotifyHelper.openPlayStoreForSpotify(it.context)
            }
        }
    }

    /**
     * Drives the scrub bar and its two clocks. Once the track's declared end has passed the bar
     * pins at full rather than overshooting — a paused client keeps broadcasting stale timestamps.
     */
    override fun setTimeTextViews(activity: Activity?) {
        val timestamps = activity?.timestamps ?: return
        val trackLengthMs = timestamps.endMs - timestamps.startMs
        val now = clock.currentTimeMillis()
        val elapsedMs = if (now >= timestamps.endMs) trackLengthMs else now - timestamps.startMs

        richPresenceSeekbar.progress = (elapsedMs.toDouble() / trackLengthMs.toDouble() * 100.0).toInt()
        richPresenceMusicElapsed.text = TimeUtils.toFriendlyStringSimple(elapsedMs)
        richPresenceMusicDuration.text = TimeUtils.toFriendlyStringSimple(trackLengthMs)
    }
}
