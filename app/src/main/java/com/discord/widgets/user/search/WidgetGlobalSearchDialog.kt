package com.discord.widgets.user.search

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appActivityViewModels
import com.discord.stores.StoreStream
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui

/**
 * Modal host for the quick switcher.
 *
 * It owns nothing but the window: [WidgetGlobalSearch] is inflated as a child fragment and does all
 * the work. Two things close it — the list publishing a dismiss event after a selection, and the
 * selected channel changing underneath it (which is what happens when a notification or deep link
 * navigates while the switcher is open).
 */
class WidgetGlobalSearchDialog : AppDialog(R.layout.widget_global_search_dialog) {

    private val dismissViewModel: WidgetGlobalSearchDismissModel by appActivityViewModels {
        WidgetGlobalSearchDismissModel()
    }

    private val fragment: WidgetGlobalSearch?
        get() = childFragmentManager.findFragmentById(R.id.widget_global_search_container)
            as? WidgetGlobalSearch

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, R.style.AppTheme_GlobalSearchDialog)
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            if (!StoreStream.getAccessibility().isReducedMotionEnabled()) {
                attributes.windowAnimations = R.style.UiKit_QuickSwitcher_Animation
            }
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Resize for the keyboard and show it immediately: the switcher is a typing surface.
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }
    }

    override fun dismiss() {
        super.dismiss()
        fragment?.onDismiss()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        dismissViewModel.dismissEvents.ui(this).subscribe { dismiss() }
        // `skip(1)` drops the channel that was already selected when the switcher opened.
        StoreStream.getChannelsSelected().observeId()
            .computationLatest()
            .distinctUntilChanged()
            .skip(1)
            .ui(this)
            .subscribe { dismiss() }
    }

    companion object {
        private const val TAG = "WidgetGlobalSearchDialog"

        fun show(fragmentManager: FragmentManager, searchText: String? = null) {
            dismiss(fragmentManager)
            WidgetGlobalSearchDialog()
                .apply {
                    arguments = Bundle().apply {
                        putString(WidgetGlobalSearch.EXTRA_SEARCH_TEXT, searchText)
                    }
                }
                .show(fragmentManager, TAG)
        }

        /**
         * Source-compatible entry point for callers that still pass a "new group DM" flag.
         *
         * `false` opens the quick switcher — which is what the DM search button should have been
         * doing all along — and `true` opens [WidgetGroupDmPickerDialog]. `WidgetChannelsList` is
         * the only caller; it should call the two dialogs directly and this overload should then go.
         */
        @Deprecated(
            "Call show(fragmentManager) or WidgetGroupDmPickerDialog.show(fragmentManager)",
            ReplaceWith("show(fragmentManager)"),
        )
        fun show(fragmentManager: FragmentManager, newGroupDm: Boolean) {
            if (newGroupDm) {
                WidgetGroupDmPickerDialog.show(fragmentManager)
            } else {
                show(fragmentManager, searchText = null)
            }
        }

        fun dismiss(fragmentManager: FragmentManager) {
            (fragmentManager.findFragmentByTag(TAG) as? WidgetGlobalSearchDialog)?.dismiss()
        }

        /**
         * Opens the switcher pre-filled from a `/query/` App Action deep link.
         *
         * The link names a *thing* — a channel, a server, a person — and the switcher is how the
         * client resolves a name to a destination, so the route is turned back into the prefixed
         * text a user would have typed. `channelName` may arrive as `general in My Server`; only the
         * channel part carries the `#`, the server name follows it as plain text.
         *
         * Returns false for a route naming nothing resolvable, so the caller can leave the intent
         * unconsumed.
         *
         * Not handled here: a query carrying `messageText`, which the reference forwards to the
         * share sheet so the named destination receives the message. `WidgetIncomingShare` has no
         * text-only entry point in this project yet, so such a link opens the switcher instead of
         * pre-composing — the destination is still reachable, the text is dropped.
         */
        fun showForQuery(fragmentManager: FragmentManager, route: DeepLinkRoute.Query): Boolean {
            val searchText = when (route.type) {
                "channel" -> {
                    val parts = route.channelName.orEmpty().split(CHANNEL_IN_SERVER, limit = 2)
                    val channelName = parts.getOrNull(0).orEmpty()
                    val serverName = parts.getOrNull(1).orEmpty()
                    "#$channelName" + if (serverName.isNotEmpty()) " $serverName" else ""
                }
                "server" -> "*${route.serverName.orEmpty()}"
                "user" -> {
                    val userName = route.userName?.takeIf(String::isNotEmpty)
                        ?: route.alternateUserName.orEmpty()
                    if (userName.isEmpty()) return false else "@$userName"
                }
                else -> return false
            }
            show(fragmentManager, searchText)
            return true
        }

        /** The separator App Actions uses inside `channelName`, as in `general in My Server`. */
        private const val CHANNEL_IN_SERVER = " in "
    }
}
