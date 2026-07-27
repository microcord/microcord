package com.discord.widgets.chat.input.gifpicker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.MarginLayoutParamsCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.recycler.DiffCreator
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem

/**
 * The staggered GIF grid, shared by the category screen and the search screen.
 *
 * The column *width* is passed in rather than measured here because the holder needs it to compute
 * each tile's height before the tile is laid out — see [GifViewHolder.Gif.configure].
 */
class GifAdapter(
    appComponent: AppComponent,
    private val onSelectGif: ((GifItem) -> Unit)? = null,
    private val columnWidthPx: Int,
    private val onSelectSuggestedTerm: ((String) -> Unit)? = null,
    private val diffCreator: DiffCreator<List<GifAdapterItem>, GifViewHolder> = DiffCreator(appComponent),
) : RecyclerView.Adapter<GifViewHolder>() {

    private var items: List<GifAdapterItem> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = items[position].type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (GifAdapterItem.ViewType.fromInt(viewType)) {
            GifAdapterItem.ViewType.VIEW_TYPE_GIF_ITEM ->
                GifViewHolder.Gif(inflater.inflate(R.layout.gif_item_view, parent, false))

            GifAdapterItem.ViewType.VIEW_TYPE_GIF_SEARCH_TERMS_EMPTY_RESULTS,
            GifAdapterItem.ViewType.VIEW_TYPE_GIF_SEARCH_TERMS_NON_EMPTY_RESULTS,
            ->
                GifViewHolder.SuggestedTerms(inflater.inflate(R.layout.gif_suggested_term_view, parent, false))
        }
    }

    override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
        when (val item = items[position]) {
            is GifItem -> (holder as GifViewHolder.Gif).configure(item, columnWidthPx, onSelectGif)

            is GifAdapterItem.SuggestedTermsItem.SuggestedTermsEmptyResults ->
                (holder as GifViewHolder.SuggestedTerms).configure(item, onSelectSuggestedTerm)

            is GifAdapterItem.SuggestedTermsItem.SuggestedTermsNonEmptyResults ->
                (holder as GifViewHolder.SuggestedTerms).configure(item, onSelectSuggestedTerm)
        }
    }

    fun setItems(newItems: List<GifAdapterItem>) {
        diffCreator.dispatchDiffUpdatesAsync(this, { items = it }, items, newItems)
    }

    fun clearItems() = setItems(emptyList())

    companion object {
        private const val DEFAULT_COLUMN_COUNT = 2
        private const val MIN_COLUMN_WIDTH_DP = 164

        /** At least two columns, and as many more as fit at [MIN_COLUMN_WIDTH_DP] each. */
        fun calculateColumnCount(recyclerView: RecyclerView): Int {
            val metrics = recyclerView.resources.displayMetrics
            val minColumnWidthPx = (MIN_COLUMN_WIDTH_DP * metrics.density).toInt()
            return maxOf(DEFAULT_COLUMN_COUNT, maxOf(metrics.widthPixels, 1) / minColumnWidthPx)
        }

        /** Screen width less the recycler's own margins, padding and inter-column gaps. */
        fun calculateColumnWidth(recyclerView: RecyclerView, numColumns: Int, columnSpacePx: Int): Int {
            val screenWidth = recyclerView.resources.displayMetrics.widthPixels
            val params = recyclerView.layoutParams as? ViewGroup.MarginLayoutParams
            val marginStart = params?.let(MarginLayoutParamsCompat::getMarginStart) ?: 0
            val marginEnd = params?.let(MarginLayoutParamsCompat::getMarginEnd) ?: 0
            val available =
                screenWidth - marginStart - marginEnd -
                    recyclerView.paddingStart - recyclerView.paddingEnd
            return ((available - (numColumns - 1) * columnSpacePx).toFloat() / numColumns).toInt()
        }
    }
}
