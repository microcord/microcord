package com.discord.widgets.chat.input.sticker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.recycler.DiffCreator

/**
 * The horizontal tab rail under the sticker grid: "Frequently used", then one tab per guild, then
 * one per owned pack.
 *
 * It is a plain `RecyclerView.Adapter` rather than an `MGRecyclerAdapter` because the rows are three
 * unrelated layouts with no shared payload type — the same shape the reference uses.
 */
class StickerCategoryAdapter(
    private val onGuildClicked: (StickerCategoryItem.GuildItem) -> Unit,
    private val onPackClicked: (StickerCategoryItem.PackItem) -> Unit,
    private val onRecentClicked: () -> Unit,
    private val onSelectedItemAdapterPositionUpdated: (Int) -> Unit,
    appComponent: AppComponent,
    private val diffCreator: DiffCreator<List<StickerCategoryItem>, StickerCategoryViewHolder> =
        DiffCreator(appComponent),
) : RecyclerView.Adapter<StickerCategoryViewHolder>() {

    private var items: List<StickerCategoryItem> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].categoryId

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is StickerCategoryItem.RecentItem -> StickerCategoryItem.TYPE_RECENT
        is StickerCategoryItem.PackItem -> StickerCategoryItem.TYPE_PACK
        is StickerCategoryItem.GuildItem -> StickerCategoryItem.TYPE_GUILD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerCategoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            StickerCategoryItem.TYPE_RECENT -> StickerCategoryViewHolder.Recent(
                inflater.inflate(R.layout.sticker_category_item_recent, parent, false),
            )
            StickerCategoryItem.TYPE_PACK -> StickerCategoryViewHolder.Pack(
                inflater.inflate(R.layout.sticker_category_item_pack, parent, false),
            )
            StickerCategoryItem.TYPE_GUILD -> StickerCategoryViewHolder.Guild(
                inflater.inflate(R.layout.sticker_category_item_guild, parent, false),
            )
            else -> throw IllegalStateException("Invalid Sticker Category Type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: StickerCategoryViewHolder, position: Int) {
        when (val item = items[position]) {
            is StickerCategoryItem.RecentItem ->
                (holder as StickerCategoryViewHolder.Recent).configure(item, onRecentClicked)
            is StickerCategoryItem.PackItem ->
                (holder as StickerCategoryViewHolder.Pack).configure(item, onPackClicked)
            is StickerCategoryItem.GuildItem ->
                (holder as StickerCategoryViewHolder.Guild).configure(item, onGuildClicked)
        }
    }

    /**
     * Diffed off the main thread, then applied — the rail is rebuilt on every store emission and a
     * `notifyDataSetChanged` here would drop the guild icons mid-scroll.
     */
    fun setItems(newItems: List<StickerCategoryItem>) {
        diffCreator.dispatchDiffUpdatesAsync(
            this,
            { applied ->
                items = applied
                applied.indexOfFirst { it.isSelected }
                    .takeIf { it != -1 }
                    ?.let(onSelectedItemAdapterPositionUpdated)
            },
            items,
            newItems,
        )
    }
}
