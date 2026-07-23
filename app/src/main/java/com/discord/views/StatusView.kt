package com.discord.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import com.discord.R
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.models.presence.Presence

class StatusView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {
    private var cornerRadiusPx = 2f
    private lateinit var backgroundPaint: Paint
    private var showMobile = true
    private var drawCircle = true

    init {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_XY
        context.obtainStyledAttributes(attrs, R.styleable.StatusView).run {
            try {
                setBorderWidth(getDimension(R.styleable.StatusView_sv_border_width, 0f).toInt())
                backgroundPaint = Paint().apply {
                    style = Paint.Style.FILL
                    color = getColor(R.styleable.StatusView_sv_background_color, 0)
                    isAntiAlias = true
                }
                showMobile = getBoolean(R.styleable.StatusView_sv_show_mobile, true)
                cornerRadiusPx = getDimension(R.styleable.StatusView_sv_corner_radius, 0f)
            } finally {
                recycle()
            }
        }
    }

    fun setPresence(presence: Presence?) {
        val statuses = presence?.clientStatuses
        val mobile = showMobile && statuses?.mobileStatus() == ClientStatus.ONLINE &&
            statuses.webStatus() != ClientStatus.ONLINE && statuses.desktopStatus() != ClientStatus.ONLINE
        setImageResource(
            when {
                mobile -> R.drawable.ic_mobile
                presence?.activities?.any { it.type == ActivityType.STREAMING } == true ->
                    R.drawable.ic_status_streaming_16dp
                presence?.status == ClientStatus.ONLINE -> R.drawable.ic_status_online_16dp
                presence?.status == ClientStatus.IDLE -> R.drawable.ic_status_idle_16dp
                presence?.status == ClientStatus.DND -> R.drawable.ic_status_dnd_16dp
                else -> R.drawable.ic_status_invisible_16dp
            },
        )
        drawCircle = !mobile
    }

    fun setBorderWidth(borderWidthPx: Int) {
        setPadding(borderWidthPx, borderWidthPx, borderWidthPx, borderWidthPx)
    }

    fun setCornerRadius(cornerRadius: Float) {
        cornerRadiusPx = cornerRadius
        invalidate()
    }

    override fun setBackgroundColor(color: Int) {
        backgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            this.color = color
            isAntiAlias = true
        }
    }

    public override fun onDraw(canvas: Canvas) {
        if (drawCircle) {
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, backgroundPaint)
        } else {
            canvas.drawRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                cornerRadiusPx,
                cornerRadiusPx,
                backgroundPaint,
            )
        }
        super.onDraw(canvas)
    }
}
