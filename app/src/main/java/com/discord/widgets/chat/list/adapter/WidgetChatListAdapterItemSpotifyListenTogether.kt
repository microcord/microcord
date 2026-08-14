package com.discord.widgets.chat.list.adapter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.i18n.i18nFormat
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.widgets.channels.list.WidgetCollapsedUsersListAdapter
import com.discord.widgets.channels.list.items.CollapsedUser
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SpotifyListenTogetherEntry
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.Subscription
import com.discord.i18n.i18nRender
import com.discord.utilities.view.extensions.setTextAndVisibilityBy

/**
 * A "listen along on Spotify" invite.
 *
 * The invite names a listening party rather than a track, so it only means anything while the author
 * is still in that party: the row watches their presence and collapses to "this listening session
 * has ended" as soon as the party id stops matching, or once the message is older than two hours.
 * Everything below the header — album art, track, artist, participants — is hidden in that state.
 */
class WidgetChatListAdapterItemSpotifyListenTogether(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_spotify_listen_together, adapter) {

    private val container: View = itemView.findViewById(R.id.item_listen_together_container)
    private val header: TextView = itemView.findViewById(R.id.item_listen_together_header)
    private val albumImage: SimpleDraweeView =
        itemView.findViewById(R.id.item_listen_together_album_image)
    private val sessionEnded: TextView = itemView.findViewById(R.id.item_listen_together_session_ended)
    private val track: TextView = itemView.findViewById(R.id.item_listen_together_track)
    private val artist: TextView = itemView.findViewById(R.id.item_listen_together_artist)
    private val participants: RecyclerView = itemView.findViewById(R.id.item_listen_together_recycler)
    private val userAdapter = WidgetCollapsedUsersListAdapter(participants).also {
        participants.adapter = it
    }

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as SpotifyListenTogetherEntry

        observeModel(entry).ui().appSubscribe(
            errorClass = WidgetChatListAdapterItemSpotifyListenTogether::class.java,
            subscriptionHandler = { modelSubscription = it },
            onNext = ::configureUI,
        )
    }

    private fun configureUI(model: Model) {
        val entry = model.item
        val presence = model.presence
        header.text = header.i18nFormat(
            R.string.invite_embed_invite_to_listen,
            "name" to activityName(header.context, entry),
        )

        val dead = isDeadInvite(presence, entry)
        val listeningActivity = with(PresenceUtils) { presence?.spotifyListeningActivity }

        sessionEnded.visibility = if (dead) View.VISIBLE else View.GONE
        track.visibility = if (dead) View.GONE else View.VISIBLE
        artist.visibility = if (dead) View.GONE else View.VISIBLE
        albumImage.visibility = if (dead) View.GONE else View.VISIBLE
        participants.visibility = if (dead) View.GONE else View.VISIBLE
        // Marquee only scrolls on a selected view, and the track title is routinely too long.
        container.isSelected = true

        if (dead) return

        userAdapter.setData(model.users)
        track.setTextAndVisibilityBy(listeningActivity?.details)
        artist.setTextAndVisibilityBy(
            listeningActivity?.state?.takeIf(String::isNotBlank)?.let {
                artist.context.i18nRender(R.string.user_activity_listening_artists, "artists" to it)
            },
        )

        val assets = listeningActivity?.assets
        assets?.largeImage?.let { largeImage ->
            MGImages.setImage(albumImage, IconUtils.getAssetImage(null, largeImage))
        }
        albumImage.contentDescription = assets?.largeText

        track.setOnClickListener { view -> launchSpotifyTrack(view.context, listeningActivity) }
    }

    private fun observeModel(entry: SpotifyListenTogetherEntry): Observable<Model> =
        Observable.combineLatest(
            StoreStream.getPresences().observePresenceForUser(entry.userId),
            StoreStream.getGameParty().observeUsersForPartyId(entry.activity.partyId),
            StoreStream.getUsers().observeMeId(),
        ) { presence, partyUsers, meId ->
            val users = partyUsers.values.associateByTo(LinkedHashMap(), { it.id }) { CollapsedUser(it) }
            if (meId == entry.userId) {
                StoreStream.getUsers().getMe().let { users[it.id] = CollapsedUser(it) }
            }
            val maxSize = with(PresenceUtils) {
                presence?.spotifyListeningActivity?.party?.maxSize
            } ?: 0L
            val collapsedUsers = users.values.toMutableList()
            for (index in partyUsers.size until minOf(MAX_VISIBLE_USERS.toLong(), maxSize).toInt()) {
                collapsedUsers += CollapsedUser.createEmptyUser(
                    if (index == MAX_VISIBLE_USERS - 1) maxSize - MAX_VISIBLE_USERS else 0,
                )
            }
            Model(presence, collapsedUsers, entry, meId == entry.userId)
        }.computationLatest().distinctUntilChanged()

    /**
     * The platform named in the header.
     *
     * A party id issued by Spotify names Spotify; anything else came from a desktop client whose
     * platform the mobile app cannot join, so the header says so.
     */
    private fun activityName(context: Context, entry: SpotifyListenTogetherEntry): String =
        if (entry.activity.partyId.contains(SPOTIFY_PARTY_ID_MARKER, ignoreCase = true)) {
            SPOTIFY_NAME
        } else {
            context.getString(R.string.form_label_desktop_only)
        }

    /**
     * Whether the invite has stopped being joinable: the author left the party it names, or the
     * message has simply aged out.
     */
    private fun isDeadInvite(presence: Presence?, entry: SpotifyListenTogetherEntry): Boolean {
        val partyId = with(PresenceUtils) { presence?.spotifyListeningActivity }?.party?.id
        if (partyId != entry.activity.partyId) return true
        val sentAt = TimeUtils.parseSnowflake(entry.messageId)
        return sentAt + EMBED_LIFETIME_MILLIS < ClockFactory.get().currentTimeMillis()
    }

    /**
     * Opens the track in the Spotify app.
     *
     * The reference routes this through `com.discord.utilities.integrations.SpotifyHelper`, which
     * additionally falls back to the Play Store when Spotify is not installed. That utility has not
     * been reconstructed; the intent below is the same one it fires, and a missing app is swallowed
     * rather than crashing the list.
     */
    private fun launchSpotifyTrack(context: Context, activity: Activity?) {
        val syncId = activity?.syncId ?: return
        val uri = Uri.parse("spotify:track:${Uri.encode(syncId)}?$SPOTIFY_UTM_PARAMS")
        val intent = Intent(Intent.ACTION_VIEW, uri).putExtra(
            Intent.EXTRA_REFERRER,
            Uri.parse("android-app://${context.packageName}"),
        )
        try {
            context.startActivity(intent)
        } catch (notInstalled: ActivityNotFoundException) {
            // Nothing on the device can play Spotify URIs; the row stays put.
        }
    }

    private data class Model(
        val presence: Presence?,
        val users: List<CollapsedUser>,
        val item: SpotifyListenTogetherEntry,
        val isMe: Boolean,
    )

    private companion object {
        /** How long a listen-along invite stays live, matching the reference's two hours. */
        private const val EMBED_LIFETIME_MILLIS = 7_200_000L

        private const val SPOTIFY_PARTY_ID_MARKER = "spotify"
        private const val SPOTIFY_NAME = "Spotify"
        private const val SPOTIFY_UTM_PARAMS = "utm_source=discord&utm_medium=mobile"
        private const val MAX_VISIBLE_USERS = 6
    }
}
