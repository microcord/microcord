package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.text.Selection
import android.view.View
import android.widget.RelativeLayout
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetSettingsAccountEditBinding
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.auth.AuthUtils
import com.discord.utilities.premium.grantsAccessToCustomTagAndAnimatedAvatarFeatures
import com.discord.utilities.user.UserUtils.hasUniqueUsername
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

    /** Null until the first `me` arrives; the form's shape depends on whether the account migrated. */
    private var hasUniqueUsername: Boolean? = null
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
        if (hasUniqueUsername != me.hasUniqueUsername) {
            hasUniqueUsername = me.hasUniqueUsername
            configureDiscriminatorField(me.hasUniqueUsername)
        }

        binding.editAccountUsernameWrap.setText(
            state.get(binding.editAccountUsernameWrap.id, me.username),
        )
        binding.editAccountUsernameWrap.editText?.text?.let { Selection.setSelection(it, it.length) }

        if (me.hasUniqueUsername) return

        binding.editAccountDiscriminatorWrap.setText(
            state.get(
                binding.editAccountDiscriminatorWrap.id,
                String.format(Locale.ROOT, "%04d", me.discriminator),
            ),
        )
        val canEditDiscriminator = me.premiumTier.grantsAccessToCustomTagAndAnimatedAvatarFeatures()
        binding.editAccountDiscriminatorWrap.editText?.apply {
            isFocusable = canEditDiscriminator
            isFocusableInTouchMode = canEditDiscriminator
            isLongClickable = canEditDiscriminator
        }
    }

    /**
     * A migrated account has no discriminator to edit, so the field and its `#` are dropped and the
     * username input takes the full row. The validator is rebuilt to match, otherwise the hidden
     * field's "must not be 0000" rule would refuse every save.
     */
    private fun configureDiscriminatorField(hasUniqueUsername: Boolean) {
        binding.editAccountTagLayoutContainer.visibility =
            if (hasUniqueUsername) View.GONE else View.VISIBLE
        (binding.editAccountUsernameWrap.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            if (hasUniqueUsername) {
                removeRule(RelativeLayout.START_OF)
                addRule(RelativeLayout.ALIGN_PARENT_END)
            } else {
                removeRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.START_OF, R.id.edit_account_tag_layout_container)
            }
            binding.editAccountUsernameWrap.layoutParams = this
        }

        val usernameInput = Input.TextInputLayoutInput(
            USERNAME_FIELD,
            binding.editAccountUsernameWrap,
            BasicTextInputValidator.createRequiredInputValidator(R.string.username_required),
        )
        validationManager = if (hasUniqueUsername) {
            ValidationManager(usernameInput)
        } else {
            ValidationManager(
                usernameInput,
                Input.EditTextInput(
                    DISCRIMINATOR_FIELD,
                    binding.editAccountDiscriminatorWrap,
                    AuthUtils.createDiscriminatorInputValidator(
                        R.string.premium_change_discriminator_length_error,
                        R.string.premium_change_discriminator_zero_error,
                    ),
                ),
            )
        }
    }

    private fun showPasswordVerification() {
        if (!::validationManager.isInitialized || !validationManager.validate(false)) return
        WidgetUserPasswordVerify.launchUpdateAccountSettings(
            requireContext(),
            passwordVerifyLauncher,
            username = binding.editAccountUsernameWrap.getTextOrEmpty(),
            discriminator = binding.editAccountDiscriminatorWrap.getTextOrEmpty()
                .takeUnless { hasUniqueUsername == true },
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
