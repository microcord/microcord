package com.discord.widgets.hubs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.mg_recycler.MGRecyclerAdapter

/**
 * A single-choice list in a bottom sheet, used by [WidgetHubDescription] to pick a directory
 * category. Binds `R.layout.widget_radio_selector_bottom_sheet`.
 *
 * Tapping a row reports its id and leaves the sheet open; the caller decides what to do, which is why
 * [onSelected] is a plain callback set by [show] rather than a fragment result.
 */
class WidgetRadioSelectorBottomSheet : AppBottomSheet() {

    private val args: RadioSelectorItems by hubDialogArgs()

    var onSelected: ((Int) -> Unit)? = null

    private var adapter: RadioSelectorAdapter? = null

    override fun getContentViewResId(): Int = R.layout.widget_radio_selector_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val listAdapter = MGRecyclerAdapter.configure(
            RadioSelectorAdapter(recycler) { id -> onSelected?.invoke(id) },
        )
        adapter = listAdapter
        listAdapter.setItems(args.items)
        view.findViewById<TextView>(R.id.radio_selector_bottom_sheet_title).text = args.title
    }

    override fun onDestroyView() {
        adapter?.unsubscribeFromUpdates()
        adapter = null
        super.onDestroyView()
    }

    companion object {
        fun show(
            fragmentManager: FragmentManager,
            items: RadioSelectorItems,
            onSelected: (Int) -> Unit,
        ) {
            WidgetRadioSelectorBottomSheet().apply {
                arguments = items.toArgsBundle()
                this.onSelected = onSelected
                show(fragmentManager, WidgetRadioSelectorBottomSheet::class.java.name)
            }
        }
    }
}
