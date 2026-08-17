package com.discord.widgets.chat.input.gifpicker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.images.MGImages
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.GifItem
import com.discord.widgets.chat.input.gifpicker.GifAdapterItem.SuggestedTermsItem
import com.discord.views.VitoImageView
import com.google.android.flexbox.FlexboxLayout

/** The two row shapes the GIF grid can hold. */
sealed class GifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    /**
     * A GIF tile.
     *
     * The tile's height is derived from the GIF's own aspect ratio against the column width the
     * adapter measured, which is what gives the staggered grid its ragged bottom edge.
     */
    class Gif(itemView: View) : GifViewHolder(itemView) {

        private val image: VitoImageView = itemView.findViewById(R.id.gif_item_image)
        private val favorite: ImageView = itemView.findViewById(R.id.gif_item_favorite)

        init {
            itemView.setOnTouchListener(ViewScalingOnTouchListener(PRESS_SCALE))
        }

        fun configure(
            gifItem: GifItem,
            columnWidthPx: Int,
            onSelectGif: ((GifItem) -> Unit)? = null,
            onToggleFavorite: ((GifItem) -> Unit)? = null,
        ) {
            val gif = gifItem.gif
            val aspect = if (gif.width == 0) 1f else gif.height.toFloat() / gif.width
            itemView.layoutParams.height = (columnWidthPx * aspect).toInt()
            MGImages.setImage(image, listOf(gif.gifImageUrl))
            favorite.visibility = View.VISIBLE
            favorite.alpha = if (gifItem.isFavorite) 1f else 0.7f
            favorite.setColorFilter(
                ContextCompat.getColor(
                    favorite.context,
                    if (gifItem.isFavorite) R.color.status_yellow else R.color.white,
                ),
            )
            favorite.contentDescription = favorite.context.getString(
                if (gifItem.isFavorite) R.string.gif_tooltip_remove_from_favorites
                else R.string.gif_tooltip_favorited_picker_button,
            )
            favorite.setOnClickListener {
                onToggleFavorite?.invoke(gifItem)
                Toast.makeText(
                    itemView.context,
                    if (gifItem.isFavorite) R.string.gif_tooltip_remove_from_favorites
                    else R.string.gif_tooltip_favorited_picker_button,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            itemView.setOnClickListener { onSelectGif?.invoke(gifItem) }
            itemView.setOnLongClickListener(null)
        }

        private companion object {
            const val PRESS_SCALE = 0.9f
        }
    }

    /**
     * The related/trending search chips.
     *
     * Chip views are recycled in place rather than rebuilt: the flexbox keeps whatever children it
     * already has, adds any it is short of, and trims the tail. That matters because this holder is
     * re-bound on every keystroke of the search box.
     */
    class SuggestedTerms(itemView: View) : GifViewHolder(itemView) {

        private val flexBox: FlexboxLayout = itemView.findViewById(R.id.gif_search_suggested_terms_flex_box)
        private val title: TextView = itemView.findViewById(R.id.gif_search_suggested_terms_icon_text)

        /** Empty results: centred, full-height, with the "no results" illustration above the title. */
        fun configure(
            termsItem: SuggestedTermsItem.SuggestedTermsEmptyResults,
            onTermSelected: ((String) -> Unit)?,
        ) {
            title.setCompoundDrawablesCompat(
                top = itemView.getThemedDrawableRes(R.attr.theme_expression_tray_search_icon_empty),
            )
            configureSearchTerms(termsItem, onTermSelected, ViewGroup.LayoutParams.MATCH_PARENT)
            itemView.setPadding(itemView.paddingLeft, 0, itemView.paddingRight, 0)
        }

        /** Non-empty results: a footer under the grid, no illustration, generous vertical padding. */
        fun configure(
            termsItem: SuggestedTermsItem.SuggestedTermsNonEmptyResults,
            onTermSelected: ((String) -> Unit)?,
        ) {
            title.setCompoundDrawablesCompat(start = null, top = null, end = null, bottom = null)
            configureSearchTerms(termsItem, onTermSelected, ViewGroup.LayoutParams.WRAP_CONTENT)
            itemView.setPadding(
                itemView.paddingLeft,
                dpToPixels(TOP_PADDING_DP),
                itemView.paddingRight,
                dpToPixels(BOTTOM_PADDING_DP),
            )
        }

        private fun configureSearchTerms(
            termsItem: SuggestedTermsItem,
            onTermSelected: ((String) -> Unit)?,
            layoutHeight: Int,
        ) {
            val terms = termsItem.terms
            title.text = itemView.i18nFormat(termsItem.titleResId)

            terms.forEachIndexed { index, suggestion ->
                if (index >= flexBox.childCount) {
                    flexBox.addView(
                        LayoutInflater.from(itemView.context)
                            .inflate(R.layout.gif_search_empty_state_text_box_view, flexBox, false),
                        index,
                    )
                }
                (flexBox.getChildAt(index) as TextView).apply {
                    text = suggestion
                    setOnClickListener { onTermSelected?.invoke(suggestion) }
                }
            }
            for (index in flexBox.childCount - 1 downTo terms.size) {
                flexBox.removeViewAt(index)
            }

            // The block always spans every column; the staggered layout manager needs to be told.
            itemView.layoutParams = StaggeredGridLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                layoutHeight,
            ).apply { isFullSpan = true }
        }


        private companion object {
            const val TOP_PADDING_DP = 32
            const val BOTTOM_PADDING_DP = 40
        }
    }
}
