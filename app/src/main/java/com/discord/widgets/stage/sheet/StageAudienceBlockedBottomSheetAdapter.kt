package com.discord.widgets.stage.sheet

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.CutoutView
import com.discord.views.VitoImageView

/**
 * Lists the people already in a stage whom the user has blocked, above the "join anyway" prompt.
 *
 * Blocked members are drawn at half opacity, and speakers carry a mic badge cut out of their
 * avatar, so the user can tell at a glance whether a blocked account will actually be audible.
 */
class StageAudienceBlockedBottomSheetAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<StageAudienceBlockedBottomSheetAdapter.ListItem>(
    recycler,
    // The list is short and fully known before the sheet opens, so diffing it on a background
    // scheduler would only delay the first frame. The reference makes the same choice.
    deferredDiffs = false,
) {

    init {
        // Unlike its siblings this adapter is not passed through `MGRecyclerAdapter.configure`;
        // the reference attaches itself here instead, and without this the recycler never gets an
        // adapter and the sheet lists nobody.
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = this
    }

    sealed class ListItem : MGRecyclerDataPayload {

        data class Header(val numBlockedUsers: Int) : ListItem() {
            override val type = TYPE_HEADER
            override val key = "AudienceBlockedHeader"
        }

        data class Participant(
            val participant: StoreVoiceParticipants.VoiceUser,
            val isSpeaker: Boolean,
        ) : ListItem() {
            override val type = TYPE_PARTICIPANT
            override val key = participant.user.id.toString()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ListItem> = when (viewType) {
        TYPE_HEADER -> HeaderViewHolder(this)
        TYPE_PARTICIPANT -> ParticipantViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private class HeaderViewHolder(
        adapter: StageAudienceBlockedBottomSheetAdapter,
    ) : MGRecyclerViewHolder<MGRecyclerAdapterSimple<ListItem>, ListItem>(
        R.layout.stage_audience_blocked_bottom_sheet_header_item,
        adapter,
    ) {
        private val title: TextView = itemView.findViewById(R.id.title)
        private val description: TextView = itemView.findViewById(R.id.description)

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            val header = data as? ListItem.Header ?: return
            title.text = itemView.i18nFormat(
                R.string.stage_blocked_users_title_plural,
                "number" to itemView.i18nPlural(
                    R.plurals.stage_blocked_users_title_plural_number,
                    header.numBlockedUsers,
                ),
            )
            description.text = itemView.i18nFormat(
                R.string.stage_blocked_users_body,
                "number" to itemView.i18nPlural(
                    R.plurals.stage_blocked_users_body_number,
                    header.numBlockedUsers,
                    "#" to header.numBlockedUsers,
                ),
            )
        }
    }

    private class ParticipantViewHolder(
        adapter: StageAudienceBlockedBottomSheetAdapter,
    ) : MGRecyclerViewHolder<MGRecyclerAdapterSimple<ListItem>, ListItem>(
        R.layout.stage_audience_blocked_bottom_sheet_item,
        adapter,
    ) {
        private val avatar: VitoImageView =
            itemView.findViewById(R.id.stage_channel_blocked_member_icon)
        private val avatarContainer: CutoutView =
            itemView.findViewById(R.id.stage_channel_blocked_member_icon_container)
        private val name: TextView = itemView.findViewById(R.id.stage_channel_blocked_text)
        private val micBadge: ImageView =
            itemView.findViewById(R.id.stage_channel_blocked_member_speaker)
        private val role: TextView = itemView.findViewById(R.id.stage_channel_blocked_text_speaker)

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            val item = data as? ListItem.Participant ?: return
            val user = item.participant.user

            // The cutout only makes room for the mic badge; the audience has nothing to cut out of.
            avatarContainer.isCutoutEnabled = item.isSpeaker
            micBadge.setBackgroundResource(R.drawable.drawable_uikit_white_circle)
            micBadge.setImageResource(R.drawable.ic_mic_grey_24dp)
            // INVISIBLE, not GONE: the badge holds its slot so the row height never shifts.
            micBadge.visibility = if (item.isSpeaker) View.VISIBLE else View.INVISIBLE
            micBadge.isActivated = item.isSpeaker

            role.setText(if (item.isSpeaker) R.string.stage_speaker else R.string.stage_audience)
            // The reference renders name + discriminator as a two-style spannable via
            // `UserNameFormatterKt.getSpannableForUserNameWithDiscrim`, which this project has not
            // reconstructed. The nickname-or-username fallback below is the same text without the
            // muted discriminator run.
            name.text = item.participant.displayName

            IconUtils.setIcon(
                imageView = avatar,
                user = user,
                sizeDimenRes = R.dimen.avatar_size_large,
                guildMember = item.participant.guildMember,
            )
            avatar.alpha = BLOCKED_AVATAR_ALPHA
        }

        private companion object {
            const val BLOCKED_AVATAR_ALPHA = 0.5f
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PARTICIPANT = 1
    }
}
