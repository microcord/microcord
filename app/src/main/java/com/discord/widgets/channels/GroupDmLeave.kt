package com.discord.widgets.channels

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.i18n.i18nRender
import com.discord.utilities.dimen.dpToPixels
import com.discord.views.CheckedSetting
import com.discord.widgets.notice.WidgetNoticeDialog

/** Confirms leaving a group DM, offering to do it without posting the system message. */
object GroupDmLeave {

    fun confirm(
        context: Context,
        fragmentManager: FragmentManager,
        groupName: CharSequence,
        onConfirmed: (silent: Boolean) -> Unit,
    ) = WidgetNoticeDialog.show(
        fragmentManager = fragmentManager,
        headerText = context.i18nRender(R.string.leave_group_dm_title, "name" to groupName),
        bodyText = context.i18nRender(R.string.leave_group_dm_body, "name" to groupName),
        okText = context.getString(R.string.leave_group_dm),
        cancelText = context.getString(R.string.cancel),
        listenerMap = mapOf(
            // The body and the row both carry 16dp of their own, which stacks into a large gap.
            WidgetNoticeDialog.ON_SHOW to { root: View ->
                val horizontal = dpToPixels(16)
                root.findViewById<TextView>(R.id.notice_body_text)
                    ?.setPadding(horizontal, dpToPixels(4), horizontal, 0)
                root.findViewById<View>(R.id.setting_container)
                    ?.setPadding(horizontal, 0, horizontal, dpToPixels(8))
            },
            R.id.notice_ok to { view: View ->
                val silent = view.rootView
                    .findViewById<CheckedSetting>(R.id.leave_group_dm_silent)
                    ?.isChecked == true
                onConfirmed(silent)
            },
        ),
        belowLayoutId = R.layout.widget_leave_group_dm_silent,
        dialogTheme = R.attr.notice_theme_positive_red,
    )
}
