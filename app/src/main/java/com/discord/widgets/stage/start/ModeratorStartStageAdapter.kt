package com.discord.widgets.stage.start

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.user.UserSummaryView
import com.discord.widgets.guildscheduledevent.GuildEventPromptView
import com.discord.widgets.guildscheduledevent.ScheduledEventUi

/**
 * The moderator's start-stage menu. Every row is clickable and reports itself back through
 * [onItemClicked]; the fragment decides what each one means.
 */
class ModeratorStartStageAdapter(
    recyclerView: RecyclerView,
    private val onItemClicked: (ModeratorStartStageItem) -> Unit,
) : MGRecyclerAdapterSimple<ModeratorStartStageItem>(recyclerView, deferredDiffs = false) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ModeratorStartStageItem> = when (viewType) {
        ModeratorStartStageItem.TYPE_HEADER ->
            StartStageViewHolder(R.layout.moderator_start_stage_header_item, this)
        ModeratorStartStageItem.TYPE_LIST_ITEM -> ListItemViewHolder(this)
        ModeratorStartStageItem.TYPE_CONTINUE ->
            StartStageViewHolder(R.layout.moderator_start_stage_continue_item, this)
        ModeratorStartStageItem.TYPE_WAITING -> WaitingViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * Every row in this list is a whole tap target, so the click listener is installed once here
     * rather than repeated in each subclass.
     */
    private open class StartStageViewHolder(
        layout: Int,
        private val startStageAdapter: ModeratorStartStageAdapter,
    ) : MGRecyclerViewHolder<ModeratorStartStageAdapter, ModeratorStartStageItem>(
        layout,
        startStageAdapter,
    ) {
        override fun onConfigure(position: Int, data: ModeratorStartStageItem) {
            super.onConfigure(position, data)
            itemView.setOnClickListener { startStageAdapter.onItemClicked(data) }
        }
    }

    private class ListItemViewHolder(
        adapter: ModeratorStartStageAdapter,
    ) : StartStageViewHolder(R.layout.moderator_start_stage_list_item, adapter) {

        private val prompt = itemView as GuildEventPromptView

        override fun onConfigure(position: Int, data: ModeratorStartStageItem) {
            super.onConfigure(position, data)
            val item = data as? ModeratorStartStageItem.ListItem ?: return
            val (title: CharSequence, subtitle: CharSequence) = when (item) {
                is ModeratorStartStageItem.ListItem.StaticOption ->
                    Pair<CharSequence, CharSequence>(
                        itemView.context.getString(item.titleRes),
                        itemView.context.getString(item.subtitleRes),
                    )
                is ModeratorStartStageItem.ListItem.Event ->
                    Pair<CharSequence, CharSequence>(
                        item.event.name,
                        ScheduledEventUi.startingText(itemView.context, item.event),
                    )
            }
            prompt.configure(item.iconRes, item.iconBgColorRes, title, subtitle)
        }
    }

    private class WaitingViewHolder(
        adapter: ModeratorStartStageAdapter,
    ) : StartStageViewHolder(R.layout.moderator_start_stage_waiting_item, adapter) {

        private val userList: UserSummaryView =
            itemView.findViewById(R.id.moderator_start_stage_waiting_user_list)
        private val userText: TextView =
            itemView.findViewById(R.id.moderator_start_stage_waiting_user_text)

        override fun onConfigure(position: Int, data: ModeratorStartStageItem) {
            super.onConfigure(position, data)
            val item = data as? ModeratorStartStageItem.Waiting ?: return
            userList.configure(item.users)

            // The producer caps the list at MAX_DISPLAY_WAITING_USERS, so only 1, 2 and 3 occur.
            val names = item.users.map { it.getNickOrUserName() }
            userText.setTextAndVisibilityBy(
                when (names.size) {
                    1 -> userText.i18nFormat(
                        R.string.stage_audience_waiting_one,
                        "first" to names[0],
                    )
                    2 -> userText.i18nFormat(
                        R.string.stage_audience_waiting_two,
                        "first" to names[0],
                        "second" to names[1],
                    )
                    3 -> userText.i18nFormat(
                        R.string.stage_audience_waiting_many,
                        "first" to names[0],
                        "second" to names[1],
                        "numOthers" to userText.i18nPlural(
                            R.plurals.stage_audience_waiting_many_numOthers,
                            names.size - 2,
                            "#" to (names.size - 2),
                        ),
                    )
                    // DELIBERATE DIVERGENCE. The reference's three-user branch passes the two names
                    // as *positional* format arguments straight into the `..._numOthers` plural,
                    // whose only placeholder is `{#}` — so the first name lands in the count slot
                    // and `stage_audience_waiting_many`, the string that actually reads correctly,
                    // is never used. We render the intended sentence instead.
                    else -> null
                },
            )
        }
    }
}
