package com.lytefast.flexinput.fragment

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.R as MaterialR
import com.lytefast.flexinput.R as FlexR

open class FlexInputFragmentCompat : FlexInputFragment() {
    override fun onAddToggle() {
        hideEmojiTray()
        val token = activity?.currentFocus?.windowToken ?: view?.windowToken
        if (token != null) {
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
        try {
            val dialog = MaterialAddContentDialogFragment()
            dialog.show(childFragmentManager.beginTransaction(), ADD_CONTENT_FRAG_TAG)
            childFragmentManager.executePendingTransactions()
            dialog.dialog?.setOnDismissListener {
                if (dialog.isAdded && !dialog.isDetached) dialog.dismissAllowingStateLoss()
                if (!isAdded || isHidden) return@setOnDismissListener
                requestFocus()
                view?.findViewById<View>(FlexR.id.attachment_preview_container)?.visibility =
                    if (attachmentPreviewAdapter.itemCount > 0) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            android.util.Log.d("FlexInputFragment", "Could not open AddContentDialogFragment", e)
        }
    }
}

class MaterialAddContentDialogFragment : AddContentDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = object : AppCompatDialog(requireContext(), FlexR.style.FlexInput_DialogWhenLarge) {
            override fun show() {
                super.show()
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setWindowAnimations(MaterialR.style.Animation_MaterialComponents_BottomSheetDialog)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return dialog
    }

    override fun show(transaction: FragmentTransaction, tag: String?): Int {
        transaction.setCustomAnimations(
            MaterialR.anim.mtrl_bottom_sheet_slide_in,
            MaterialR.anim.mtrl_bottom_sheet_slide_out,
        )
        transaction.add(this, tag)
        return transaction.commit()
    }
}
