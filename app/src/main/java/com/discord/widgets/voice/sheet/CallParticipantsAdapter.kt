package com.discord.widgets.voice.sheet

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.i18n.i18nFormat
import com.discord.models.commands.Application
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.streams.StreamContext
import com.discord.views.PileView
import com.discord.views.StreamPreviewView
import com.discord.views.VoiceUserView
import com.discord.widgets.guildscheduledevent.GuildScheduledEventItemView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

/**
 * The participant list shown by [WidgetVoiceBottomSheet] and by the fullscreen call preview.
 *
 * Rows are heterogeneous: connected and disconnected users, section headers, a spectators header,
 * a divider, an "invite friends" affordance, a scheduled-event card, and an embedded-activity card.
 *
 * @param isCallPreview when `true` the rows keep their layout-declared background instead of being
 *   repainted with `?attr/colorBackgroundPrimary`, because the preview draws them over the camera
 *   feed.
 * @param quantizeUserAvatars reserved for the reference's representative-avatar-colour extraction.
 *   See the note on [ViewHolderUser.onConfigure] — the colour store it feeds does not exist yet.
 */
class CallParticipantsAdapter @JvmOverloads constructor(
    recycler: RecyclerView,
    private val isCallPreview: Boolean = false,
    private val quantizeUserAvatars: Boolean = false,
) : MGRecyclerAdapterSimple<CallParticipantsAdapter.ListItem>(recycler) {

    var onVoiceUserClicked: (StoreVoiceParticipants.VoiceUser) -> Unit = {}
    var onStreamPreviewClicked: (StreamContext) -> Unit = {}
    var onToggleRingingClicked: (StoreVoiceParticipants.VoiceUser) -> Unit = {}
    var onInviteFriendsClicked: (View) -> Unit = {}
    var onEventClicked: (GuildScheduledEvent) -> Unit = {}
    var onStartEventClicked: (GuildScheduledEvent) -> Unit = {}
    var onEmbeddedActivityClicked: (EmbeddedActivity) -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<CallParticipantsAdapter, ListItem> = when (viewType) {
        VIEW_TYPE_VOICE_USER -> ViewHolderUser(this, isCallPreview, quantizeUserAvatars)
        VIEW_TYPE_HEADER -> ViewHolderHeader(this)
        VIEW_TYPE_SPECTATORS_HEADER -> ViewHolderHeader(this)
        VIEW_TYPE_DIVIDER -> ViewHolderDivider(this)
        VIEW_TYPE_INVITE -> ViewHolderInvite(this, isCallPreview)
        VIEW_TYPE_EVENT -> ViewHolderEvent(this, isCallPreview)
        VIEW_TYPE_EMBEDDED_ACTIVITY -> ViewHolderEmbeddedActivity(this, isCallPreview)
        else -> throw invalidViewTypeException(viewType)
    }

    sealed class ListItem : MGRecyclerDataPayload {

        data class VoiceUser(
            val participant: StoreVoiceParticipants.VoiceUser,
            val canRing: Boolean,
            val isSpectatingSameStream: Boolean,
        ) : ListItem() {
            override val type: Int = VIEW_TYPE_VOICE_USER
            override val key: String = participant.user.id.toString()
        }

        /** A section title such as "In this channel" / "Not in this channel". */
        data class Header(val stringResId: Int) : ListItem() {
            override val type: Int = VIEW_TYPE_HEADER
            override val key: String = stringResId.toString()
        }

        /** "Watching {username}" — only one can be on screen, hence the constant key. */
        data class SpectatorsHeader(val streamerName: String) : ListItem() {
            override val type: Int = VIEW_TYPE_SPECTATORS_HEADER
            override val key: String = "SpectatorsHeader"
        }

        object Divider : ListItem() {
            override val type: Int = VIEW_TYPE_DIVIDER
            override val key: String = VIEW_TYPE_DIVIDER.toString()
        }

        object Invite : ListItem() {
            override val type: Int = VIEW_TYPE_INVITE
            override val key: String = VIEW_TYPE_INVITE.toString()
        }

        data class Event(
            val event: GuildScheduledEvent,
            val canStartEvent: Boolean,
            val isCurrentUserInCall: Boolean,
        ) : ListItem() {
            override val type: Int = VIEW_TYPE_EVENT
            override val key: String = VIEW_TYPE_EVENT.toString()
        }

        data class EmbeddedActivityItem(
            val embeddedActivity: EmbeddedActivity,
            val participants: List<UserGuildMember>,
            val application: Application,
        ) : ListItem() {
            override val type: Int = VIEW_TYPE_EMBEDDED_ACTIVITY
            override val key: String = embeddedActivity.applicationId.toString()
        }
    }

    class ViewHolderDivider(adapter: CallParticipantsAdapter) :
        MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
            R.layout.voice_user_list_item_divider,
            adapter,
        )

    class ViewHolderHeader(adapter: CallParticipantsAdapter) :
        MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
            R.layout.voice_user_list_item_header,
            adapter,
        ) {
        private val label: TextView =
            itemView.findViewById(R.id.voice_user_list_item_header_label)

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            when (data) {
                is ListItem.Header -> label.setText(data.stringResId)
                is ListItem.SpectatorsHeader -> label.text = label.i18nFormat(
                    R.string.go_live_watching_user,
                    "username" to data.streamerName,
                )
                else -> Unit
            }
        }
    }

    class ViewHolderInvite(
        adapter: CallParticipantsAdapter,
        private val isCallPreview: Boolean,
    ) : MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
        R.layout.voice_user_list_item_invite,
        adapter,
    ) {
        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            if (data !is ListItem.Invite) return
            itemView.setOnClickListener { adapter.onInviteFriendsClicked(it) }
            if (!isCallPreview) itemView.applySheetBackground()
        }
    }

    class ViewHolderEvent(
        adapter: CallParticipantsAdapter,
        private val isCallPreview: Boolean,
    ) : MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
        R.layout.voice_user_list_item_event,
        adapter,
    ) {
        private val eventCard: GuildScheduledEventItemView = itemView.findViewById(R.id.event_card)

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            if (data !is ListItem.Event) return
            eventCard.configureInVoiceChannel(
                guildScheduledEvent = data.event,
                creator = null,
                canStartEvent = data.canStartEvent && data.isCurrentUserInCall,
                isCollapsed = data.isCurrentUserInCall,
                canConnect = data.isCurrentUserInCall,
                isConnected = isCallPreview,
                onCardClicked = { adapter.onEventClicked(data.event) },
                onStartEventButtonClicked = { adapter.onStartEventClicked(data.event) },
            )
        }
    }

    class ViewHolderEmbeddedActivity(
        adapter: CallParticipantsAdapter,
        private val isCallPreview: Boolean,
    ) : MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
        R.layout.voice_user_list_item_embedded_activity,
        adapter,
    ) {
        private val appIcon: SimpleDraweeView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val clickableContainer: ConstraintLayout =
            itemView.findViewById(R.id.clickable_container)
        private val participantAvatars: PileView = itemView.findViewById(R.id.participant_avatars)
        private val distinctChangeDetector = MGImages.DistinctChangeDetector()

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            if (data !is ListItem.EmbeddedActivityItem) return
            val application = data.application

            if (!isCallPreview) itemView.applySheetBackground()
            appName.text = application.name

            // With no CDN icon the drawee keeps the generic activity glyph as its background and
            // loads nothing; with one, the background is cleared so the icon is not drawn over it.
            val icon = application.icon
            appIcon.background = if (icon != null) {
                null
            } else {
                ContextCompat.getDrawable(appIcon.context, R.drawable.ic_activity_24dp)
            }
            val iconSizePx = dpToPixels(APP_ICON_SIZE_DP)
            MGImages.setImage(
                view = appIcon,
                url = icon?.let { IconUtils.getApplicationIcon(application.id, it, iconSizePx) },
                width = iconSizePx,
                height = iconSizePx,
                changeDetector = distinctChangeDetector,
            )

            val avatarSizePx = dpToPixels(PARTICIPANT_AVATAR_SIZE_DP)
            participantAvatars.setItems(
                data.participants.map { member ->
                    val url = IconUtils.getForGuildMemberOrUser(
                        member.user,
                        member.guildMember,
                        avatarSizePx,
                        allowAnimation = false,
                    )
                    PileView.Item({ url }, null)
                },
            )
            clickableContainer.setOnClickListener { adapter.onEmbeddedActivityClicked(data.embeddedActivity) }
        }

        private companion object {
            private const val APP_ICON_SIZE_DP = 32
            private const val PARTICIPANT_AVATAR_SIZE_DP = 16
        }
    }

    class ViewHolderUser(
        adapter: CallParticipantsAdapter,
        private val isCallPreview: Boolean,
        @Suppress("unused") private val quantizeUserAvatar: Boolean,
    ) : MGRecyclerViewHolder<CallParticipantsAdapter, ListItem>(
        R.layout.voice_user_list_item_user,
        adapter,
    ) {
        private val avatar: VoiceUserView =
            itemView.findViewById(R.id.voice_user_list_item_user_avatar)
        private val name: TextView = itemView.findViewById(R.id.voice_user_list_item_user_name)
        private val liveIndicator: TextView =
            itemView.findViewById(R.id.voice_user_list_item_live_indicator)
        private val spectatingIndicator: ImageView =
            itemView.findViewById(R.id.voice_user_list_item_spectating_indicator)
        private val muteIndicator: ImageView =
            itemView.findViewById(R.id.voice_user_list_item_mute_indicator)
        private val deafenIndicator: ImageView =
            itemView.findViewById(R.id.voice_user_list_item_deafen_indicator)
        private val videoIndicator: ImageView =
            itemView.findViewById(R.id.voice_user_list_item_video_indicator)
        private val toggleRinging: MaterialButton =
            itemView.findViewById(R.id.voice_user_list_item_toggle_ringing)
        private val streamPreview: StreamPreviewView =
            itemView.findViewById(R.id.voice_user_list_item_user_stream_preview)

        /**
         * The reference additionally registers a bitmap listener here that feeds the loaded avatar
         * into `UserRepresentativeColors`, which the video grid reads back to tint a participant
         * tile. That colour store has not been reconstructed, so [quantizeUserAvatar] is accepted
         * and carried but currently drives nothing. Wire it here when the store lands.
         */
        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            if (data !is ListItem.VoiceUser) return
            val voiceUser = data.participant

            if (!isCallPreview) itemView.applySheetBackground()

            avatar.setVoiceUser(voiceUser, R.dimen.avatar_size_standard)
            name.text = voiceUser.displayName
            itemView.setOnClickListener { adapter.onVoiceUserClicked(voiceUser) }

            configureRinging(voiceUser, data.canRing)
            configureIndicators(voiceUser, data)
        }

        private fun configureRinging(
            voiceUser: StoreVoiceParticipants.VoiceUser,
            canRing: Boolean,
        ) {
            toggleRinging.visibility = if (canRing) View.VISIBLE else View.GONE
            toggleRinging.setText(
                if (voiceUser.isRinging) R.string.stop_ringing else R.string.ring,
            )
            toggleRinging.contentDescription = toggleRinging.i18nFormat(
                if (voiceUser.isRinging) {
                    R.string.stop_ringing_username_a11y_label
                } else {
                    R.string.ring_username_a11y_label
                },
                "username" to voiceUser.displayName,
            )
            toggleRinging.setOnClickListener { adapter.onToggleRingingClicked(voiceUser) }
        }

        private fun configureIndicators(
            voiceUser: StoreVoiceParticipants.VoiceUser,
            data: ListItem.VoiceUser,
        ) {
            val voiceState = voiceUser.voiceState
            if (!voiceUser.isConnected || voiceState == null) {
                // Somebody in the channel list who is not actually connected: no live state at all.
                deafenIndicator.visibility = View.GONE
                muteIndicator.visibility = View.GONE
                streamPreview.visibility = View.GONE
                videoIndicator.visibility = View.GONE
                spectatingIndicator.visibility = View.GONE
                liveIndicator.visibility = View.GONE
                return
            }

            val deafened = voiceState.selfDeaf || voiceState.deaf
            // Suppressed counts as muted here: a stage audience member cannot speak either.
            val muted = voiceState.selfMute || voiceState.mute || voiceState.suppress

            spectatingIndicator.visibility =
                if (data.isSpectatingSameStream) View.VISIBLE else View.GONE
            deafenIndicator.visibility = if (deafened) View.VISIBLE else View.GONE
            muteIndicator.visibility = if (muted) View.VISIBLE else View.GONE
            videoIndicator.visibility = if (voiceState.selfVideo) View.VISIBLE else View.GONE

            val streamContext = voiceUser.streamContext
            liveIndicator.visibility = if (streamContext != null) View.VISIBLE else View.GONE

            val preview = streamContext?.preview
            if (preview == null || streamContext.joinability != StreamContext.Joinability.CAN_CONNECT) {
                streamPreview.visibility = View.GONE
                return
            }
            streamPreview.visibility = View.VISIBLE
            streamPreview.setOnClickListener { adapter.onStreamPreviewClicked(streamContext) }
            streamPreview.configure(
                preview,
                StreamContext.Joinability.CAN_CONNECT,
                streamContext.isCurrentUserParticipating,
            )
        }
    }

    companion object {
        private const val VIEW_TYPE_VOICE_USER = 0
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_SPECTATORS_HEADER = 2
        private const val VIEW_TYPE_DIVIDER = 3
        private const val VIEW_TYPE_INVITE = 4
        private const val VIEW_TYPE_EVENT = 5
        private const val VIEW_TYPE_EMBEDDED_ACTIVITY = 6

        /**
         * Rows are declared with an opaque background so they can be reused by the call preview,
         * which draws them over video. Inside the sheet they must take the sheet's own surface.
         */
        private fun View.applySheetBackground() {
            setBackgroundColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundPrimary))
        }
    }
}
