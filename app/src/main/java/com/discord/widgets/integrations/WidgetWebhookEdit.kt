package com.discord.widgets.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.media_picker.MediaPicker
import com.discord.models.domain.ModelWebhook
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.WidgetChannelPickerBottomSheet
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsStrings
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Edits one webhook: icon, name and destination channel, plus copying its URL and deleting it.
 *
 * A follower webhook has neither an icon of its own nor a token, so both blocks stand down and
 * "Delete" is the unfollow action.
 */
class WidgetWebhookEdit : AppFragment(R.layout.widget_webhook_edit) {

    private val webhookId: Long get() = mostRecentIntent.getLongExtra(ARG_WEBHOOK_ID, -1L)
    private val guildId: Long get() = mostRecentIntent.getLongExtra(ARG_GUILD_ID, -1L)
    private val webhookType: Int
        get() = mostRecentIntent.getIntExtra(ARG_TYPE, ModelWebhook.TYPE_INCOMING)
    private val token: String? get() = mostRecentIntent.getStringExtra(ARG_TOKEN)

    private lateinit var name: TextInputLayout
    private lateinit var avatar: SimpleDraweeView
    private lateinit var channelRow: TextView
    private lateinit var save: FloatingActionButton

    private var savedName = ""
    private var savedChannelId = 0L
    private var draftChannelId = 0L
    private var draftAvatarDataUrl: String? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.webhook_modal_title)
        name = view.findViewById(R.id.webhook_edit_name)
        avatar = view.findViewById(R.id.webhook_edit_avatar)
        channelRow = view.findViewById(R.id.webhook_edit_channel)
        save = view.findViewById(R.id.webhook_edit_save)

        savedName = mostRecentIntent.getStringExtra(ARG_NAME).orEmpty()
        savedChannelId = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, 0L)
        draftChannelId = savedChannelId
        name.editText?.setText(savedName)
        name.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveVisibility()
        })
        avatar.setImageURI(
            IconUtils.getForUser(webhookId, mostRecentIntent.getStringExtra(ARG_AVATAR), isWebhook = true),
        )

        val isFollower = webhookType == ModelWebhook.TYPE_CHANNEL_FOLLOWER
        view.findViewById<View>(R.id.webhook_edit_avatar_container).isVisible = !isFollower
        avatar.setOnClickListener { openMediaChooser() }
        view.findViewById<View>(R.id.webhook_edit_url_container).isVisible = token != null
        token?.let { webhookToken ->
            val url = "$WEBHOOK_URL_PREFIX$webhookId/$webhookToken"
            view.findViewById<TextView>(R.id.webhook_edit_url).text = url
            view.findViewById<TextView>(R.id.webhook_edit_copy_url).setOnClickListener {
                AppToast.copyToClipboard(requireContext(), url, R.string.integrations_webhook_copied_url)
            }
        }
        view.findViewById<TextView>(R.id.webhook_edit_delete).apply {
            setText(if (isFollower) R.string.integrations_followed_channel_delete else R.string.webhook_delete)
            setOnClickListener { confirmDelete(isFollower) }
        }

        channelRow.setOnClickListener {
            WidgetChannelPickerBottomSheet.launch(this, REQUEST_KEY_CHANNEL, guildId, draftChannelId)
        }
        WidgetChannelPickerBottomSheet.registerForResult(
            fragment = this,
            requestKey = REQUEST_KEY_CHANNEL,
            onChannelSelected = { channelId, _, _ ->
                draftChannelId = channelId
                renderChannel()
                updateSaveVisibility()
            },
            onCreateChannelSelected = {},
        )
        save.setOnClickListener { saveChanges() }
        renderChannel()
        updateSaveVisibility()
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        MediaPicker.requestImageCrop(
            mediaPickerProvider(),
            uri,
            MediaPicker.CropTheme(
                android.graphics.Color.BLACK,
                android.graphics.Color.BLACK,
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE,
            ),
            maxOutputDimensionPx = 1024,
        )
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        MGImages.requestDataUrl(
            context,
            uri,
            mimeType,
            resultCallback = { data ->
                draftAvatarDataUrl = data
                avatar.setImageURI(uri)
                updateSaveVisibility()
            },
            onError = { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
        )
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetWebhookEdit.requireContext()
        override fun getImageFile() = imageFile

        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) =
            this@WidgetWebhookEdit.startActivityForResult(intent, requestCode)
    }

    private fun renderChannel() {
        val channel = StoreStream.getChannels().getChannel(draftChannelId)
        channelRow.text = channel?.getDisplayName().orEmpty()
        channelRow.setCompoundDrawablesRelativeWithIntrinsicBounds(guildChannelIcon(channel), 0, 0, 0)
    }

    private fun updateSaveVisibility() {
        if (!::save.isInitialized) return
        save.isVisible = name.editText?.text?.toString().orEmpty() != savedName ||
            draftChannelId != savedChannelId ||
            draftAvatarDataUrl != null
    }

    private fun saveChanges() {
        RestAPI.api.updateWebhook(
            webhookId,
            RestAPIParams.UpdateWebhook(
                name = name.editText?.text?.toString().orEmpty(),
                channelId = draftChannelId,
                avatar = draftAvatarDataUrl,
            ),
        ).restSubscribeOn().ui(this)
            .appSubscribe(WidgetWebhookEdit::class.java, context = context) {
                hideKeyboard()
                appActivity?.finish()
            }
    }

    private fun confirmDelete(isFollower: Boolean) {
        val webhookName = name.editText?.text?.toString().orEmpty()
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = SettingsStrings.render(
                requireContext(),
                if (isFollower) R.string.integrations_followed_channel_delete_title else R.string.webhook_delete_title,
                "name" to webhookName,
            ).toString(),
            bodyText = SettingsStrings.render(
                requireContext(),
                if (isFollower) R.string.integrations_followed_channel_delete_body else R.string.webhook_delete_body,
                "name" to webhookName,
            ),
            okText = getString(R.string.delete),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(
                R.id.notice_ok to { _: View ->
                    RestAPI.api.deleteWebhook(webhookId).restSubscribeOn().ui(this)
                        .appSubscribe(WidgetWebhookEdit::class.java, context = context) {
                            appActivity?.finish()
                        }
                },
            ),
            dialogTheme = R.attr.notice_theme_positive_red,
        )
    }

    companion object {
        private const val ARG_WEBHOOK_ID = "com.discord.intent.extra.EXTRA_WEBHOOK_ID"
        private const val ARG_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ARG_NAME = "com.discord.intent.extra.EXTRA_WEBHOOK_NAME"
        private const val ARG_AVATAR = "com.discord.intent.extra.EXTRA_WEBHOOK_AVATAR"
        private const val ARG_TOKEN = "com.discord.intent.extra.EXTRA_WEBHOOK_TOKEN"
        private const val ARG_TYPE = "com.discord.intent.extra.EXTRA_WEBHOOK_TYPE"
        private const val REQUEST_KEY_CHANNEL = "WEBHOOK_EDIT_CHANNEL"
        private const val WEBHOOK_URL_PREFIX = "https://discord.com/api/webhooks/"

        fun launch(context: Context, webhook: ModelWebhook) = AppScreenRouter.launch(
            context,
            WidgetWebhookEdit::class.java,
            Intent()
                .putExtra(ARG_WEBHOOK_ID, webhook.id)
                .putExtra(ARG_GUILD_ID, webhook.guildId)
                .putExtra(ARG_CHANNEL_ID, webhook.channelId)
                .putExtra(ARG_NAME, webhook.name)
                .putExtra(ARG_AVATAR, webhook.avatar)
                .putExtra(ARG_TOKEN, webhook.token)
                .putExtra(ARG_TYPE, webhook.type),
        )
    }
}
