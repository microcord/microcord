package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.util.Patterns
import android.view.View
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.extensions.setText
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.discord.widgets.user.WidgetUserPasswordVerify
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

class WidgetSettingsAccountEmailEdit : AppFragment(R.layout.widget_settings_account_email_edit) {

    private lateinit var email: TextInputLayout
    private lateinit var description: LinkifiedTextView
    private lateinit var next: Button
    private lateinit var save: FloatingActionButton
    private lateinit var dimmer: DimmerView
    private val state = StatefulViews(R.id.edit_account_email_wrap)
    private val nextScreenLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            state.clear()
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    private val validationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                EMAIL_FIELD,
                email,
                BasicTextInputValidator.createRequiredInputValidator(R.string.email_required),
                BasicTextInputValidator(R.string.email_invalid) {
                    Patterns.EMAIL_ADDRESS.matcher(it).matches()
                },
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_change_email_title)
        setActionBarDisplayHomeAsUpEnabled()

        email = view.findViewById(R.id.edit_account_email_wrap)
        description = view.findViewById(R.id.edit_account_email_description)
        next = view.findViewById(R.id.settings_account_next)
        save = view.findViewById(R.id.settings_account_save)
        dimmer = view.findViewById(R.id.dimmer_view)

        if (shouldConfirmEmail()) {
            description.visibility = View.VISIBLE
            description.text = SettingsStrings.render(
                view.context,
                R.string.user_settings_account_email_description,
                "helpdeskURL" to EMAIL_HELP_URL,
            )
            next.visibility = View.VISIBLE
            save.hide()
            email.setOnImeActionDone { showCodeVerification() }
            next.setOnClickListener { showCodeVerification() }
            state.setupTextWatcherWithSaveAction(this, next, email)
        } else {
            description.visibility = View.GONE
            next.visibility = View.GONE
            email.setOnImeActionDone { showPasswordVerification() }
            save.setOnClickListener { showPasswordVerification() }
            state.setupTextWatcherWithSaveAction(this, save, email)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEdit::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        email.setText(state.get(email.id, me.email.orEmpty()))
        // ponytail: Smart Lock is absent; credential-manager sync belongs in utilities/auth.
    }

    private fun showCodeVerification() {
        if (!validationManager.validate(false)) return
        RestAPI.api.userEmail()
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEdit::class.java, context) {
                WidgetSettingsAccountEmailEditConfirm.launch(
                    requireContext(),
                    nextScreenLauncher,
                    email.getTextOrEmpty(),
                )
            }
    }

    private fun showPasswordVerification() {
        if (!validationManager.validate(false)) return
        WidgetUserPasswordVerify.launchUpdateAccountSettings(
            requireContext(),
            nextScreenLauncher,
            email = email.getTextOrEmpty(),
        )
    }

    private fun shouldConfirmEmail(): Boolean =
        arguments?.getBoolean(EXTRA_SHOULD_CONFIRM_EMAIL)
            ?: mostRecentIntent.getBooleanExtra(EXTRA_SHOULD_CONFIRM_EMAIL, false)

    companion object {
        private const val EMAIL_FIELD = "email"
        private const val EXTRA_SHOULD_CONFIRM_EMAIL = "extra_should_confirm_email"
        private const val EMAIL_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360000961212"

        @JvmStatic
        fun launch(context: Context, shouldConfirmEmail: Boolean) {
            SettingsNavigation.launch(
                context,
                WidgetSettingsAccountEmailEdit::class.java,
                Intent().putExtra(EXTRA_SHOULD_CONFIRM_EMAIL, shouldConfirmEmail),
            )
        }
    }
}
