package com.discord.widgets.chat.input.emoji

import androidx.fragment.app.FragmentManager
import com.discord.widgets.chat.input.expression.WidgetEmojiPickerChatSheet

object EmojiPickerNavigator {
    const val ARG_EMOJI_PICKER_CONTEXT_TYPE = "EMOJI_PICKER_CONTEXT_TYPE"
    const val ARG_MODE = "MODE"

    @JvmStatic
    fun launchBottomSheet(
        fragmentManager: FragmentManager,
        emojiPickerListener: EmojiPickerListener?,
        emojiPickerContextType: EmojiPickerContextType,
        onCancel: (() -> Unit)? = null,
    ) {
        WidgetEmojiPickerChatSheet.show(
            fragmentManager = fragmentManager,
            listener = emojiPickerListener,
            emojiPickerContextType = emojiPickerContextType,
            onCancel = onCancel,
        )
    }
}
