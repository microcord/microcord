package com.discord.widgets.chat.list

import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.stores.StoreStream

/** Shows a favorite star over chat media for [gif], or hides the button when it is null. */
internal fun ImageView.bindGifFavorite(gif: ModelGif?) {
    if (gif == null) {
        visibility = View.GONE
        setOnClickListener(null)
        return
    }
    val favorites = StoreStream.getMediaFavorites()
    var isFavorite = favorites.isFavoriteGif(gif)

    fun render() {
        alpha = if (isFavorite) 1f else 0.7f
        setColorFilter(ContextCompat.getColor(context, if (isFavorite) R.color.status_yellow else R.color.white))
        contentDescription = context.getString(
            if (isFavorite) R.string.gif_tooltip_remove_from_favorites else R.string.gif_tooltip_favorited_picker_button,
        )
    }

    visibility = View.VISIBLE
    render()
    setOnClickListener {
        favorites.setFavoriteGif(gif, !isFavorite)
        isFavorite = !isFavorite
        render()
        Toast.makeText(context, contentDescription, Toast.LENGTH_SHORT).show()
    }
}
