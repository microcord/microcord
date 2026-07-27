package com.discord.widgets.chat.input.emoji

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.app.AppBottomSheet
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom

/**
 * The emoji picker presented over the current screen, e.g. for choosing a reaction.
 *
 * The sheet is fixed at 90% of the screen height rather than sized to content: the grid is
 * effectively unbounded, so a wrap-content sheet would fill the screen anyway but only after a
 * visible re-layout.
 */
class WidgetEmojiPickerSheet : AppBottomSheet() {

    private var listener: ((MessageReactionEmoji) -> Unit)? = null

    override fun getContentViewResId() = R.layout.widget_emoji_picker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picker = WidgetEmojiPicker.create(
            EmojiPickerContextType.Chat,
            EmojiPickerMode.BOTTOM_SHEET,
        ).apply {
            onPicked = { emoji ->
                listener?.invoke(emoji.toReactionEmoji())
                dismiss()
            }
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

        fun show(manager: FragmentManager, listener: (MessageReactionEmoji) -> Unit) {
            WidgetEmojiPickerSheet().apply { this.listener = listener }
                .show(manager, WidgetEmojiPickerSheet::class.java.simpleName)
        }
    }
}
