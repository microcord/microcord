package com.discord.widgets.chat.input.gifpicker

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.widgets.chat.input.expression.WidgetExpressionPickerSheet

/**
 * [WidgetGifPickerSearch] presented as a bottom sheet, which is how the expression tray's search
 * button opens GIF search.
 *
 * The sheet is sized to 90 % of the screen on commit rather than in XML because the child fragment
 * has to be attached first — the search field takes focus and opens the keyboard immediately, and a
 * `wrap_content` sheet would collapse to the field alone.
 */
class WidgetGifPickerSheet : WidgetExpressionPickerSheet() {

    private var container: View? = null
    private var gifPickerFragment: WidgetGifPickerSearch? = null
    private var onGifSelected: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_gif_picker_sheet

    fun setOnGifSelected(onGifSelected: (() -> Unit)?) {
        this.onGifSelected = onGifSelected
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        container = view

        val fragment = WidgetGifPickerSearch()
        gifPickerFragment = fragment
        fragment.setOnGifSelected {
            onGifSelected?.invoke()
            dismiss()
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.gif_search_sheet_content, fragment, fragment.javaClass.simpleName)
            .runOnCommit {
                container?.layoutParams?.height =
                    (resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt()
            }
            .commit()
    }

    override fun onCancel(dialog: DialogInterface) {
        // Clear before the base class notifies its own onCancel, so a re-open starts empty.
        gifPickerFragment?.clearSearchBar()
        super.onCancel(dialog)
    }

    companion object {
        private const val SHEET_HEIGHT_FRACTION = 0.9

        fun show(
            fragmentManager: FragmentManager,
            onGifSelected: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
        ): WidgetGifPickerSheet = WidgetGifPickerSheet().apply {
            show(fragmentManager, WidgetGifPickerSheet::class.java.simpleName)
            setOnGifSelected(onGifSelected)
            setOnCancel(onCancel)
        }
    }
}
