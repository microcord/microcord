package com.discord.utilities.apng

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.linecorp.apng.ApngDrawable
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

object ApngUtils {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmOverloads
    fun renderApngFromFile(
        file: File,
        imageView: ImageView,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
        autoPlay: Boolean = false,
    ): Job {
        maxHeight?.let(imageView::setMaxHeight)
        maxWidth?.let(imageView::setMaxWidth)
        val imageViewRef = WeakReference(imageView)
        return scope.launch {
            val job = coroutineContext[Job]
            runCatching { ApngDrawable.decode(file, maxWidth, maxHeight) }
                .onSuccess { drawable ->
                    val posted = imageViewRef.get()?.post {
                        imageViewRef.get()?.takeIf { job?.isActive == true }?.let {
                            it.setImageDrawable(drawable)
                            if (autoPlay) drawable.start()
                        } ?: drawable.recycle()
                    } ?: false
                    if (!posted) drawable.recycle()
                }
        }
    }

    fun playApngAnimation(drawable: Drawable?) {
        (drawable as? ApngDrawable)?.start()
    }

    fun pauseApngAnimation(drawable: Drawable?) {
        (drawable as? ApngDrawable)?.stop()
    }
}
