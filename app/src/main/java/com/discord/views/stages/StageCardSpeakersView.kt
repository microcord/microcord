package com.discord.views.stages

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.dimen.dpToPixels
import com.discord.views.user.avatarUrl
import com.discord.views.user.setAvatar
import com.discord.views.VitoImageView
import kotlin.math.min

class StageCardSpeakersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : android.widget.LinearLayout(context, attrs) {
    data class Speaker(val user: User, val guildMember: GuildMember?, val displayName: String)

    private val adapter = SpeakersAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_stage_card_speakers, this)
        val recycler = requireNotNull<SpeakersRecyclerView>(findViewById(R.id.discovery_speakers_recycler))
        recycler.adapter = adapter
        recycler.layoutManager = GridLayoutManager(recycler.context, 2)
        recycler.addItemDecoration(SpeakerSpacingDecoration(dpToPixels(4), dpToPixels(8)))
        recycler.setHasFixedSize(false)
    }

    fun setSpeakers(speakers: List<Speaker>) {
        val (shown, remaining) = stageCardCounts(speakers.size)
        adapter.items = buildList {
            addAll(speakers.take(shown).map(::SpeakerItem))
            if (remaining > 0) add(RemainingItem(remaining))
        }
    }


    private inner class SpeakersAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is SpeakerItem) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) SpeakerHolder(parent) else RemainingHolder(parent)

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SpeakerHolder -> holder.bind((items[position] as SpeakerItem).speaker)
                is RemainingHolder -> holder.bind((items[position] as RemainingItem).count)
            }
        }
    }

    private class SpeakerHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.widget_discovery_stage_card_speaker, parent, false),
    ) {
        private val avatar = requireNotNull<VitoImageView>(itemView.findViewById(R.id.discovery_stage_card_speaker_avatar))
        private val name = requireNotNull<TextView>(itemView.findViewById(R.id.discovery_stage_card_speaker_name))

        fun bind(speaker: Speaker) {
            val size = itemView.resources.getDimensionPixelSize(R.dimen.avatar_size_medium)
            setAvatar(avatar, avatarUrl(speaker.user, speaker.guildMember, size), true, size)
            name.text = speaker.displayName
        }
    }

    private class RemainingHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.widget_discovery_other_speakers_count, parent, false),
    ) {
        private val count = requireNotNull<TextView>(itemView.findViewById(R.id.other_speakers_count_text))

        fun bind(value: Int) {
            val plural = itemView.resources.getQuantityString(R.plurals.discovery_speaker_count_speakersCount, value)
                .replace("{count}", value.toString())
            count.text = itemView.context.getString(R.string.discovery_speaker_count)
                .replace("{speakersCount}", plural)
        }
    }

    private class SpeakerSpacingDecoration(private val horizontal: Int, private val vertical: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            super.getItemOffsets(outRect, view, parent, state)
            outRect.left = horizontal
            outRect.right = horizontal
            outRect.bottom = if (parent.getChildAdapterPosition(view) >= state.itemCount - 2) 0 else vertical
        }
    }

    private sealed interface Item
    private data class SpeakerItem(val speaker: Speaker) : Item
    private data class RemainingItem(val count: Int) : Item
}

internal fun stageCardCounts(total: Int) = min(total, 3) to (total - 3).coerceAtLeast(0)
