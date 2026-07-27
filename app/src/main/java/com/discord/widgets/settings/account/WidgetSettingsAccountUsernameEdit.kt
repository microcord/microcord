package com.discord.widgets.settings.account

import android.content.Context
import android.text.Selection
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import android.content.Intent
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class WidgetSettingsAccountUsernameEdit : AppFragment(R.layout.widget_settings_account_edit) {

    private lateinit var username: TextInputLayout
    private lateinit var discriminator: TextInputLayout
    private lateinit var save: FloatingActionButton
    private val state = StatefulViews(R.id.edit_account_username_wrap, R.id.edit_account_discriminator_wrap)
    private val passwordVerifyLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            state.clear()
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }
    private val validationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                USERNAME_FIELD,
                username,
                BasicTextInputValidator.createRequiredInputValidator(R.string.username_required),
            ),
            Input.EditTextInput(
                DISCRIMINATOR_FIELD,
                discriminator,
                BasicTextInputValidator(R.string.premium_change_discriminator_length_error) {
                    it.length == DISCRIMINATOR_LENGTH
                },
                BasicTextInputValidator(R.string.premium_change_discriminator_zero_error) {
                    it != ZERO_DISCRIMINATOR
                },
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_change_username_title)
        setActionBarDisplayHomeAsUpEnabled()

        username = view.findViewById(R.id.edit_account_username_wrap)
        discriminator = view.findViewById(R.id.edit_account_discriminator_wrap)
        save = view.findViewById(R.id.settings_account_save)
        discriminator.addBindedTextWatcher(this) { discriminator.error = null }
        discriminator.setOnImeActionDone { showPasswordVerification() }
        save.setOnClickListener { showPasswordVerification() }
        state.setupTextWatcherWithSaveAction(this, save, username, discriminator)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountUsernameEdit::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        username.setText(state.get(username.id, me.username))
        discriminator.setText(
            state.get(discriminator.id, String.format(Locale.ROOT, "%04d", me.discriminator)),
        )
        username.editText?.text?.let { Selection.setSelection(it, it.length) }

        val canEditDiscriminator = me.premiumTier.grantsAccessToCustomTagAndAnimatedAvatarFeatures()
        discriminator.editText?.apply {
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
            username = username.getTextOrEmpty(),
            discriminator = discriminator.getTextOrEmpty(),
        )
    }

    companion object {
        private const val USERNAME_FIELD = "username"
        private const val DISCRIMINATOR_FIELD = "discriminator"
        private const val DISCRIMINATOR_LENGTH = 4
        private const val ZERO_DISCRIMINATOR = "0000"

        @JvmStatic
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAccountUsernameEdit::class.java)
        }
    }
}
