package com.discord.widgets.hubs

import android.util.Patterns
import com.discord.R
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.google.android.material.textfield.TextInputLayout

/**
 * The email form used by [WidgetHubEmailFlow].
 *
 * This is the reference's `AuthUtils.createEmailValidationManager`: required, then well-formed. That
 * class lives in `com.discord.utilities.auth`, which this project has not reconstructed and this
 * wave does not own — move the two helpers there and delete this file when it lands.
 */
internal fun createEmailValidationManager(emailInput: TextInputLayout): ValidationManager =
    ValidationManager(
        Input.TextInputLayoutInput(
            EMAIL_FIELD,
            emailInput,
            BasicTextInputValidator.createRequiredInputValidator(R.string.email_required),
            BasicTextInputValidator(R.string.email_invalid, ::isValidEmail),
        ),
    )

internal const val EMAIL_FIELD: String = "email"

private fun isValidEmail(email: String): Boolean =
    email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
