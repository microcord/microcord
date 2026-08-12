package com.discord.widgets.chat.input.sticker

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppBottomSheet

/**
 * Hosts [WidgetStickerPicker] as a bottom sheet — the search-first surface, opened from the
 * expression tray's search affordance and from the sticker popout's "View Sticker Pack".
 *
 * The reference derives this from `WidgetExpressionPickerSheet`, which also carries the GIF and
 * emoji siblings; that package has not been reconstructed, so this extends [AppBottomSheet] and
 * reproduces the two behaviours it inherited: no collapsed state, and 90% of the screen height.
 */
class WidgetStickerPickerSheet : AppBottomSheet(), StickerPickerListener {

    private var stickerPickerFragment: WidgetStickerPicker? = null
    private var stickerPickerListenerDelegate: StickerPickerListener? = null
    private var onCancel: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_sticker_picker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()

        val packId = argumentsOrDefault
            .getLong(WidgetStickerPicker.EXTRA_STICKER_PACK_ID, WidgetStickerPicker.NO_PACK)
            .takeIf { it != WidgetStickerPicker.NO_PACK }
        val picker = WidgetStickerPicker.newInstance(
            mode = StickerPickerMode.BOTTOM_SHEET,
            initialStickerPackId = packId,
            searchText = argumentsOrDefault.getString(WidgetStickerPicker.EXTRA_SEARCH_TEXT),
            selectionOnly = argumentsOrDefault.getBoolean(WidgetStickerPicker.EXTRA_SELECTION_ONLY),
        )
        picker.stickerPickerListener = this
        stickerPickerFragment = picker

        childFragmentManager.beginTransaction()
            .replace(R.id.sticker_sheet_sticker_picker_content, picker, PICKER_TAG)
            .runOnCommit {
                // The sheet has to be given a height explicitly: its only child is a
                // FragmentContainerView, which measures to nothing until the picker inflates.
                // Posted because the content view has no layout params until it is attached.
                view.post {
                    val params = view.layoutParams ?: ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    params.height =
                        (resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt()
                    view.layoutParams = params
                }
            }
            .commit()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        stickerPickerFragment?.clearSearchInput()
        onCancel?.invoke()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Leave the shared, activity-scoped view model on "Frequently used" so the tray behind this
        // sheet does not reopen on whatever pack was last browsed here.
        stickerPickerFragment?.selectCategoryById(StickerPickerViewModel.RECENT_SELECTED_ID)
    }

    override fun onStickerPicked(sticker: Sticker) {
        stickerPickerListenerDelegate?.onStickerPicked(sticker)
        dismiss()
    }

    fun scrollToPack(packId: Long?) {
        stickerPickerFragment?.scrollToPack(packId)
    }

    companion object {
        private const val PICKER_TAG = "WidgetStickerPicker"
        private const val SHEET_HEIGHT_FRACTION = 0.9

        fun show(
            fragmentManager: FragmentManager,
            stickerPickerListener: StickerPickerListener?,
            initialStickerPackId: Long? = null,
            searchText: String? = null,
            selectionOnly: Boolean = false,
            onCancel: (() -> Unit)? = null,
        ): WidgetStickerPickerSheet = WidgetStickerPickerSheet().apply {
            this.stickerPickerListenerDelegate = stickerPickerListener
            this.onCancel = onCancel
            arguments = Bundle().apply {
                initialStickerPackId?.let {
                    putLong(WidgetStickerPicker.EXTRA_STICKER_PACK_ID, it)
                }
                searchText?.let { putString(WidgetStickerPicker.EXTRA_SEARCH_TEXT, it) }
                putBoolean(WidgetStickerPicker.EXTRA_SELECTION_ONLY, selectionOnly)
            }
            show(fragmentManager, WidgetStickerPickerSheet::class.java.simpleName)
        }
    }
}
