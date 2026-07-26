package com.discord.widgets.hubs

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.appViewModels
import com.discord.i18n.I18nSpans
import com.discord.stores.StoreStream
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.GuildView
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "What's your name?" — hubs ask for a real name, stored as the per-guild nickname.
 *
 * Binds `R.layout.widget_hub_add_name`. Closes itself when the guild it was opened for is not a hub;
 * that is a routing bug rather than a user-visible state, so it is logged and dismissed rather than
 * rendered.
 */
class WidgetHubAddName : AppFragment(R.layout.widget_hub_add_name) {

    private val args: HubAddNameArgs by hubArgs()

    private val viewModel: WidgetHubAddNameViewModel by appViewModels {
        WidgetHubAddNameViewModel(args.guildId)
    }

    private lateinit var guildView: GuildView
    private lateinit var title: TextView
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private lateinit var submitButton: LoadingButton

    private val validationManager: ValidationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                NICKNAME_FIELD,
                nameLayout,
                BasicTextInputValidator.createRequiredInputValidator(R.string.member_verification_form_required_item),
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        guildView = view.findViewById(R.id.discord_hub_add_name_image)
        title = view.findViewById(R.id.discord_hub_add_name_title)
        nameLayout = view.findViewById(R.id.discord_hub_add_name_input_layout)
        nameInput = view.findViewById(R.id.discord_hub_add_name_input)
        submitButton = view.findViewById(R.id.discord_hub_add_name_title_button)

        submitButton.setOnClickListener { maybeUpdateName() }
        showKeyboard(nameInput)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guild = StoreStream.getGuilds().getGuild(args.guildId)
        if (guild == null || !guild.isHub()) {
            activity?.finish()
            AppLog.e("Guild is not a hub", IllegalStateException("Guild id: ${args.guildId}"))
        }

        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubAddName::class.java) { configureUI(it) }
    }

    private fun configureUI(state: AddNameState) {
        val context = context ?: return
        submitButton.setIsLoading(state.changeNicknameAsync is Loading)
        nameInput.setText(state.nickname)
        title.text = I18nSpans.render(
            getString(R.string.hub_real_name_modal_header),
            "guildName" to state.guild?.name,
        )
        state.guild?.let { guild ->
            guildView.configure(IconUtils.getForGuild(guild), guild.shortName)
        }

        state.changeNicknameAsync.handleResponse(context) {
            activity?.onBackPressed()
            hideKeyboard(view)
        }
    }

    private fun maybeUpdateName() {
        if (validationManager.validate()) {
            viewModel.setName(nameInput.text?.toString().orEmpty())
        }
    }

    private companion object {
        /**
         * Field name used to correlate a server error with this input. The reference passes the
         * literal string `"binding.discordHubAddNameInputLayout"`, which can never match a server
         * error key; the REST body's own field name is used instead so `setErrors` can land.
         */
        const val NICKNAME_FIELD = "nick"
    }
}
