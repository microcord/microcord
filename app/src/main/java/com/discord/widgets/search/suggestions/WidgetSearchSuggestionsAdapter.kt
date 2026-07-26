package com.discord.widgets.search.suggestions

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.mg_recycler.SingleTypePayload
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.strings.ContextSearchStringProvider
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.FilterSuggestion
import com.discord.utilities.search.suggestion.entries.HasSuggestion
import com.discord.utilities.search.suggestion.entries.RecentQuerySuggestion
import com.discord.utilities.search.suggestion.entries.SearchSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.user.UserUtils
import com.discord.views.StatusView
import com.discord.views.UserListItemView

/**
 * The suggestion list under the search box.
 *
 * Every row type is a [SingleTypePayload] wrapping its suggestion, so the adapter carries no state
 * of its own — the six click callbacks are set by the fragment on every render and forward straight
 * back to the store, which is what edits the query.
 */
class WidgetSearchSuggestionsAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler, deferredDiffs = false) {

    var onFilterClicked: (FilterType) -> Unit = {}
    var onUserClicked: (UserSuggestion) -> Unit = {}
    var onChannelClicked: (ChannelSuggestion) -> Unit = {}
    var onHasClicked: (HasAnswerOption) -> Unit = {}
    var onRecentQueryClicked: (List<QueryNode>) -> Unit = {}
    var onClearHistoryClicked: () -> Unit = {}

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MGRecyclerDataPayload> = when (viewType) {
        TYPE_HEADER -> HeaderViewHolder(this)
        TYPE_HISTORY_HEADER -> HistoryHeaderViewHolder(this)
        TYPE_FILTER -> FilterViewHolder(this)
        TYPE_USER -> UserViewHolder(this)
        TYPE_IN_CHANNEL -> InChannelViewHolder(this)
        TYPE_HAS -> HasViewHolder(this)
        TYPE_RECENT_QUERY -> RecentQueryViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_HISTORY_HEADER = 1
        private const val TYPE_FILTER = 2
        private const val TYPE_USER = 3
        private const val TYPE_IN_CHANNEL = 4
        private const val TYPE_HAS = 5
        private const val TYPE_RECENT_QUERY = 6

        /** History gets its own header because it is the only one with a "clear" affordance. */
        fun getHeaderItem(category: SearchSuggestion.Category): MGRecyclerDataPayload =
            SingleTypePayload(
                category,
                category.name,
                if (category == SearchSuggestion.Category.RECENT_QUERY) TYPE_HISTORY_HEADER else TYPE_HEADER,
            )

        fun getFilterItem(data: FilterSuggestion): MGRecyclerDataPayload =
            SingleTypePayload(data, data.category.name, TYPE_FILTER)

        fun getUserItem(data: UserSuggestion): MGRecyclerDataPayload =
            SingleTypePayload(data, "${data.targetType.name}${data.userId}", TYPE_USER)

        fun getInChannelItem(data: ChannelSuggestion): MGRecyclerDataPayload =
            SingleTypePayload(data, data.channelId.toString(), TYPE_IN_CHANNEL)

        fun getHasItem(data: HasSuggestion): MGRecyclerDataPayload =
            SingleTypePayload(data, data.hasAnswerOption.name, TYPE_HAS)

        fun getRecentQueryItem(data: RecentQuerySuggestion): MGRecyclerDataPayload =
            SingleTypePayload(data, data.hashCode().toString(), TYPE_RECENT_QUERY)
    }

    /** Section label; the header for a run of one category. */
    private class HeaderViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestion_item_header,
        adapter,
    ) {
        private val label: TextView = itemView.findViewById(R.id.suggestion_item_header)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val category = (data as SingleTypePayload<*>).data as SearchSuggestion.Category
            label.setText(
                when (category) {
                    SearchSuggestion.Category.FILTER -> R.string.search_group_header_search_options
                    SearchSuggestion.Category.MENTIONS_USER -> R.string.search_group_header_mentions
                    SearchSuggestion.Category.FROM_USER -> R.string.search_group_header_from
                    SearchSuggestion.Category.HAS -> R.string.search_group_header_has
                    SearchSuggestion.Category.BEFORE_DATE -> R.string.search_group_header_dates
                    SearchSuggestion.Category.IN_CHANNEL -> R.string.search_group_header_channels
                    SearchSuggestion.Category.RECENT_QUERY ->
                        throw IllegalArgumentException("recent queries use the history header")
                },
            )
        }
    }

    /** "Recent searches", with the clear-all button. */
    private class HistoryHeaderViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestion_item_header_history,
        adapter,
    ) {
        private val clearIcon: ImageView =
            itemView.findViewById(R.id.suggestion_item_header_history_clear_icon)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            clearIcon.setOnClickListener { adapter.onClearHistoryClicked() }
        }
    }

    /** "**from:** username" — the filter keyword plus an example of what answers it. */
    private class FilterViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestions_item_suggestion,
        adapter,
    ) {
        private val container: View = itemView.findViewById(R.id.suggestion_example_container)
        private val icon: ImageView = itemView.findViewById(R.id.suggestion_example_icon)
        private val filterLabel: TextView = itemView.findViewById(R.id.suggestion_example_filter)
        private val answerLabel: TextView = itemView.findViewById(R.id.suggestion_example_answer)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val filterType = ((data as SingleTypePayload<*>).data as FilterSuggestion).filterType

            container.setOnClickListener { adapter.onFilterClicked(filterType) }
            icon.setImageDrawable(ContextCompat.getDrawable(icon.context, filterIcon(filterType)))
            filterLabel.text = boldWithColon(itemView.context.getString(filterText(filterType)))
            answerLabel.setText(answerText(filterType))
        }

        private fun boldWithColon(filter: String): CharSequence =
            SpannableStringBuilder(filter).apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(":")
            }

        private fun filterText(filterType: FilterType): Int = when (filterType) {
            FilterType.FROM -> R.string.search_filter_from
            FilterType.MENTIONS -> R.string.search_filter_mentions
            FilterType.HAS -> R.string.search_filter_has
            FilterType.IN -> R.string.search_filter_in
        }

        private fun answerText(filterType: FilterType): Int = when (filterType) {
            FilterType.FROM -> R.string.search_answer_from
            FilterType.MENTIONS -> R.string.search_answer_mentions
            FilterType.HAS -> R.string.search_answer_has
            FilterType.IN -> R.string.search_answer_in
        }

        private fun filterIcon(filterType: FilterType): Int = when (filterType) {
            FilterType.FROM -> R.drawable.ic_members_24dp
            FilterType.MENTIONS -> R.drawable.ic_mentions_white_24dp
            FilterType.HAS -> R.drawable.ic_attachment_white_24dp
            FilterType.IN -> R.drawable.ic_text_channel_white_24dp
        }
    }

    /** A member, shown with nickname over `name#0000` when the guild gives them one. */
    private class UserViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestions_item_user,
        adapter,
    ) {
        private val container: UserListItemView = itemView as UserListItemView
        private val avatar: ImageView = itemView.findViewById(R.id.user_list_item_avatar)
        private val name: TextView = itemView.findViewById(R.id.user_list_item_name)
        private val secondaryName: TextView = itemView.findViewById(R.id.user_list_item_name_secondary)
        private val status: StatusView = itemView.findViewById(R.id.user_list_item_status)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val suggestion = (data as SingleTypePayload<*>).data as UserSuggestion
            val user = suggestion.user
            val usernameWithDiscriminator =
                user.username + UserUtils.padDiscriminator(user.discriminator)

            val nick = suggestion.guildMember?.nick
            if (nick.isNullOrEmpty()) {
                name.text = usernameWithDiscriminator
                secondaryName.text = ""
            } else {
                name.text = nick
                secondaryName.text = usernameWithDiscriminator
            }

            IconUtils.setIcon(
                imageView = avatar,
                user = user,
                sizeDimenRes = R.dimen.avatar_size_standard,
                guildMember = suggestion.guildMember,
            )
            // Presence is not loaded for search suggestions, so the dot only reflects bot/system
            // status the user object itself carries.
            status.setPresence(null)
            status.visibility =
                if (UserUtils.isStatusVisible(user, null, false)) View.VISIBLE else View.GONE

            container.setOnClickListener { adapter.onUserClicked(suggestion) }
        }
    }

    /** A channel offered while `in:` is open. */
    private class InChannelViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestions_item_channel,
        adapter,
    ) {
        private val container: View =
            itemView.findViewById(R.id.search_suggestion_item_channel_container)
        private val label: TextView =
            itemView.findViewById(R.id.search_suggestions_item_channel_text)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val suggestion = (data as SingleTypePayload<*>).data as ChannelSuggestion
            label.text = suggestion.channelName
            container.setOnClickListener { adapter.onChannelClicked(suggestion) }
        }
    }

    /** A media kind offered while `has:` is open. */
    private class HasViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestions_item_has,
        adapter,
    ) {
        private val container: View =
            itemView.findViewById(R.id.search_suggestion_item_has_container)
        private val icon: ImageView = itemView.findViewById(R.id.search_suggestions_item_has_icon)
        private val label: TextView = itemView.findViewById(R.id.search_suggestions_item_has_text)
        private val searchStringProvider = ContextSearchStringProvider(itemView.context)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val option = ((data as SingleTypePayload<*>).data as HasSuggestion).hasAnswerOption
            label.text = option.getLocalizedInputText(searchStringProvider)
            icon.setImageResource(iconRes(option))
            container.setOnClickListener { adapter.onHasClicked(option) }
        }

        private fun iconRes(option: HasAnswerOption): Int = when (option) {
            HasAnswerOption.LINK -> R.drawable.ic_diag_link_24dp
            HasAnswerOption.EMBED -> R.drawable.ic_embed_white_24dp
            HasAnswerOption.FILE -> R.drawable.ic_attachment_white_24dp
            HasAnswerOption.VIDEO -> R.drawable.ic_play_circle_outline_white_24dp
            HasAnswerOption.IMAGE -> R.drawable.ic_text_image_24dp
            HasAnswerOption.SOUND -> R.drawable.ic_sound_24dp
            HasAnswerOption.STICKER -> R.drawable.ic_sticker_icon_24dp
        }
    }

    /** A previous query, rendered through the same AST renderer as the input field. */
    private class RecentQueryViewHolder(
        adapter: WidgetSearchSuggestionsAdapter,
    ) : MGRecyclerViewHolder<WidgetSearchSuggestionsAdapter, MGRecyclerDataPayload>(
        R.layout.widget_search_suggestions_item_recent_query,
        adapter,
    ) {
        private val container: View =
            itemView.findViewById(R.id.search_suggestion_item_recent_query_container)
        private val label: TextView =
            itemView.findViewById(R.id.search_suggestions_item_recent_query_text)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val query = ((data as SingleTypePayload<*>).data as RecentQuerySuggestion).query
            label.text = AstRenderer.render(query, itemView.context)
            container.setOnClickListener { adapter.onRecentQueryClicked(query) }
        }
    }
}
