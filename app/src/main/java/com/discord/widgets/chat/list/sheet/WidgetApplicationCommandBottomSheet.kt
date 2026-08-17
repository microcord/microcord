package com.discord.widgets.chat.list.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.ui
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton
import rx.subscriptions.CompositeSubscription
import com.discord.i18n.i18nRender

class WidgetApplicationCommandBottomSheet : AppBottomSheet() {

    private val viewModel by appViewModels {
        WidgetApplicationCommandBottomSheetViewModel(
            interactionId = argumentsOrDefault.getLong(EXTRA_INTERACTION_ID),
            messageId = argumentsOrDefault.getLong(EXTRA_MESSAGE_ID),
            channelId = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID),
            guildId = argumentsOrDefault.takeIf { it.containsKey(EXTRA_GUILD_ID) }
                ?.getLong(EXTRA_GUILD_ID),
            interactionUserId = argumentsOrDefault.getLong(EXTRA_USER_ID),
            applicationId = argumentsOrDefault.getLong(EXTRA_APPLICATION_ID),
            messageNonce = argumentsOrDefault.getString(ARG_MESSAGE_NONCE),
        )
    }

    private lateinit var avatar: VitoImageView
    private lateinit var title: TextView
    private lateinit var applicationName: TextView
    private lateinit var content: TextView
    private lateinit var loader: ProgressBar
    private lateinit var copy: MaterialButton

    override fun getContentViewResId(): Int = R.layout.widget_application_command_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        avatar = view.findViewById(R.id.command_bottom_sheet_command_avatar)
        title = view.findViewById(R.id.command_bottom_sheet_command_title)
        applicationName = view.findViewById(R.id.command_bottom_sheet_command_title_application_name)
        content = view.findViewById(R.id.command_bottom_sheet_command_content)
        loader = view.findViewById(R.id.command_bottom_sheet_command_loader)
        copy = view.findViewById(R.id.command_bottom_sheet_command_copy_button)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        hideKeyboard()
        compositeSubscription.add(
            viewModel.observeViewState().ui(this).subscribe(::configureUI),
        )
    }

    private fun configureUI(viewState: WidgetApplicationCommandBottomSheetViewModel.ViewState) {
        val loading = viewState === WidgetApplicationCommandBottomSheetViewModel.ViewState.Loading
        loader.visibility = if (loading) View.VISIBLE else View.GONE
        copy.isEnabled = !loading
        when (viewState) {
            WidgetApplicationCommandBottomSheetViewModel.ViewState.Failed -> dismiss()
            WidgetApplicationCommandBottomSheetViewModel.ViewState.Loading -> Unit
            is WidgetApplicationCommandBottomSheetViewModel.ViewState.Loaded -> configureLoaded(viewState)
        }
    }

    private fun configureLoaded(state: WidgetApplicationCommandBottomSheetViewModel.ViewState.Loaded) {
        val commandName = "/${state.applicationCommandData.name}"
        val username = state.interactionUser?.nick ?: state.user?.username.orEmpty()
        title.text = title.context.i18nRender(
            R.string.system_message_application_command_used_as_title,
            "username" to username,
            "commandName" to commandName,
        )

        val appName = state.application?.bot?.username ?: state.application?.name
        applicationName.text = appName
        applicationName.visibility = if (appName == null) View.GONE else View.VISIBLE
        val application = state.application
        avatar.visibility = if (application?.icon == null) View.GONE else View.VISIBLE
        MGImages.setImage(
            avatar,
            application?.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
        )

        content.text = commandDisplay(state)
        copy.setOnClickListener {
            val text = state.applicationCommandData.toSlashCommandCopyString(state.commandValues)
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
            Toast.makeText(requireContext(), R.string.copied_text, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun commandDisplay(state: WidgetApplicationCommandBottomSheetViewModel.ViewState.Loaded) =
        SpannableStringBuilder("/${state.applicationCommandData.name} ").apply {
            state.applicationCommandData.options.orEmpty().forEach { appendOption(it, state.commandValues) }
        }

    private fun SpannableStringBuilder.appendOption(
        option: com.discord.api.commands.ApplicationCommandValue,
        values: Map<String, WidgetApplicationCommandBottomSheetViewModel.SlashCommandParam>,
    ) {
        val children = option.options
        if (!children.isNullOrEmpty()) {
            append(option.name).append(' ')
            children.forEach { appendOption(it, values) }
            return
        }
        append(option.name)
        values[option.name]?.value?.takeIf(String::isNotBlank)?.let { value ->
            append(": ")
            val start = length
            append(value).append(' ')
            values[option.name]?.valueColor?.let { color ->
                setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    companion object {
        const val ARG_MESSAGE_NONCE = "arg_message_nonce"
        private const val EXTRA_INTERACTION_ID = "com.discord.intent.extra.EXTRA_INTERACTION_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"
        private const val EXTRA_APPLICATION_ID = "com.discord.intent.extra.EXTRA_APPLICATION_ID"

        fun show(
            fragmentManager: FragmentManager,
            interactionId: Long,
            messageId: Long,
            channelId: Long,
            guildId: Long?,
            userId: Long,
            applicationId: Long,
            messageNonce: String?,
        ) {
            WidgetApplicationCommandBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_INTERACTION_ID, interactionId)
                    putLong(EXTRA_MESSAGE_ID, messageId)
                    putLong(EXTRA_CHANNEL_ID, channelId)
                    if (guildId != null) putLong(EXTRA_GUILD_ID, guildId)
                    putLong(EXTRA_USER_ID, userId)
                    putLong(EXTRA_APPLICATION_ID, applicationId)
                    putString(ARG_MESSAGE_NONCE, messageNonce)
                }
            }.show(fragmentManager, WidgetApplicationCommandBottomSheet::class.java.name)
        }
    }
}
