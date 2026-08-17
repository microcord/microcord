package com.facebook.samples.zoomable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import com.discord.views.VitoImageView

class ZoomableDraweeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VitoImageView(context, attrs, defStyleAttr) {
    private val controller = ZoomableController(this)
    private var tapListener: GestureDetector.SimpleOnGestureListener? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent) = tapListener?.onSingleTapConfirmed(e) == true
        override fun onDoubleTap(e: MotionEvent) = tapListener?.onDoubleTap(e) == true
        override fun onDoubleTapEvent(e: MotionEvent) = tapListener?.onDoubleTapEvent(e) == true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (controller.getScale() <= 1.01f) return false
            controller.pan(-distanceX, -distanceY)
            return true
        }
    })
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            controller.setScale(
                controller.getScale() * detector.scaleFactor,
                detector.focusX,
                detector.focusY,
                false,
            )
            return true
        }
    })

    init {
        scaleType = ImageView.ScaleType.MATRIX
        gestures.setIsLongpressEnabled(false)
        setOnTouchListener { _, event ->
            scaler.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        controller.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        controller.reset()
    }

    fun setTapListener(listener: GestureDetector.SimpleOnGestureListener?) {
        tapListener = listener
    }

    fun setIsLongpressEnabled(enabled: Boolean) {
        gestures.setIsLongpressEnabled(enabled)
        isLongClickable = enabled
        if (!enabled) setOnLongClickListener(null)
    }

    fun getZoomableController(): ZoomableController = controller

    class ZoomableController(private val view: ZoomableDraweeView) {
        private val matrix = Matrix()
        private val values = FloatArray(9)
        private var scale = 1f
        private var minScale = 1f
        private var maxScale = 4f
        private var transitionMs = 300L
        private var animator: ValueAnimator? = null

        fun getScale(): Float = scale
        fun getMinimumScale(): Float = minScale
        fun getMaximumScale(): Float = maxScale
        fun setZoomTransitionDuration(ms: Long) {
            transitionMs = ms
        }

        fun setScale(target: Float, fx: Float, fy: Float, animate: Boolean) {
            val dest = target.coerceIn(minScale, maxScale)
            animator?.cancel()
            if (!animate || view.width == 0) {
                applyScale(dest, fx, fy)
                return
            }
            val start = scale
            animator = ValueAnimator.ofFloat(start, dest).apply {
                duration = transitionMs
                addUpdateListener { applyScale(it.animatedValue as Float, fx, fy) }
                start()
            }
        }

        fun pan(dx: Float, dy: Float) {
            matrix.postTranslate(dx, dy)
            clamp()
            view.imageMatrix = matrix
        }

        fun reset() {
            animator?.cancel()
            scale = 1f
            val drawable = view.drawable ?: return
            val vw = view.width.toFloat()
            val vh = view.height.toFloat()
            val dw = drawable.intrinsicWidth.toFloat()
            val dh = drawable.intrinsicHeight.toFloat()
            if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
            val fit = minOf(vw / dw, vh / dh)
            matrix.setScale(fit, fit)
            matrix.postTranslate((vw - dw * fit) / 2f, (vh - dh * fit) / 2f)
            view.imageMatrix = matrix
        }

        private fun applyScale(dest: Float, fx: Float, fy: Float) {
            val factor = dest / scale
            if (factor != 1f) matrix.postScale(factor, factor, fx, fy)
            scale = dest
            clamp()
            view.imageMatrix = matrix
        }

        private fun clamp() {
            val drawable = view.drawable ?: return
            matrix.getValues(values)
            val dw = drawable.intrinsicWidth * values[Matrix.MSCALE_X]
            val dh = drawable.intrinsicHeight * values[Matrix.MSCALE_Y]
            val tx = values[Matrix.MTRANS_X]
            val ty = values[Matrix.MTRANS_Y]
            val vw = view.width.toFloat()
            val vh = view.height.toFloat()
            val dx = when {
                dw <= vw -> (vw - dw) / 2f - tx
                tx > 0f -> -tx
                tx + dw < vw -> vw - dw - tx
                else -> 0f
            }
            val dy = when {
                dh <= vh -> (vh - dh) / 2f - ty
                ty > 0f -> -ty
                ty + dh < vh -> vh - dh - ty
                else -> 0f
            }
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }
    }
}
