package com.discord.widgets.friends

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.restapi.RestAPIAbortCodes
import com.discord.utilities.error.RestApiError
import com.discord.widgets.notice.WidgetNoticeDialog

/**
 * Discord refuses (80013) to befriend someone with no mutual friends or small mutual servers until
 * the user confirms. As the modern client does, the request is sent normally first; on 80013 the
 * user is asked, and confirming resends it with `confirm_stranger_request`.
 */
object StrangerFriendRequest {
    fun isConfirmationRequired(error: RestApiError) =
        error.code == RestAPIAbortCodes.RELATIONSHIP_INVALID_NO_CONFIRMATION

    fun confirm(fragmentManager: FragmentManager, context: Context, onConfirm: () -> Unit) =
        WidgetNoticeDialog.show(
            fragmentManager = fragmentManager,
            headerText = context.getString(R.string.manticord_stranger_request_title),
            bodyText = context.getString(R.string.manticord_stranger_request_body),
            okText = context.getString(R.string.accept),
            cancelText = context.getString(R.string.cancel),
            listenerMap = mapOf(R.id.notice_ok to { _ -> onConfirm() }),
        )
}
