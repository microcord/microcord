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
    private var showMobileBackground = true
    private var mobileWidthPx = 0
    private var mobileHeightPx = 0
    private var defaultWidthPx: Int? = null
    private var defaultHeightPx: Int? = null

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
                showMobileBackground = getBoolean(R.styleable.StatusView_sv_mobile_background, true)
                mobileWidthPx = getDimensionPixelSize(R.styleable.StatusView_sv_mobile_width, 0)
                mobileHeightPx = getDimensionPixelSize(R.styleable.StatusView_sv_mobile_height, 0)
                cornerRadiusPx = getDimension(R.styleable.StatusView_sv_corner_radius, 0f)
            } finally {
                recycle()
            }
        }
    }

    fun setPresence(presence: Presence?) {
        val statuses = presence?.clientStatuses
        val mobileStatus = statuses?.mobileStatus()
        val mobile = showMobile && mobileStatus.isActive() &&
            !statuses?.webStatus().isActive() && !statuses?.desktopStatus().isActive()
        setImageResource(
            when {
                mobile && mobileStatus == ClientStatus.IDLE -> R.drawable.ic_mobile_idle
                mobile && mobileStatus == ClientStatus.DND -> R.drawable.ic_mobile_dnd
                mobile -> R.drawable.ic_mobile_online
                presence?.activities?.any { it.type == ActivityType.STREAMING } == true ->
                    R.drawable.ic_status_streaming_16dp
                presence?.status == ClientStatus.ONLINE -> R.drawable.ic_status_online_16dp
                presence?.status == ClientStatus.IDLE -> R.drawable.ic_status_idle_16dp
                presence?.status == ClientStatus.DND -> R.drawable.ic_status_dnd_16dp
                else -> R.drawable.ic_status_invisible_16dp
            },
        )
        drawCircle = !mobile
        updateMobileSize(mobile)
    }

    private fun updateMobileSize(mobile: Boolean) {
        if (mobileWidthPx == 0 || mobileHeightPx == 0) return
        val params = layoutParams ?: return
        val defaultWidth = defaultWidthPx ?: params.width.also { defaultWidthPx = it }
        val defaultHeight = defaultHeightPx ?: params.height.also { defaultHeightPx = it }
        val width = if (mobile) mobileWidthPx else defaultWidth
        val height = if (mobile) mobileHeightPx else defaultHeight
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            layoutParams = params
        }
    }

    private fun ClientStatus?.isActive() = this != null && this != ClientStatus.OFFLINE && this != ClientStatus.INVISIBLE

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
        if (drawCircle || showMobileBackground) {
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
        }
        super.onDraw(canvas)
    }
}
