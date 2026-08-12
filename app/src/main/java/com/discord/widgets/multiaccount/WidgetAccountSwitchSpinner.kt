package com.discord.widgets.multiaccount

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import com.discord.R
import com.discord.app.AppDialog
import androidx.fragment.app.FragmentManager

/**
 * Covers the screen while a switch is in flight.
 *
 * Not dismissable: the stores are torn down and rebuilt underneath, so the UI behind this has no
 * account to describe until the new session's READY lands. [StoreMultiAccountSwitch] always
 * resolves — on success, on refusal, or on its own timeout — so nothing here can strand the user.
 */
class WidgetAccountSwitchSpinner : AppDialog(R.layout.widget_account_switch_spinner) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply { setCanceledOnTouchOutside(false) }

    override fun onStart() {
        super.onStart()
        // A dialog window sizes itself to its content, which leaves a page layout as a small box
        // floating over whichever account's screen is being dismantled behind it.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onResume() {
        super.onResume()
        isCancelable = false
    }

    companion object {
        private val TAG = WidgetAccountSwitchSpinner::class.java.name

        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            WidgetAccountSwitchSpinner().show(fragmentManager, TAG)
        }

        fun dismiss(fragmentManager: FragmentManager) {
            (fragmentManager.findFragmentByTag(TAG) as? WidgetAccountSwitchSpinner)?.dismiss()
        }
    }
}
