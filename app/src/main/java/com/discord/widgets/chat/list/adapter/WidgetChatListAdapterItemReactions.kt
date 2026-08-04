package com.discord.widgets.chat.list.adapter

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.views.ReactionView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.google.android.flexbox.FlexboxLayout

/**
 * The reaction strip under a message.
 *
 * Reaction chips are recycled inside the row as well as outside it: the flexbox keeps the
 * [ReactionView]s it already built, hides the surplus and only inflates a new one when a message
 * has more reactions than any message this holder has shown before. The "+" chip is appended last
 * and removed first, so it never ends up buried among the recycled children.
 */
class WidgetChatListAdapterItemReactions(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_reactions, adapter) {

    private val highlightBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val reactionsContainer: FlexboxLayout =
        itemView.findViewById(R.id.chat_list_item_reactions)

    private val quickAddReactionView: ImageView = LayoutInflater.from(itemView.context)
        .inflate(R.layout.reaction_quick_add, null, false) as ImageView

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as ReactionsEntry
        configureCellHighlight(entry.message, highlightBackground, gutterBackground, entry.isPendingReply)
        displayReactions(
            reactions = entry.message.getReactionsMap().values,
            messageId = entry.message.id,
            canAddReactions = entry.canAddReactions,
            canCreateReactions = entry.canCreateReactions,
            animateEmojis = entry.animateEmojis,
        )
    }

    private fun displayReactions(
        reactions: Collection<MessageReaction>,
        messageId: Long,
        canAddReactions: Boolean,
        canCreateReactions: Boolean,
        animateEmojis: Boolean,
    ) {
        removeQuickAddReactionView()

        for (index in reactions.size until reactionsContainer.childCount) {
            reactionsContainer.getChildAt(index).visibility = View.GONE
        }

        val recyclableCount = reactionsContainer.childCount
        reactions.forEachIndexed { index, reaction ->
            val view = if (index < recyclableCount) {
                (reactionsContainer.getChildAt(index) as ReactionView).apply {
                    visibility = View.VISIBLE
                }
            } else {
                createReactionView()
            }
            view.setOnClickListener {
                adapter.onReactionClicked(messageId, reaction, canAddReactions)
            }
            view.setOnLongClickListenerConsumeClick {
                adapter.onReactionLongClicked(messageId, reaction)
            }
            view.configure(reaction, messageId, animateEmojis)
        }

        if (reactions.size < REACTION_LIMIT && canCreateReactions) {
            quickAddReactionView.setOnClickListener { adapter.onQuickAddReactionClicked(messageId) }
            reactionsContainer.addView(quickAddReactionView)
        }
    }

    private fun createReactionView(): ReactionView {
        val context = reactionsContainer.context
        val view = ReactionView(context)
        val horizontalPadding = dpToPixels(REACTION_HORIZONTAL_PADDING_DP)
        view.setPadding(horizontalPadding, view.paddingTop, horizontalPadding, view.paddingBottom)
        view.background = ContextCompat.getDrawable(context, itemView.context.getThemedDrawableRes(R.attr.theme_chat_reaction_background))
        view.gravity = Gravity.CENTER
        reactionsContainer.addView(view)
        view.layoutParams.height = dpToPixels(REACTION_VIEW_HEIGHT_DP)
        return view
    }

    /**
     * The "+" chip is a single shared view, so it must be detached from the flexbox before the
     * reaction chips are re-laid-out; otherwise it would be counted as a recyclable reaction chip
     * and the cast in [displayReactions] would fail.
     */
    private fun removeQuickAddReactionView() {
        val lastIndex = reactionsContainer.childCount - 1
        if (lastIndex < 0) return
        if (reactionsContainer.getChildAt(lastIndex) === quickAddReactionView) {
            reactionsContainer.removeViewAt(lastIndex)
        }
    }


    private companion object {
        /** Discord stops offering the "+" chip once a message carries this many distinct emoji. */
        private const val REACTION_LIMIT = 20
        private const val REACTION_HORIZONTAL_PADDING_DP = 6
        private const val REACTION_VIEW_HEIGHT_DP = 26
    }
}
