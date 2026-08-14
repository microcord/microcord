package com.discord.utilities.notices

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppLog
import com.discord.i18n.i18nRender
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.notice.WidgetNoticeDialog

/**
 * Turns a [StoreNotices.Dialog] into the modal it describes.
 *
 * `StoreNotices` deliberately knows nothing about views: a queued [StoreNotices.Dialog.Type] is
 * just a name plus a metadata bag, and this is the only place that decides what copy, buttons and
 * click handlers that name means. It is what makes [StoreNotices.dialogPresenter] non-null — until
 * [install] runs, a `Dialog`-backed notice reports that it failed to show and stays queued
 * forever.
 *
 * @see install for the one wiring call this needs.
 */
object NoticeBuilders {

    /**
     * Installs this object as the store's dialog presenter.
     *
     * **Must be called once during app start**, before the first notice can be queued —
     * `App.onCreate` is the natural place, next to the other one-time registrations. It is not done
     * lazily from a builder lookup because `StoreNotices` polls on a background scheduler and a
     * missed presenter is silent: the notice simply never appears.
     */
    @JvmStatic
    fun install() {
        StoreNotices.dialogPresenter = { activity, dialog ->
            showNotice(activity, activity.supportFragmentManager, dialog)
        }
    }

    /**
     * @return `true` when [notice] named a dialog this object knows how to build. `false` leaves
     *   the notice queued rather than silently consuming it.
     */
    @JvmStatic
    fun showNotice(
        context: Context,
        fragmentManager: FragmentManager,
        notice: StoreNotices.Dialog,
    ): Boolean {
        val data = when (notice.type) {
            StoreNotices.Dialog.Type.REQUEST_RATING_MODAL -> requestRatingModal(context)
            StoreNotices.Dialog.Type.DELETE_CONNECTION_MODAL -> deleteConnectionModal(context, notice)
        }
        WidgetNoticeDialog.show(
            fragmentManager = fragmentManager,
            headerText = data.headerText,
            bodyText = data.bodyText,
            okText = data.okText,
            cancelText = data.cancelText,
            listenerMap = data.listenerMap,
            noticeType = notice.type.name,
            aboveLayoutId = data.extraLayoutId,
        )
        return true
    }

    /**
     * "Enjoying Discord?" — the Play Store review prompt.
     *
     * All three outcomes are reported back to `StoreReviewRequest`, including merely *seeing* it
     * ([WidgetNoticeDialog.ON_SHOW]), because the store's cooldown is measured from the moment the
     * user was asked rather than from the moment they answered.
     */
    private fun requestRatingModal(context: Context) = DialogData(
        headerText = context.i18nRender(R.string.rating_request_title),
        bodyText = context.i18nRender(R.string.rating_request_body_android),
        okText = context.i18nRender(R.string.okay),
        cancelText = context.i18nRender(R.string.no_thanks),
        listenerMap = mapOf(
            WidgetNoticeDialog.ON_SHOW to { _: View ->
                StoreStream.getReviewRequest().onReviewRequestShown()
            },
            R.id.notice_ok to { view: View ->
                StoreStream.getReviewRequest().onUserAcceptedRequest()
                UriHandler.directToPlayStore(view.context)
            },
            R.id.CANCEL_BUTTON to { _: View ->
                StoreStream.getReviewRequest().onUserDismissedRequest()
            },
        ),
        extraLayoutId = R.layout.view_review_request_modal_image,
    )

    /**
     * "Disconnect your Steam account?" — raised from the connections settings screen, which is
     * where the platform name and connection id in [StoreNotices.Dialog.metadata] come from.
     *
     * A missing or wrong-typed metadata entry only cancels the delete; it does not throw, because
     * this dialog is built on a background poll and a crash here would take the whole notice queue
     * down with it.
     */
    private fun deleteConnectionModal(
        context: Context,
        notice: StoreNotices.Dialog,
    ) = DialogData(
        headerText = context.i18nRender(
            R.string.disconnect_account_title,
            "name" to notice.metadata?.get(METADATA_PLATFORM_TITLE),
        ),
        bodyText = context.i18nRender(R.string.disconnect_account_body),
        okText = context.i18nRender(R.string.service_connections_disconnect),
        cancelText = context.i18nRender(R.string.cancel),
        listenerMap = mapOf(
            R.id.notice_ok to { _: View ->
                val metadata = notice.metadata
                val platformName = metadata?.get(METADATA_PLATFORM_NAME) as? String
                val connectionId = metadata?.get(METADATA_CONNECTION_ID) as? String
                if (platformName != null && connectionId != null) {
                    StoreStream.getUserConnections().deleteUserConnection(platformName, connectionId)
                } else {
                    AppLog.e(
                        "Delete Connections",
                        Exception("$platformName, $connectionId"),
                    )
                }
            },
        ),
    )

    /**
     * The pieces [WidgetNoticeDialog.show] needs, so each builder can be read as data rather than
     * as a fourteen-argument call.
     */
    private data class DialogData(
        val headerText: CharSequence,
        val bodyText: CharSequence,
        val okText: CharSequence? = null,
        val cancelText: CharSequence? = null,
        val listenerMap: Map<Int, (View) -> Unit>? = null,
        val extraLayoutId: Int? = null,
    )

    private const val METADATA_PLATFORM_TITLE = "platform_title"
    private const val METADATA_PLATFORM_NAME = "platform_name"
    private const val METADATA_CONNECTION_ID = "connection_id"
}
