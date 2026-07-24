package com.facebook.samples.zoomable

import android.content.Context
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.view.GestureDetector
import com.facebook.drawee.controller.AbstractDraweeController
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.interfaces.DraweeController
import com.facebook.imagepipeline.image.ImageInfo
import me.relex.photodraweeview.Attacher
import me.relex.photodraweeview.PhotoDraweeView

class ZoomableDraweeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PhotoDraweeView(context, attrs, defStyleAttr) {
    private var imageInfoListener: BaseControllerListener<ImageInfo>? =
        object : BaseControllerListener<ImageInfo>() {
            override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
                imageInfo?.let { update(it.width, it.height) }
                isEnableDraweeMatrix = imageInfo != null
            }

            override fun onIntermediateImageSet(id: String?, imageInfo: ImageInfo?) {
                imageInfo?.let { update(it.width, it.height) }
                isEnableDraweeMatrix = imageInfo != null
            }

            override fun onFailure(id: String?, throwable: Throwable?) {
                isEnableDraweeMatrix = false
            }
        }

    override fun setController(controller: DraweeController?) {
        imageInfoListener?.let { listener ->
            @Suppress("UNCHECKED_CAST")
            (getController() as? AbstractDraweeController<*, ImageInfo>)
                ?.removeControllerListener(listener)
            @Suppress("UNCHECKED_CAST")
            (controller as? AbstractDraweeController<*, ImageInfo>)
                ?.addControllerListener(listener)
        }
        super.setController(controller)
    }

    fun setTapListener(listener: GestureDetector.SimpleOnGestureListener?) {
        setOnDoubleTapListener(listener ?: NO_TAP_LISTENER)
    }

    fun setIsLongpressEnabled(enabled: Boolean) {
        isLongClickable = enabled
        if (!enabled) setOnLongClickListener(null)
    }

    fun getZoomableController(): Attacher = attacher

    private companion object {
        val NO_TAP_LISTENER = object : GestureDetector.SimpleOnGestureListener() {}
    }
}
