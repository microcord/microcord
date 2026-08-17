package com.facebook.samples.zoomable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewParent
import com.discord.views.VitoImageView

class ZoomableDraweeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VitoImageView(context, attrs, defStyleAttr) {
    var onZoomChanged: ((Boolean) -> Unit)? = null
    private val controller = ZoomableController(this)
    private var tapListener: GestureDetector.SimpleOnGestureListener? = null
    private var lastPanX = 0f
    private var lastPanY = 0f
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent) = tapListener?.onSingleTapConfirmed(e) == true
        override fun onDoubleTap(e: MotionEvent) = tapListener?.onDoubleTap(e) == true
        override fun onDoubleTapEvent(e: MotionEvent) = tapListener?.onDoubleTapEvent(e) == true
    })
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.coerceIn(0.5f, 2f)
            controller.setScale(
                controller.getScale() * factor,
                detector.focusX,
                detector.focusY,
                false,
            )
            return true
        }
    })

    init {
        isClickable = true
        gestures.setIsLongpressEnabled(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 29) {
            super.onDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.concat(controller.matrix)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            controller.getScale() > 1.01f
        ) {
            disallowParents()
        }
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val stay = if (event.actionIndex == 0) 1 else 0
                if (stay < event.pointerCount) {
                    lastPanX = event.getX(stay)
                    lastPanY = event.getY(stay)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress &&
                controller.getScale() > 1.01f &&
                event.pointerCount == 1
            ) {
                controller.pan(event.x - lastPanX, event.y - lastPanY)
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (controller.getScale() <= 1.01f) allowParents()
            }
        }
        return true
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

    private fun disallowParents() {
        var parent: ViewParent? = parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true)
            parent = parent.parent
        }
    }

    private fun allowParents() {
        var parent: ViewParent? = parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false)
            parent = parent.parent
        }
    }

    class ZoomableController(private val view: ZoomableDraweeView) {
        val matrix = Matrix()
        private val mapped = RectF()
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
            publish()
        }

        fun reset() {
            animator?.cancel()
            val wasZoomed = scale > 1.01f
            scale = 1f
            matrix.reset()
            publish()
            if (wasZoomed) view.onZoomChanged?.invoke(false)
        }

        private fun applyScale(dest: Float, fx: Float, fy: Float) {
            val factor = dest / scale
            if (factor != 1f) matrix.postScale(factor, factor, fx, fy)
            val wasZoomed = scale > 1.01f
            scale = dest
            if (dest <= minScale + 0.01f) matrix.reset() else clamp()
            publish()
            val zoomed = dest > 1.01f
            if (wasZoomed != zoomed) view.onZoomChanged?.invoke(zoomed)
        }

        private fun clamp() {
            val vw = view.width.toFloat()
            val vh = view.height.toFloat()
            if (vw <= 0f || vh <= 0f) return
            mapped.set(0f, 0f, vw, vh)
            matrix.mapRect(mapped)
            var dx = 0f
            var dy = 0f
            if (mapped.width() <= vw) dx = (vw - mapped.width()) / 2f - mapped.left
            else {
                if (mapped.left > 0f) dx = -mapped.left
                if (mapped.right < vw) dx = vw - mapped.right
            }
            if (mapped.height() <= vh) dy = (vh - mapped.height()) / 2f - mapped.top
            else {
                if (mapped.top > 0f) dy = -mapped.top
                if (mapped.bottom < vh) dy = vh - mapped.bottom
            }
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }

        private fun publish() {
            if (Build.VERSION.SDK_INT >= 29) view.animationMatrix = matrix
            view.invalidate()
        }
    }
}
