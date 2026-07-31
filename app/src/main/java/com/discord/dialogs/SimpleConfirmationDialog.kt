package com.discord.dialogs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.google.android.material.button.MaterialButton

/**
 * A title, a body and two buttons — the app's plain "are you sure?" modal.
 *
 * The confirm action is not parcelled, so a dialog restored after process death still dismisses
 * correctly but no longer runs it. That is the reference's behaviour: destructive confirmations are
 * expected to be re-initiated rather than silently completed on restore.
 */
class SimpleConfirmationDialog : AppDialog(R.layout.simple_confirmation_dialog) {

    var positiveClickListener: View.OnClickListener? = null

    private val args: SimpleConfirmationDialogArgs
        get() = requireNotNull(argumentsOrDefault.getParcelable(ARG_KEY)) {
            "Missing ${SimpleConfirmationDialogArgs::class.java.simpleName} for key $ARG_KEY!"
        }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        requireDialog().setCanceledOnTouchOutside(true)

        val view = requireView()
        val positive = view.findViewById<MaterialButton>(R.id.simple_confirmation_dialog_positive)
        positive.text = args.positiveButtonText
        positive.setOnClickListener {
            positiveClickListener?.onClick(it)
            dismiss()
        }

        val negative = view.findViewById<MaterialButton>(R.id.simple_confirmation_dialog_negative)
        negative.text = args.negativeButtonText
        negative.setOnClickListener { dismiss() }

        view.findViewById<TextView>(R.id.simple_confirmation_dialog_title)
            .setTextAndVisibilityBy(args.title)
        view.findViewById<TextView>(R.id.simple_confirmation_dialog_description)
            .setTextAndVisibilityBy(args.description)
    }

    companion object {
        private const val ARG_KEY = "intent_args_key"

        fun show(
            fragmentManager: FragmentManager,
            args: SimpleConfirmationDialogArgs,
            positiveClickListener: View.OnClickListener,
        ): SimpleConfirmationDialog = SimpleConfirmationDialog().apply {
            arguments = Bundle().apply { putParcelable(ARG_KEY, args) }
            this.positiveClickListener = positiveClickListener
            show(fragmentManager, SimpleConfirmationDialog::class.java.name)
        }
    }
}
