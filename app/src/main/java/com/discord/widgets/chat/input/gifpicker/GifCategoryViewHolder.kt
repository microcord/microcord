package com.discord.widgets.chat.input.gifpicker

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.images.MGImages
import com.discord.widgets.chat.input.gifpicker.GifCategoryItem.Standard
import com.discord.widgets.chat.input.gifpicker.GifCategoryItem.Trending
import com.facebook.drawee.view.SimpleDraweeView

/**
 * One tile on the picker's landing grid.
 *
 * The trending tile is the only one with an icon; every other tile is preview image plus the
 * category's own name.
 */
class GifCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val preview: SimpleDraweeView = itemView.findViewById(R.id.gif_category_item_preview)
    private val icon: ImageView = itemView.findViewById(R.id.gif_category_item_icon)
    private val title: TextView = itemView.findViewById(R.id.gif_category_item_title)

    init {
        itemView.setOnTouchListener(ViewScalingOnTouchListener(PRESS_SCALE))
    }

    fun configure(
        gifCategoryItem: GifCategoryItem,
        onSelectGifCategory: ((GifCategoryItem) -> Unit)?,
    ) {
        when (gifCategoryItem) {
            is Standard -> {
                setPreviewImage(gifCategoryItem.gifCategory.gifPreviewUrl)
                title.text = gifCategoryItem.gifCategory.categoryName
                icon.visibility = View.GONE
            }

            is Trending -> {
                setPreviewImage(gifCategoryItem.gifPreviewUrl)
                title.text = itemView.resources.getString(R.string.gif_picker_result_type_trending_gifs)
                icon.visibility = View.VISIBLE
                icon.setImageDrawable(
                    ResourcesCompat.getDrawable(itemView.resources, R.drawable.ic_analytics_16dp, null),
                )
            }
        }
        itemView.setOnClickListener { onSelectGifCategory?.invoke(gifCategoryItem) }
    }

    /**
     * Requests the preview at the tile's own pixel size so Fresco decodes a bitmap that fits,
     * rather than a full-size frame that has to be scaled down on every row.
     */
    private fun setPreviewImage(imageUrl: String) {
        val params = preview.layoutParams
        MGImages.setImage(preview, imageUrl, params.width, params.height)
    }

    private companion object {
        const val PRESS_SCALE = 0.9f
    }
}
