package com.discord.widgets.settings.account

import android.content.Context
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.textfield.TextInputLayout

open class WidgetSettingsAccountChangePassword :
    AppFragment(R.layout.widget_settings_account_change_password) {

    protected lateinit var currentPassword: TextInputLayout
    protected lateinit var newPassword: TextInputLayout
    private lateinit var save: LoadingButton
    private lateinit var dimmer: DimmerView
    private val state = StatefulViews(R.id.change_password_new_password_input)
    private val validationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                CURRENT_PASSWORD_FIELD,
                currentPassword,
                BasicTextInputValidator.createRequiredInputValidator(R.string.password_required),
            ),
            Input.TextInputLayoutInput(
                NEW_PASSWORD_FIELD,
                newPassword,
                BasicTextInputValidator.createRequiredInputValidator(R.string.password_requirements_empty),
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        currentPassword = view.findViewById(R.id.change_password_current_password_input)
        newPassword = view.findViewById(R.id.change_password_new_password_input)
        save = view.findViewById(R.id.change_password_save)
        dimmer = view.findViewById(R.id.dimmer_view)

        state.setupUnsavedChangesConfirmation(this)
        save.setIsLoading(false)
        save.setText(getText(R.string.change_password))
        listOf(currentPassword, newPassword).forEach { input ->
            input.addBindedTextWatcher(this) { input.error = null }
        }
        save.setOnClickListener { saveNewPassword() }
    }

    private fun saveNewPassword() {
        if (!validationManager.validate(false)) return
        hideKeyboard()
        save.setIsLoading(true)
        val replacement = newPassword.getTextOrEmpty()
        RestAPI.api.patchUser(
            RestAPIParams.UserInfo(
                password = currentPassword.getTextOrEmpty(),
                newPassword = replacement,
                pushToken = StoreStream.getNotifications().getPushToken(),
            ),
        )
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(
                errorClass = javaClass,
                context = context,
                errorHandler = ::handleError,
            ) { updatedUser ->
                updatedUser.token?.let(StoreStream.getAuthentication()::onPasswordChanged)
                // ponytail: Smart Lock is absent; credential-manager sync belongs in utilities/auth.
                onChangePasswordSuccess()
            }
    }

    private fun handleError(error: RestApiError) {
        save.setIsLoading(false)
        val unhandledFields = validationManager.setErrors(error.messages)
        error.showErrorToasts = error.messages.isEmpty() || unhandledFields.isNotEmpty()
    }

    open fun onChangePasswordSuccess() {
        AppToast.show(this, R.string.saved_settings)
        @Suppress("DEPRECATION")
        activity?.onBackPressed()
    }

    companion object {
        const val CURRENT_PASSWORD_FIELD = "password"
        const val NEW_PASSWORD_FIELD = "new_password"

        @JvmStatic
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAccountChangePassword::class.java)
        }
    }
}
