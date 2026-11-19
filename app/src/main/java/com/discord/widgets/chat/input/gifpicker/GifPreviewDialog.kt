package com.discord.widgets.chat.input.gifpicker

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.GifPreviewDialogBinding
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.utilities.images.MGImages

/**
 * A peek at a picker GIF: a card with it large and the link picking it sends, shown while the tile
 * is held and gone when the finger lifts. The tile's own touch stream closes it, so nothing here is touched.
 */
class GifPreviewDialog : AppDialog(R.layout.gif_preview_dialog) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // A peek only: the held tile keeps the gesture, and nothing here may take it or the keyboard.
        dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val args = requireArguments()
        val src = args.getString(ARG_SRC).orEmpty()
        val url = args.getString(ARG_URL).orEmpty()
        val binding = GifPreviewDialogBinding.bind(view)

        // As wide as the screen allows, the GIF's own shape, never taller than most of the screen.
        val metrics = resources.displayMetrics
        val aspect = args.getInt(ARG_HEIGHT).toFloat() / args.getInt(ARG_WIDTH).coerceAtLeast(1)
        val maxHeight = metrics.heightPixels * MAX_HEIGHT_FRACTION
        var width = metrics.widthPixels * WIDTH_FRACTION
        if (aspect > 0f && width * aspect > maxHeight) width = maxHeight / aspect
        val height = if (aspect > 0f) width * aspect else width
        binding.gifPreviewImage.updateLayoutParams {
            this.width = width.toInt()
            this.height = height.toInt()
        }
        MGImages.setImage(binding.gifPreviewImage, ModelGif.previewUrls(src, width.toInt(), height.toInt()))

        binding.gifPreviewLink.updateLayoutParams { this.width = width.toInt() }
        binding.gifPreviewUrl.text = url.substringAfter("://").removePrefix("www.")
    }

    override fun onStart() {
        super.onStart()
        // A dialog window is capped narrower than the screen, which ate the card's padding; the
        // card centres itself in a full-screen one instead.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    companion object {
        private const val ARG_SRC = "src"
        private const val ARG_URL = "url"
        private const val ARG_WIDTH = "width"
        private const val ARG_HEIGHT = "height"
        private const val WIDTH_FRACTION = 0.7f
        private const val MAX_HEIGHT_FRACTION = 0.6f

        /** @return the shown preview, for the tile to dismiss on release. */
        fun show(fragmentManager: FragmentManager, gif: ModelGif): GifPreviewDialog =
            GifPreviewDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SRC, gif.gifImageUrl)
                    putString(ARG_URL, gif.tenorGifUrl)
                    putInt(ARG_WIDTH, gif.width)
                    putInt(ARG_HEIGHT, gif.height)
                }
                show(fragmentManager, GifPreviewDialog::class.java.simpleName)
            }
    }
}
