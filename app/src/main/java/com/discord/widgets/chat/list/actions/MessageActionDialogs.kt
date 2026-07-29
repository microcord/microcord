package com.discord.widgets.chat.list.actions

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppComponent
import com.discord.app.AppToast
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.notice.WidgetNoticeDialog

object MessageActionDialogs {
    fun showDeleteMessageConfirmation(
        fragmentManager: FragmentManager,
        context: Context,
        message: Message,
        onSuccess: () -> Unit,
    ) = WidgetNoticeDialog.show(
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
                    .appSubscribe(MessageActionDialogs::class.java, view.context) {
                        AppToast.show(context, if (isPinned) R.string.message_unpinned else R.string.message_pinned)
                        onSuccess()
                    }
            }),
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
