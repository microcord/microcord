package com.discord.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.utilities.color.ColorCompat
import java.util.Locale
import kotlin.math.roundToInt

class VoiceUserLimitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val current: TextView
    private val maximum: TextView
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        color = ColorCompat.getThemedColor(context, R.attr.colorBackgroundMobilePrimary)
    }
    private val slantWidth = (6 * resources.displayMetrics.density).roundToInt()
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val videoDrawable by lazy(LazyThreadSafetyMode.NONE) {
        AppCompatResources.getDrawable(context, R.drawable.ic_videocam_white_12dp)?.mutate()?.also {
            DrawableCompat.setTint(it, ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal))
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.voice_user_limit_view, this)
        current = requireNotNull(findViewById(R.id.voice_user_limit_current))
        maximum = requireNotNull(findViewById(R.id.voice_user_limit_max))
    }

    fun configure(current: Int, max: Int, isVideo: Boolean) {
        this.current.text = formatCount(current)
        maximum.text = formatCount(max)
        this.current.setCompoundDrawablesWithIntrinsicBounds(
            if (isVideo) videoDrawable else null,
            null,
            null,
            null,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val edge = current.width.toFloat()
        path.moveTo(edge, height.toFloat())
        path.lineTo(edge + slantWidth, 0f)
        path.lineTo(edge, 0f)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
    }

    private fun formatCount(value: Int) =
        if (value < 100) String.format(Locale.getDefault(), "%02d", value) else value.toString()

}
