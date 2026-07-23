package com.discord.views.calls

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R

class VolumeSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val maximumIcon: ImageView
    private val minimumIcon: ImageView
    private val seekBar: SeekBar

    init {
        LayoutInflater.from(context).inflate(R.layout.volume_slider_view, this)
        maximumIcon = requireNotNull(findViewById(R.id.volume_slider_max_vol))
        minimumIcon = requireNotNull(findViewById(R.id.volume_slider_min_vol))
        seekBar = requireNotNull(findViewById(R.id.volume_slider_seek_bar))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.VolumeSliderView).run {
                try {
                    seekBar.max = getInt(R.styleable.VolumeSliderView_vsv_max_volume, 100)
                    val tintResource = getResourceId(R.styleable.VolumeSliderView_vsv_icon_tint, -1)
                    if (tintResource != -1) {
                        val tint = ColorStateList.valueOf(ContextCompat.getColor(context, tintResource))
                        ImageViewCompat.setImageTintList(maximumIcon, tint)
                        ImageViewCompat.setImageTintList(minimumIcon, tint)
                    }
                } finally {
                    recycle()
                }
            }
        }
    }

    fun setOnVolumeChange(onVolumeChanged: (Float, Boolean) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onVolumeChanged(progress.toFloat(), fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }
}
