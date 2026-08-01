package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.text.Selection
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsAccountEditBinding
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.auth.AuthUtils
import com.discord.utilities.premium.grantsAccessToCustomTagAndAnimatedAvatarFeatures
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.extensions.setText
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.discord.widgets.user.WidgetUserPasswordVerify
import java.util.Locale

class WidgetSettingsAccountUsernameEdit : AppFragment(R.layout.widget_settings_account_edit) {

    private var _binding: WidgetSettingsAccountEditBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val state = StatefulViews(R.id.edit_account_username_wrap, R.id.edit_account_discriminator_wrap)
    private lateinit var validationManager: ValidationManager
    private val passwordVerifyLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            state.clear()
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsAccountEditBinding.bind(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_change_username_title)
        setActionBarDisplayHomeAsUpEnabled()

        validationManager = ValidationManager(
            Input.TextInputLayoutInput(
                USERNAME_FIELD,
                binding.editAccountUsernameWrap,
                BasicTextInputValidator.createRequiredInputValidator(R.string.username_required),
            ),
            Input.EditTextInput(
                DISCRIMINATOR_FIELD,
                binding.editAccountDiscriminatorWrap,
                AuthUtils.createDiscriminatorInputValidator(
                    R.string.premium_change_discriminator_length_error,
                    R.string.premium_change_discriminator_zero_error,
                ),
            ),
        )
        binding.editAccountDiscriminatorWrap.addBindedTextWatcher(this) {
            binding.editAccountDiscriminatorWrap.error = null
        }
        binding.editAccountDiscriminatorWrap.setOnImeActionDone { showPasswordVerification() }
        binding.settingsAccountSave.setOnClickListener { showPasswordVerification() }
        state.setupTextWatcherWithSaveAction(
            this,
            binding.settingsAccountSave,
            binding.editAccountUsernameWrap,
            binding.editAccountDiscriminatorWrap,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountUsernameEdit::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        binding.editAccountUsernameWrap.setText(
            state.get(binding.editAccountUsernameWrap.id, me.username),
        )
        binding.editAccountDiscriminatorWrap.setText(
            state.get(
                binding.editAccountDiscriminatorWrap.id,
                String.format(Locale.ROOT, "%04d", me.discriminator),
            ),
        )
        binding.editAccountUsernameWrap.editText?.text?.let { Selection.setSelection(it, it.length) }

        val canEditDiscriminator = me.premiumTier.grantsAccessToCustomTagAndAnimatedAvatarFeatures()
        binding.editAccountDiscriminatorWrap.editText?.apply {
            isFocusable = canEditDiscriminator
            isFocusableInTouchMode = canEditDiscriminator
            isLongClickable = canEditDiscriminator
        }
    }

    private fun showPasswordVerification() {
        if (!validationManager.validate(false)) return
        WidgetUserPasswordVerify.launchUpdateAccountSettings(
            requireContext(),
            passwordVerifyLauncher,
            username = binding.editAccountUsernameWrap.getTextOrEmpty(),
            discriminator = binding.editAccountDiscriminatorWrap.getTextOrEmpty(),
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val USERNAME_FIELD = "username"
        private const val DISCRIMINATOR_FIELD = "discriminator"

        @JvmStatic
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAccountUsernameEdit::class.java)
        }
    }
}
