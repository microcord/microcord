package com.discord.utilities.view.validators

import androidx.annotation.StringRes
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.google.android.material.textfield.TextInputLayout

/**
 * Validates a [TextInputLayout]'s current text with a predicate, reporting [messageResId] when the
 * predicate rejects it.
 */
class BasicTextInputValidator(
    @StringRes val messageResId: Int,
    private val inputPredicate: (String) -> Boolean,
) : InputValidator<TextInputLayout> {

    override fun getErrorMessage(view: TextInputLayout): CharSequence? {
        if (inputPredicate(view.getTextOrEmpty())) return null
        return view.context.getString(messageResId)
    }

    companion object {
        /** Rejects an empty field. */
        @JvmStatic
        fun createRequiredInputValidator(@StringRes messageResId: Int): BasicTextInputValidator =
            BasicTextInputValidator(messageResId) { it.isNotEmpty() }
    }
}
