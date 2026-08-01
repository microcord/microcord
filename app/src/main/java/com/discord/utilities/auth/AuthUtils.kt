package com.discord.utilities.auth

import android.util.Patterns
import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.phone.PhoneUtils
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.InputValidator
import com.discord.utilities.view.validators.ValidationManager
import com.google.android.material.textfield.TextInputLayout

object AuthUtils {
    fun createDiscriminatorInputValidator(
        @StringRes invalidFormatResId: Int,
        @StringRes invalidValueResId: Int,
    ): InputValidator<TextInputLayout> = object : InputValidator<TextInputLayout> {
        override fun getErrorMessage(view: TextInputLayout): CharSequence? =
            when (discriminatorError(view.getTextOrEmpty())) {
                DiscriminatorError.FORMAT -> view.context.getString(invalidFormatResId)
                DiscriminatorError.VALUE -> view.context.getString(invalidValueResId)
                null -> null
            }
    }

    fun createEmailInputValidator(@StringRes messageResId: Int) =
        BasicTextInputValidator(messageResId) {
            it.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(it).matches()
        }

    fun createEmailValidationManager(emailInput: TextInputLayout) = ValidationManager(
        Input.TextInputLayoutInput(
            "email",
            emailInput,
            BasicTextInputValidator.createRequiredInputValidator(R.string.email_required),
            createEmailInputValidator(R.string.email_invalid),
        ),
    )

    fun createPasswordInputValidator(@StringRes messageResId: Int) =
        BasicTextInputValidator(messageResId, ::isValidPasswordLength)

    fun createPhoneInputValidator(@StringRes messageResId: Int) =
        BasicTextInputValidator(messageResId, PhoneUtils::isValidPhoneFragment)

    fun isValidPasswordLength(password: String) = password.length in 6..128

    internal fun discriminatorError(value: String): DiscriminatorError? {
        val discriminator = value.toIntOrNull()
        return when {
            discriminator == null || value.length != 4 -> DiscriminatorError.FORMAT
            discriminator <= 0 -> DiscriminatorError.VALUE
            else -> null
        }
    }

    internal enum class DiscriminatorError { FORMAT, VALUE }
}
