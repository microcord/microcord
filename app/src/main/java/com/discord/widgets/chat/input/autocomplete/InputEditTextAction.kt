package com.discord.widgets.chat.input.autocomplete

import android.text.style.CharacterStyle

sealed class InputEditTextAction(open val assumedInput: CharSequence) {
    data class ClearSpans(override val assumedInput: CharSequence) : InputEditTextAction(assumedInput)

    data class InsertText(
        override val assumedInput: CharSequence,
        val toAppend: CharSequence,
        val insertRange: IntRange,
        val selectionIndex: Int,
    ) : InputEditTextAction(assumedInput)

    data class None(override val assumedInput: CharSequence) : InputEditTextAction(assumedInput)

    data class RemoveText(
        override val assumedInput: CharSequence,
        val range: IntRange,
        val selectionIndex: Int,
    ) : InputEditTextAction(assumedInput)

    data class ReplaceCharacterStyleSpans(
        override val assumedInput: CharSequence,
        val spans: Map<IntRange, List<CharacterStyle>> = emptyMap(),
    ) : InputEditTextAction(assumedInput)

    data class ReplacePillSpans(
        override val assumedInput: CharSequence,
        val spans: Map<IntRange, List<CharacterStyle>> = emptyMap(),
    ) : InputEditTextAction(assumedInput)

    data class ReplaceText(
        override val assumedInput: CharSequence,
        val newText: CharSequence,
        val selectionIndex: Int = newText.length,
    ) : InputEditTextAction(assumedInput)

    data class SelectText(
        override val assumedInput: CharSequence,
        val selection: IntRange,
    ) : InputEditTextAction(assumedInput)
}
