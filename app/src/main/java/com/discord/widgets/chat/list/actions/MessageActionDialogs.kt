package com.discord.widgets.chat.list.actions

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppComponent
import com.discord.app.AppToast
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isPrivate
import com.discord.i18n.i18nFormat
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.notice.WidgetNoticeDialog

object MessageActionDialogs {

    /** Reference `MAX_PINS_PER_CHANNEL`. */
    const val MAX_PINS_PER_CHANNEL = 250

    /** `TOO_MANY_PINS_IN_CHANNEL`. */
    private const val ABORT_CODE_TOO_MANY_PINS = 30003

    /** Deletes right away, without the dialog, when the "Delete Without Confirmation" flag is on. */
    fun showDeleteMessageConfirmation(
        fragmentManager: FragmentManager,
        context: Context,
        message: Message,
        onSuccess: () -> Unit,
    ) = if (FeatureFlags.skipDeleteConfirmationEnabled()) {
        StoreStream.getMessages().deleteMessage(message)
        onSuccess()
    } else WidgetNoticeDialog.show(
        fragmentManager = fragmentManager,
        headerText = context.getString(R.string.delete_message_title),
        bodyText = context.getString(R.string.delete_message_body),
        okText = context.getString(R.string.delete),
        cancelText = context.getString(R.string.cancel),
        listenerMap = mapOf(R.id.notice_ok to {
            StoreStream.getMessages().deleteMessage(message)
            onSuccess()
        }),
        dialogTheme = R.attr.notice_theme_positive_red,
    )

    fun showPinMessageConfirmation(
        fragmentManager: FragmentManager,
        context: Context,
        message: Message,
        appComponent: AppComponent,
        onSuccess: () -> Unit,
    ) {
        val isPinned = message.pinned == true
        WidgetNoticeDialog.show(
            fragmentManager = fragmentManager,
            headerText = context.getString(if (isPinned) R.string.unpin_message_title else R.string.pin_message_title),
            bodyText = context.getString(if (isPinned) R.string.unpin_message_body else R.string.pin_message_body_mobile),
            okText = context.getString(if (isPinned) R.string.unpin else R.string.pin_confirm),
            cancelText = context.getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { view ->
                val request = if (isPinned) {
                    RestAPI.api.deleteChannelPin(message.channelId, message.id)
                } else {
                    RestAPI.api.addChannelPin(message.channelId, message.id)
                }
                request.restSubscribeOn().ui(appComponent)
                    .appSubscribe(
                        MessageActionDialogs::class.java,
                        view.context,
                        errorHandler = { error ->
                            if (error.code != ABORT_CODE_TOO_MANY_PINS) return@appSubscribe
                            error.showErrorToasts = false
                            showPinLimitReached(fragmentManager, context, message)
                        },
                    ) {
                        AppToast.show(context, if (isPinned) R.string.message_unpinned else R.string.message_pinned)
                        onSuccess()
                    }
            }),
        )
    }

    /**
     * The pin limit is only ever discovered from the server: the reference does not pre-check the
     * count before pinning, so neither does this.
     */
    private fun showPinLimitReached(
        fragmentManager: FragmentManager,
        context: Context,
        message: Message,
    ) {
        val channel = StoreStream.getChannels().getChannel(message.channelId)
        val body = if (channel?.isPrivate() == true) {
            context.i18nFormat(
                R.string.pin_message_too_many_body_private_channel,
                "maxPins" to MAX_PINS_PER_CHANNEL,
            )
        } else {
            context.i18nFormat(
                R.string.pin_message_too_many_body,
                "maxPins" to MAX_PINS_PER_CHANNEL,
                "channelName" to channel?.getDisplayNameOrDefault(context, includePrefix = false).orEmpty(),
            )
        }
        WidgetNoticeDialog.show(
            fragmentManager = fragmentManager,
            headerText = context.getString(R.string.pin_message_too_many_title),
            bodyText = body,
            okText = context.getString(R.string.okay),
        )
    }

    fun showPublishMessageConfirmation(
        fragmentManager: FragmentManager,
        message: Message,
        onSuccess: () -> Unit,
    ) {
        val owner = fragmentManager.fragments.lastOrNull { it is AppComponent && it.context != null }
            ?: error("Publishing a message requires an attached AppComponent")
        showPublishMessageConfirmation(fragmentManager, requireNotNull(owner.context), message, owner as AppComponent, onSuccess)
    }

    fun showPublishMessageConfirmation(
        fragmentManager: FragmentManager,
        context: Context,
        message: Message,
        appComponent: AppComponent,
        onSuccess: () -> Unit,
    ) = WidgetNoticeDialog.show(
        fragmentManager = fragmentManager,
        headerText = context.getString(R.string.news_channel_publish),
        bodyText = context.getString(R.string.publish_followed_news_body),
        okText = context.getString(R.string.news_channel_publish),
        cancelText = context.getString(R.string.cancel),
        listenerMap = mapOf(R.id.notice_ok to { view ->
            RestAPI.api.crosspostMessage(message.channelId, message.id)
                .restSubscribeOn()
                .ui(appComponent)
                .appSubscribe(MessageActionDialogs::class.java, view.context) {
                    AppToast.show(context, R.string.message_published)
                    onSuccess()
                }
        }),
    )
}
