package com.discord.widgets.auth

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import com.discord.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date

private const val BIRTHDAY_RESULT = "auth_birthday_result"
private const val BIRTHDAY_VALUE = "auth_birthday_value"

class WidgetAuthBirthday : AuthFragment(R.layout.widget_auth_birthday, AuthRoute.BIRTHDAY) {
    private val birthday get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_birthday)
    private val consent get() = requireView().findViewById<CheckBox>(R.id.auth_tos_opt_in)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        birthday.editText?.apply {
            isFocusable = false
            setOnClickListener { chooseBirthday(model.state.birthday, ::setBirthday) }
        }
        birthday.setOnClickListener { chooseBirthday(model.state.birthday, ::setBirthday) }
        consent.visibility = if (model.state.consentRequired) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.auth_register_button).setOnClickListener { submitBirthday() }
    }

    private fun setBirthday(value: Long) = model.update { it.copy(birthday = value, fieldErrors = emptyMap()) }

    private fun submitBirthday() {
        val state = model.state.copy(consented = consent.isChecked)
        val birthday = state.birthday
        when {
            birthday == null -> model.update { it.copy(fieldErrors = mapOf("birthday" to "Birthday is required.")) }
            state.consentRequired && !state.consented ->
                model.update { it.copy(fieldErrors = mapOf("consent" to "You must agree before continuing.")) }
            else -> {
                parentFragmentManager.setFragmentResult(
                    BIRTHDAY_RESULT,
                    Bundle().apply { putLong(BIRTHDAY_VALUE, birthday) },
                )
                actions.close()
            }
        }
    }

    override fun render(state: AuthUiState) {
        birthday.editText?.setText(
            state.birthday?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty(),
        )
        birthday.setErrorFrom(state, "birthday")
        consent.error = state.fieldErrors["consent"]
    }
}
