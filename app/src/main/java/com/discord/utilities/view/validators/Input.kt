package com.discord.utilities.view.validators

import android.view.View
import com.google.android.material.textfield.TextInputLayout

/** Field name used to correlate a client input with a server-side error key. */
typealias FieldName = String

/**
 * One named field in a form, together with the validators that must all pass for it.
 *
 * [validate] stops at the first failing validator so the user sees the most specific problem, and
 * only renders the message when [showErrors] is set — callers use `validate(false)` to drive an
 * enabled/disabled submit button without flashing errors as the user types.
 */
open class Input<T : View>(
    val name: FieldName,
    val view: T,
    private vararg val validators: InputValidator<in T>,
) {

    /**
     * Renders [errorMessage] on the field.
     *
     * @return true when an error was actually displayed.
     */
    open fun setErrorMessage(errorMessage: CharSequence?): Boolean = false

    open fun validate(showErrors: Boolean = true): Boolean {
        for (validator in validators) {
            val errorMessage = validator.getErrorMessage(view)
            val failed = if (showErrors) {
                setErrorMessage(errorMessage)
            } else {
                !errorMessage.isNullOrEmpty()
            }
            if (failed) return false
        }
        return true
    }

    /** A [TextInputLayout] backed field that renders errors through the layout itself. */
    open class TextInputLayoutInput(
        name: FieldName,
        view: TextInputLayout,
        vararg validators: InputValidator<in TextInputLayout>,
    ) : Input<TextInputLayout>(name, view, *validators) {

        override fun setErrorMessage(errorMessage: CharSequence?): Boolean {
            view.error = errorMessage
            // The password-toggle end icon and the error icon occupy the same slot; drop the error
            // icon so the toggle stays reachable.
            if (view.endIconMode == TextInputLayout.END_ICON_PASSWORD_TOGGLE) {
                view.setErrorIconDrawable(0)
            }
            val hasError = !errorMessage.isNullOrBlank()
            view.isErrorEnabled = hasError
            if (hasError) view.requestFocus()
            return hasError
        }
    }

    /**
     * Legacy alias kept because auth screens refer to their text fields as edit-text inputs; it
     * does not adjust the end icon.
     */
    class EditTextInput(
        name: FieldName,
        view: TextInputLayout,
        vararg validators: InputValidator<in TextInputLayout>,
    ) : Input<TextInputLayout>(name, view, *validators) {

        override fun setErrorMessage(errorMessage: CharSequence?): Boolean {
            view.error = errorMessage
            val hasError = !errorMessage.isNullOrBlank()
            if (hasError) view.requestFocus()
            return hasError
        }
    }

    /** A field of any view type whose error rendering is supplied by the caller. */
    class GenericInput<T : View>(
        name: FieldName,
        view: T,
        validator: InputValidator<in T>,
        private val onError: (T, CharSequence) -> Boolean,
    ) : Input<T>(name, view, validator) {

        override fun setErrorMessage(errorMessage: CharSequence?): Boolean {
            if (errorMessage.isNullOrBlank()) return false
            return onError(view, errorMessage)
        }
    }
}
