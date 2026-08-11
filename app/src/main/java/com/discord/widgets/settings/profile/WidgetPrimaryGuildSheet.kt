package com.discord.widgets.settings.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.user.PrimaryGuild
import com.discord.app.AppBottomSheet
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.views.GuildTagView
import com.facebook.drawee.view.SimpleDraweeView

/**
 * Picks which server's tag is worn next to the name, or none.
 *
 * The list is the guilds that can lend a tag, alphabetically, behind a "None" row that is always
 * first. Tapping the guild already chosen clears the tag rather than reselecting it, matching the
 * reference. The choice is reported straight away and the sheet closes; nothing is written here,
 * because the editor holds it as a pending change until the user saves.
 */
class WidgetPrimaryGuildSheet : AppBottomSheet() {

    private var guilds: List<Guild> = emptyList()

    override fun getContentViewResId(): Int = R.layout.widget_primary_guild_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val selectedGuildId = argumentsOrDefault.getLong(ARG_SELECTED_GUILD_ID, NO_GUILD)
            .takeIf { it != NO_GUILD }
        val meId = StoreStream.getUsers().getMe().id
        guilds = eligibleGuilds(StoreStream.getGuilds().getGuilds().values) { guildId ->
            StoreStream.getGuilds().getMember(guildId, meId)?.pending == true
        }
        view.findViewById<MaxHeightRecyclerView>(R.id.primary_guild_sheet_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter(guilds, selectedGuildId, ::report)
        }
    }

    private fun report(guildId: Long?) {
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_GUILD_ID to (guildId ?: NO_GUILD)))
        dismiss()
    }

    private class Adapter(
        private val guilds: List<Guild>,
        private val selectedGuildId: Long?,
        private val onPicked: (Long?) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: SimpleDraweeView = view.findViewById(R.id.primary_guild_item_icon)
            val name: TextView = view.findViewById(R.id.primary_guild_item_name)
            val tag: GuildTagView = view.findViewById(R.id.primary_guild_item_tag)
            val radio: RadioButton = view.findViewById(R.id.primary_guild_item_radio)
        }

        override fun getItemCount() = guilds.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_primary_guild_sheet_item, parent, false),
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Position 0 is the None row, so the guild list is offset by one throughout.
            val guild = guilds.getOrNull(position - 1)
            holder.icon.isVisible = guild != null
            if (guild != null) {
                holder.icon.setImageURI(IconUtils.getForGuild(guild.id, guild.icon))
            }
            holder.name.text = guild?.name ?: holder.name.context.getString(R.string.none)
            holder.tag.bind(guild?.asWornTag())
            holder.radio.isChecked = guild?.id == selectedGuildId
            holder.itemView.setOnClickListener {
                // Tapping the current choice takes the tag off rather than reselecting it.
                onPicked(guild?.id?.takeIf { it != selectedGuildId })
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "PRIMARY_GUILD"
        const val RESULT_GUILD_ID = "guild_id"

        /** Reported instead of an id when the user picked None. */
        const val NO_GUILD = -1L

        private const val ARG_SELECTED_GUILD_ID = "selected_guild_id"
        private const val TAG = "WidgetPrimaryGuildSheet"

        /**
         * A guild can lend its tag once it has one and the account is a settled member.
         *
         * [isPendingMember] covers membership screening: until the rules are accepted the account
         * is only provisionally in the guild and has nothing to wear yet. An unknown member is
         * treated as settled, since [Guild.joinedAt] already proves membership and the member list
         * is loaded lazily.
         */
        fun eligibleGuilds(
            guilds: Collection<Guild>,
            isPendingMember: (Long) -> Boolean = { false },
        ): List<Guild> = guilds
            .filter {
                it.hasFeature(GuildFeature.GUILD_TAGS) &&
                    !it.profile?.tag.isNullOrBlank() &&
                    it.joinedAt != null &&
                    !isPendingMember(it.id)
            }
            .sortedBy { it.name.lowercase() }

        fun show(fragmentManager: FragmentManager, selectedGuildId: Long?) {
            WidgetPrimaryGuildSheet()
                .apply {
                    arguments = bundleOf(ARG_SELECTED_GUILD_ID to (selectedGuildId ?: NO_GUILD))
                }
                .show(fragmentManager, TAG)
        }

        fun bindResult(fragment: Fragment, onPicked: (Long?) -> Unit) {
            fragment.parentFragmentManager.setFragmentResultListener(
                REQUEST_KEY,
                fragment.viewLifecycleOwner,
            ) { _, bundle ->
                onPicked(bundle.getLong(RESULT_GUILD_ID).takeIf { it != NO_GUILD })
            }
        }
    }
}

/** The tag this guild would lend, shaped as the worn form [GuildTagView] renders. */
internal fun Guild.asWornTag() = PrimaryGuild(
    identityEnabled = true,
    identityGuildId = id,
    tag = profile?.tag,
    badge = profile?.badge,
)
