package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsAccountEmailEditBinding
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.auth.AuthUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.extensions.setText
import com.discord.utilities.view.validators.ValidationManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.discord.widgets.user.WidgetUserPasswordVerify

class WidgetSettingsAccountEmailEdit : AppFragment(R.layout.widget_settings_account_email_edit) {

    private var _binding: WidgetSettingsAccountEmailEditBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val state = StatefulViews(R.id.edit_account_email_wrap)
    private lateinit var validationManager: ValidationManager
    private val nextScreenLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            state.clear()
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsAccountEmailEditBinding.bind(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_change_email_title)
        setActionBarDisplayHomeAsUpEnabled()

        validationManager = AuthUtils.createEmailValidationManager(binding.editAccountEmailWrap)

        if (shouldConfirmEmail()) {
            binding.editAccountEmailDescription.visibility = View.VISIBLE
            binding.editAccountEmailDescription.text = SettingsStrings.render(
                view.context,
                R.string.user_settings_account_email_description,
                "helpdeskURL" to EMAIL_HELP_URL,
            )
            binding.settingsAccountNext.visibility = View.VISIBLE
            binding.settingsAccountSave.hide()
            binding.editAccountEmailWrap.setOnImeActionDone { showCodeVerification() }
            binding.settingsAccountNext.setOnClickListener { showCodeVerification() }
            state.setupTextWatcherWithSaveAction(
                this,
                binding.settingsAccountNext,
                binding.editAccountEmailWrap,
            )
        } else {
            binding.editAccountEmailDescription.visibility = View.GONE
            binding.settingsAccountNext.visibility = View.GONE
            binding.editAccountEmailWrap.setOnImeActionDone { showPasswordVerification() }
            binding.settingsAccountSave.setOnClickListener { showPasswordVerification() }
            state.setupTextWatcherWithSaveAction(
                this,
                binding.settingsAccountSave,
                binding.editAccountEmailWrap,
            )
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEdit::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        binding.editAccountEmailWrap.setText(
            state.get(binding.editAccountEmailWrap.id, me.email.orEmpty()),
        )
        // ponytail: Smart Lock is absent; credential-manager sync belongs in utilities/auth.
    }

    private fun showCodeVerification() {
        if (!validationManager.validate(false)) return
        RestAPI.api.userEmail()
            .restSubscribeOn()
            .withDimmer(binding.dimmerView)
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEdit::class.java, context) {
                WidgetSettingsAccountEmailEditConfirm.launch(
                    requireContext(),
                    nextScreenLauncher,
                    binding.editAccountEmailWrap.getTextOrEmpty(),
                )
            }
    }

    private fun showPasswordVerification() {
        if (!validationManager.validate(false)) return
        WidgetUserPasswordVerify.launchUpdateAccountSettings(
            requireContext(),
            nextScreenLauncher,
            email = binding.editAccountEmailWrap.getTextOrEmpty(),
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun shouldConfirmEmail(): Boolean =
        arguments?.getBoolean(EXTRA_SHOULD_CONFIRM_EMAIL)
            ?: mostRecentIntent.getBooleanExtra(EXTRA_SHOULD_CONFIRM_EMAIL, false)

    companion object {
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
