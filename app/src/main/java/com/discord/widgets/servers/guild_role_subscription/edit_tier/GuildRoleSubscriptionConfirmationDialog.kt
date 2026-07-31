package com.discord.widgets.servers.guild_role_subscription.edit_tier

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton

class GuildRoleSubscriptionConfirmationDialog :
    AppDialog(R.layout.dialog_guiild_role_subscription_confirmation) {
    private var confirmCallback: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val callback = confirmCallback ?: return dismiss()
        view.findViewById<TextView>(R.id.guild_role_subscription_confirmation_title).text =
            requireArguments().getString(EXTRA_TITLE).orEmpty()
        view.findViewById<TextView>(R.id.guild_role_subscription_confirmation_subtitle).text =
            requireArguments().getString(EXTRA_SUBTITLE).orEmpty()
        view.findViewById<View>(R.id.cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.confirm).apply {
            text = requireArguments().getString(EXTRA_CONFIRMATION_TEXT).orEmpty()
            if (requireArguments().getBoolean(EXTRA_DESTRUCTIVE)) {
                backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.status_red_500))
            }
            setOnClickListener {
                callback()
                dismiss()
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "INTENT_EXTRA_TITLE"
        private const val EXTRA_SUBTITLE = "INTENT_EXTRA_SUBTITLE"
        private const val EXTRA_CONFIRMATION_TEXT = "INTENT_EXTRA_CONFIRMATION_BUTTON_TEXT"
        private const val EXTRA_DESTRUCTIVE = "INTENT_EXTRA_IS_DESTRUCTIVE_OPERATION"

        fun show(
            fragmentManager: FragmentManager,
            title: String,
            subtitle: String,
            confirmationButtonText: String,
            isDestructiveOperation: Boolean = false,
            confirmCallback: () -> Unit,
        ) {
            GuildRoleSubscriptionConfirmationDialog().apply {
                this.confirmCallback = confirmCallback
                arguments = bundleOf(
                    EXTRA_TITLE to title,
                    EXTRA_SUBTITLE to subtitle,
                    EXTRA_CONFIRMATION_TEXT to confirmationButtonText,
                    EXTRA_DESTRUCTIVE to isDestructiveOperation,
                )
            }.show(fragmentManager, GuildRoleSubscriptionConfirmationDialog::class.java.simpleName)
        }
    }
}
