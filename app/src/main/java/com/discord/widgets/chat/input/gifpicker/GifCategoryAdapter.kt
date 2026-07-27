package com.discord.widgets.chat.input.gifpicker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.recycler.DiffCreator

/** The picker's landing grid: one row type, every tile the same size. */
class GifCategoryAdapter(
    appComponent: AppComponent,
    private val onSelectGifCategory: ((GifCategoryItem) -> Unit)?,
    private val diffCreator: DiffCreator<List<GifCategoryItem>, GifCategoryViewHolder> =
        DiffCreator(appComponent),
) : RecyclerView.Adapter<GifCategoryViewHolder>() {

    private var items: List<GifCategoryItem> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifCategoryViewHolder =
        GifCategoryViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.gif_category_item_view, parent, false),
        )

    override fun onBindViewHolder(holder: GifCategoryViewHolder, position: Int) {
        holder.configure(items[position], onSelectGifCategory)
    }

    fun setItems(newItems: List<GifCategoryItem>) {
        diffCreator.dispatchDiffUpdatesAsync(this, { items = it }, items, newItems)
    }
}
