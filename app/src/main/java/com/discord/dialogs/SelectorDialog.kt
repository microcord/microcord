package com.discord.dialogs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.utilities.views.SimpleRecyclerAdapter.Companion.addThemedDivider
import com.google.android.material.button.MaterialButton

/**
 * A titled list of plain text options; picking one reports its index and closes.
 *
 * The callback is a plain lambda rather than a bundle argument, so it cannot survive process death —
 * which is why the dialog dismisses itself in [onPause] instead of trying to restore. That is the
 * reference's behaviour, and it is the right one for a transient chooser: coming back to a dialog
 * whose selection would go nowhere is worse than not coming back at all.
 */
class SelectorDialog : AppDialog(R.layout.dialog_simple_selector) {

    var onSelectedListener: ((Int) -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.dialog_title).text =
            argumentsOrDefault.getCharSequence(ARG_TITLE)
        view.findViewById<MaterialButton>(R.id.dialog_cancel).setOnClickListener { dismiss() }

        val options = argumentsOrDefault.getCharSequenceArray(ARG_OPTIONS) ?: return
        view.findViewById<MaxHeightRecyclerView>(R.id.dialog_selections).apply {
            adapter = SimpleRecyclerAdapter(options.toList()) { inflater, parent ->
                OptionViewHolder(
                    inflater.inflate(R.layout.dialog_simple_selector_item, parent, false) as TextView,
                )
            }
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
            addThemedDivider()
        }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private inner class OptionViewHolder(
        private val label: TextView,
    ) : SimpleRecyclerAdapter.ViewHolder<CharSequence>(label) {

        override fun bind(data: CharSequence) {
            label.text = data
            label.setOnClickListener {
                onSelectedListener?.invoke(bindingAdapterPosition)
                dismiss()
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "INTENT_DIALOG_TITLE"
        private const val ARG_OPTIONS = "INTENT_DIALOG_OPTIONS"

        /** @param onSelectedListener receives the index of the chosen option. */
        fun show(
            fragmentManager: FragmentManager,
            title: CharSequence,
            options: Array<CharSequence>,
            onSelectedListener: (Int) -> Unit,
        ): SelectorDialog = SelectorDialog().apply {
            arguments = Bundle().apply {
                putCharSequence(ARG_TITLE, title)
                putCharSequenceArray(ARG_OPTIONS, options)
            }
            this.onSelectedListener = onSelectedListener
            show(fragmentManager, SelectorDialog::class.java.name)
        }
    }
}
