package com.discord.widgets.chat.input.emoji

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.app.AppBottomSheet
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.isPremiumTier2

/**
 * The emoji picker presented over the current screen, e.g. for choosing a reaction.
 *
 * The sheet is fixed at 90% of the screen height rather than sized to content: the grid is
 * effectively unbounded, so a wrap-content sheet would fill the screen anyway but only after a
 * visible re-layout.
 */
class WidgetEmojiPickerSheet : AppBottomSheet() {

    private var listener: ((MessageReactionEmoji) -> Unit)? = null
    /** Whether the picker offers the super reaction toggle; see [showForReaction]. */
    private var superReactions = false
    private var burst = false
    /** False for the pickers that borrow this sheet to choose an emoji that is not a reaction. */
    private var forReaction = true

    override fun getContentViewResId() = R.layout.widget_emoji_picker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picker = WidgetEmojiPicker.create(
            EmojiPickerContextType.Chat,
            EmojiPickerMode.BOTTOM_SHEET,
            forReaction = forReaction,
        ).apply {
            onPicked = { emoji ->
                listener?.invoke(emoji.toReactionEmoji())
                dismiss()
            }
            if (superReactions) onSuperReactionToggled = { burst = it }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.emoji_sheet_emoji_picker_content, picker)
            .runOnCommit {
                view.layoutParams = view.layoutParams.apply {
                    height = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
                }
            }
            .commit()
    }

    /**
     * A reaction is identified by `(id, name, animated)`; for a Unicode emoji the id is null and
     * the "name" is the surrogate pair itself, which is what the reactions endpoint expects.
     */
    private fun Emoji.toReactionEmoji(): MessageReactionEmoji = when (this) {
        is ModelEmojiCustom -> MessageReactionEmoji(idStr, name, isAnimated)
        else -> MessageReactionEmoji(null, getMessageContentReplacement(), false)
    }

    companion object {
        private const val SHEET_HEIGHT_RATIO = 0.9

        @JvmOverloads
        fun show(
            manager: FragmentManager,
            forReaction: Boolean = true,
            listener: (MessageReactionEmoji) -> Unit,
        ) {
            WidgetEmojiPickerSheet().apply {
                this.listener = listener
                this.forReaction = forReaction
            }
                .show(manager, WidgetEmojiPickerSheet::class.java.simpleName)
        }

        /**
         * The picker for reacting to a message. With Nitro it carries RN's super reaction toggle,
         * and [listener] is told whether the emoji was picked as one.
         */
        fun showForReaction(manager: FragmentManager, listener: (MessageReactionEmoji, Boolean) -> Unit) {
            WidgetEmojiPickerSheet().apply {
                superReactions = StoreStream.getUsers().getMe().isPremiumTier2
                this.listener = { emoji -> listener(emoji, burst) }
            }
                .show(manager, WidgetEmojiPickerSheet::class.java.simpleName)
        }
    }
}
