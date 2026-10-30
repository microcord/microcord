package com.discord.utilities.view.text

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.Spanned
import android.text.SpannedString
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.AndroidRuntimeException
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView.BufferType
import java.util.concurrent.atomic.AtomicBoolean

class LinkifiedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SimpleDraweeSpanTextView(context, attrs, defStyleAttr) {
    private val spanTouchListener = ClickableSpanOnTouchListener(urlSpanClickHandler)

    init {
        setOnTouchListener(spanTouchListener)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // A Spannable makes TextView lay text out with DynamicLayout, which on bind costs ~20 ms
        // for a long message. Links are found by the touch listener and spoilers flip state on the
        // span objects themselves, so an immutable copy holding the same spans behaves identically
        // and lays out with the far cheaper StaticLayout.
        val immutable = text is Spanned && !isTextSelectable && autoLinkMask == 0
        val content = if (immutable) SpannedString(text) else text
        val bufferType = if (immutable) BufferType.NORMAL else type
        setTextSafely(content, bufferType)
    }

    private fun setTextSafely(text: CharSequence?, type: BufferType?) {
        try {
            super.setText(text, type)
        } catch (error: AndroidRuntimeException) {
            if (error.cause is PackageManager.NameNotFoundException) {
                autoLinkMask = 0
                super.setText(text, type)
            } else {
                Log.w(LinkifiedTextView::class.java.toString(), "could not setText()", error)
                super.setText(null, type)
            }
        } catch (error: Exception) {
            Log.w(LinkifiedTextView::class.java.toString(), "Unable to linkify text", error)
            if (Build.VERSION.SDK_INT !in 24..25) super.setText(null, type)
        } finally {
            movementMethod = null
        }
    }

    private class ClickableSpanOnTouchListener(
        private val urlClickHandler: ((View, String) -> Unit)?,
    ) : OnTouchListener {
        private val isClickHandled = AtomicBoolean(false)
        private var pendingLongPress: Runnable? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val textView = view as? LinkifiedTextView ?: return false
            val span = touchedSpan(textView, event)
            if (span == null) {
                cancelLongPress(view)
                return false
            }

            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isClickHandled.set(false)
                    cancelLongPress(view)
                    if (span is com.discord.utilities.spans.ClickableSpan) {
                        val runnable = Runnable {
                            if (isClickHandled.compareAndSet(false, true)) {
                                span.onLongPress?.invoke(view)
                            }
                            pendingLongPress = null
                        }
                        pendingLongPress = runnable
                        view.postDelayed(runnable, 500L)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress(view)
                    if (isClickHandled.compareAndSet(false, true)) {
                        if (span is URLSpan && urlClickHandler != null) {
                            urlClickHandler.invoke(view, span.url)
                        } else {
                            span.onClick(view)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(view)
                    true
                }
                else -> false
            }
        }

        private fun cancelLongPress(view: View) {
            pendingLongPress?.let(view::removeCallbacks)
            pendingLongPress = null
        }

        private fun touchedSpan(view: LinkifiedTextView, event: MotionEvent): ClickableSpan? {
            val spanned = view.text as? Spanned ?: return null
            val textLayout = view.layout ?: return null
            val x = view.scrollX + event.x.toInt() - view.totalPaddingLeft
            val y = view.scrollY + event.y.toInt() - view.totalPaddingTop
            return try {
                val line = textLayout.getLineForVertical(y)
                if (x > textLayout.getLineRight(line) || (x >= 0 && x < textLayout.getLineLeft(line))) {
                    null
                } else {
                    val offset = textLayout.getOffsetForHorizontal(line, x.toFloat())
                    spanned.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
                }
            } catch (error: IndexOutOfBoundsException) {
                Log.w(javaClass.name, "failed to get touched clickable spans", error)
                null
            }
        }
    }

    companion object {
        private var urlSpanClickHandler: ((View, String) -> Unit)? = null

        @JvmStatic
        fun `init`(handler: ((View, String) -> Unit)? = null): Unit? {
            if (handler == null) return null
            urlSpanClickHandler = handler
            return Unit
        }
    }
}
