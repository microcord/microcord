package com.discord.utilities.integrations

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.discord.api.activity.Activity
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import java.io.IOException
import java.net.URL

/**
 * Hand-off from a Spotify rich presence into the Spotify app.
 *
 * Every entry point degrades the same way: if the intent finds no handler *and* Spotify is not
 * installed, the user is sent to the Play Store listing instead of seeing nothing happen.
 */
object SpotifyHelper {

    private const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"
    private const val SPOTIFY_UTM_PARAMS = "utm_source=discord&utm_medium=mobile"
    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE_NAME&$SPOTIFY_UTM_PARAMS"

    /** Adjust attribution ping fired alongside the Play Store hand-off. */
    private const val ATTRIBUTION_URL = "https://app.adjust.com/ndjczk?campaign="

    private const val TAG = "SPOTIFY"
    private const val METADATA_CHANGED = "com.spotify.music.metadatachanged"
    private const val PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"

    fun isSpotifyInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(SPOTIFY_PACKAGE_NAME, 0)
            true
        } catch (notInstalled: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    /** Receives local playback state only; authorization remains owned by the linked connection. */
    fun registerSpotifyBroadcastReceivers(
        context: Context,
        onTrackChanged: (String) -> Unit,
        onPlaybackChanged: (playing: Boolean, positionMs: Int) -> Unit,
    ) {
        val appContext = context.applicationContext
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.getStringExtra("id")
                        ?.let(::spotifyTrackId)
                        ?.let(onTrackChanged)
                }
            },
            IntentFilter(METADATA_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent ?: return
                    onPlaybackChanged(
                        intent.getBooleanExtra("playing", false),
                        intent.getIntExtra("playbackPosition", 0).coerceAtLeast(0),
                    )
                }
            },
            IntentFilter(PLAYBACK_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /**
     * Opens the track the presence is currently on. No-op when the activity carries no `sync_id`,
     * which is what a non-Spotify listening activity looks like.
     */
    fun launchTrack(context: Context, activity: Activity?) {
        val syncId = activity?.syncId ?: return
        runIfSpotifyInstalled(context) {
            launchSpotifyIntent(context, spotifyIntent("spotify:track:${Uri.encode(syncId)}"))
        }
    }

    /**
     * Opens the album the presence is currently on.
     *
     * The album id is not in the presence — it has to be fetched from the activity-metadata
     * endpoint, keyed by the *streamer's* session. Viewing your own presence just opens Spotify.
     */
    fun launchAlbum(context: Context, activity: Activity?, userId: Long, isMe: Boolean) {
        if (isMe) {
            openSpotifyApp(context)
            return
        }
        val sessionId = activity?.sessionId ?: return
        val applicationId = activity.applicationId ?: return
        runIfSpotifyInstalled(context) {
            RestAPI.api.getActivityMetadata(userId, sessionId, applicationId)
                .restSubscribeOn()
                .ui()
                .appSubscribe(errorTag = "REST: Spotify GetActivityMetadata", context = context) { metadata ->
                    val albumId = metadata.albumId ?: return@appSubscribe
                    launchSpotifyIntent(
                        context,
                        spotifyIntent("spotify:album:${Uri.encode(albumId)}"),
                    )
                }
        }
    }

    fun openSpotifyApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE_NAME)
        if (launchIntent != null) context.startActivity(launchIntent)
    }

    /**
     * Sends the user to Spotify's Play Store listing, pinging Discord's install-attribution
     * endpoint first. The ping is best-effort and never blocks the navigation.
     */
    fun openPlayStoreForSpotify(context: Context) {
        val packageName = context.packageName
        Thread {
            try {
                URL(ATTRIBUTION_URL + packageName).openStream().close()
            } catch (failure: IOException) {
                Log.w(TAG, "Couldn't open tracking url", failure)
            }
        }.start()
        UriHandler.handle(context, PLAY_STORE_URL)
    }

    private fun spotifyIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$uri?$SPOTIFY_UTM_PARAMS"))

    private fun launchSpotifyIntent(context: Context, intent: Intent) {
        try {
            intent.putExtra(
                Intent.EXTRA_REFERRER,
                Uri.parse("android-app://${context.packageName}"),
            )
            context.startActivity(intent)
        } catch (notFound: ActivityNotFoundException) {
            if (!isSpotifyInstalled(context)) openPlayStoreForSpotify(context)
        }
    }

    private inline fun runIfSpotifyInstalled(context: Context, action: () -> Unit) {
        try {
            action()
        } catch (notFound: ActivityNotFoundException) {
            if (!isSpotifyInstalled(context)) openPlayStoreForSpotify(context)
        }
    }

    internal fun spotifyTrackId(value: String): String? = value
        .takeIf { it.startsWith("spotify:track:") }
        ?.substringAfterLast(':')
        ?.takeIf { it.length == 22 && it.all(Char::isLetterOrDigit) }

}
