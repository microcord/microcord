package com.discord.utilities.view.validators

/**
 * Validates one aspect of an input view.
 *
 * @return the message to show when the value is invalid, or null when it passes.
 */
interface InputValidator<T> {
    fun getErrorMessage(view: T): CharSequence?
}
