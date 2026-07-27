package com.discord.widgets.channels.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setSelectionEnd
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.channels.settings.ChannelGroupDMSettingsViewModel.Event
import com.discord.widgets.channels.settings.ChannelGroupDMSettingsViewModel.ViewState
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.servers.NotificationMuteSettingsView
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/**
 * "Customize Group": rename a group DM, change or remove its icon, mute it, or leave it.
 *
 * The save FAB is only offered while something differs from the saved state — the name typed into
 * the field or a pending icon edit. The reference tracks that through `StatefulViews`, which has
 * not been reconstructed; this screen computes the same condition directly from the current view
 * state plus the field's text, which is equivalent for a screen with exactly two editable values.
 */
class WidgetChannelGroupDMSettings : AppFragment(R.layout.widget_channel_group_dm_settings) {

    private val viewModel: ChannelGroupDMSettingsViewModel by appViewModels {
        ChannelGroupDMSettingsViewModel(mostRecentIntent.getLongExtra(INTENT_EXTRA_CHANNEL_ID, 0L))
    }

    private lateinit var editName: TextInputLayout
    private lateinit var saveButton: FloatingActionButton
    private lateinit var muteSettings: NotificationMuteSettingsView
    private lateinit var scrollView: NestedScrollView
    private lateinit var groupIcon: SimpleDraweeView
    private lateinit var groupIconLabel: TextView
    private lateinit var groupIconRemove: TextView

    /** The name last written into the field from the store, used to detect user edits. */
    private var savedName: CharSequence = ""

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        editName = view.findViewById(R.id.channel_settings_edit_name)
        saveButton = view.findViewById(R.id.channel_settings_save)
        muteSettings = view.findViewById(R.id.group_dm_notifications_mute_settings_view)
        scrollView = view.findViewById(R.id.group_dm_settings_scroll_view)
        groupIcon = view.findViewById(R.id.settings_group_icon)
        groupIconLabel = view.findViewById(R.id.settings_group_icon_label)
        groupIconRemove = view.findViewById(R.id.settings_group_icon_remove)

        groupIconLabel.text = requireContext().i18nFormat(R.string.minimum_size, "size" to ICON_MIN_SIZE_PX)
        editName.editText?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = configureSaveVisibility()
            },
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelGroupDMSettings::class.java) { configureUi(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelGroupDMSettings::class.java) { handleEvent(it) }
    }

    private fun configureUi(viewState: ViewState) {
        when (viewState) {
            ViewState.Invalid -> appActivity?.finish()

            is ViewState.Valid -> {
                val channel = viewState.channelSettings.channel
                val displayName = channel.getDisplayNameOrDefault(requireContext(), true)
                setActionBarTitle(R.string.channel_settings)
                setActionBarSubtitle(displayName)
                setActionBarDisplayHomeAsUpEnabled()
                setActionBarOptionsMenu(R.menu.menu_main_group_settings, onSelected = { menuItem ->
                    if (menuItem.itemId == R.id.menu_leave_group) {
                        confirmLeave(displayName) { viewModel.leaveGroup() }
                    }
                })

                // Only overwrite the field while the user has not started editing it, so a store
                // emission mid-edit does not throw away what they typed.
                if (editName.editText?.text.isNullOrEmpty() || editName.editText?.text?.toString() == savedName) {
                    editName.setText(displayName)
                    editName.setSelectionEnd()
                }
                savedName = displayName

                configureIcon(viewState)

                saveButton.setOnClickListener {
                    viewModel.editGroup(channel.id, editName.editText?.text?.toString().orEmpty())
                }
                configureSaveVisibility()

                muteSettings.updateView(
                    NotificationMuteSettingsView.ViewState(
                        isMuted = viewState.channelSettings.isMuted,
                        muteEndTime = viewState.channelSettings.muteEndTime,
                        rawMuteText = getString(R.string.mute_conversation),
                        rawUnmuteText = getString(R.string.unmute_conversation),
                        rawMutedStatusText = getString(R.string.form_label_mobile_dm_muted),
                        rawMutedUntilStatusResId = R.string.form_label_mobile_dm_muted_until,
                    ),
                    onMute = { WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager) },
                    onUnmute = { viewModel.unmute(requireContext()) },
                )
            }
        }
    }

    private fun configureIcon(viewState: ViewState.Valid) {
        IconUtils.setIcon(groupIcon, viewState.currentIconUrl, R.dimen.avatar_size_xxlarge)
        groupIcon.setOnClickListener { openMediaChooser() }
        groupIconLabel.isVisible = viewState.isDefaultPhoto
        groupIconRemove.isVisible = !viewState.isDefaultPhoto
        groupIconRemove.setOnClickListener { viewModel.removeEditedIcon() }
    }

    /** The FAB appears exactly when there is something to save. */
    private fun configureSaveVisibility() {
        val state = viewModel.viewState as? ViewState.Valid
        val nameChanged = editName.editText?.text?.toString().orEmpty() != savedName.toString()
        saveButton.isVisible = state != null && (nameChanged || state.hasUnsavedIconChange)
    }

    private fun handleEvent(event: Event) = when (event) {
        Event.LeaveGroupSuccess -> {
            appActivity?.finish()
            Unit
        }

        Event.SettingsSaved -> {
            AppToast.show(requireContext(), R.string.saved_settings)
            hideKeyboard()
            scrollView.fullScroll(View.FOCUS_UP)
            configureSaveVisibility()
        }
    }

    private fun confirmLeave(groupName: CharSequence, confirmed: () -> Unit) {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = requireContext().i18nFormat(R.string.leave_group_dm_title, "name" to groupName),
            bodyText = requireContext().i18nFormat(R.string.leave_group_dm_body, "name" to groupName),
            okText = getString(R.string.leave_group_dm),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _: View -> confirmed() }),
            dialogTheme = R.attr.notice_theme_positive_red,
        )
    }

    /**
     * A picked image is uploaded as a `data:` URL embedded in the PATCH body, not as a multipart
     * file, so it is encoded here and held in the view state until save.
     */
    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, viewModel::onIconEdited)
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, viewModel::onIconEdited)
    }

    companion object {
        private const val INTENT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"

        /** Discord requires a group icon of at least 128×128. */
        private const val ICON_MIN_SIZE_PX = "128"

        fun create(channelId: Long, context: Context) {
            AppScreenRouter.launch(
                context,
                WidgetChannelGroupDMSettings::class.java,
                Intent().putExtra(INTENT_EXTRA_CHANNEL_ID, channelId),
            )
        }
    }
}
