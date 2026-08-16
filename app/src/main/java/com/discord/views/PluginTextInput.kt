package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.text.InputFilter
import android.text.Selection
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.databinding.ViewPluginTextInputBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.expression.WidgetEmojiPickerChatSheet
import com.facebook.common.executors.CallerThreadExecutor
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.request.ImageRequest

/** A stable Discord FlexInput wrapper that external plugin XML can safely inflate. */
class PluginTextInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val binding = ViewPluginTextInputBinding.inflate(
        LayoutInflater.from(ActivityProvider.getActivity()?.applicationContext ?: context),
        this,
        true,
    )

    init {
        minimumHeight = dp(40)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary))
        }
        backgroundTintList = ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary))
        setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextNormal))
        setHintTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextMuted))
        val attributes = context.obtainStyledAttributes(
            attrs,
            intArrayOf(
                android.R.attr.hint,
                android.R.attr.imeOptions,
                android.R.attr.inputType,
                android.R.attr.maxLength,
                android.R.attr.maxLines,
            ),
        )
        try {
            binding.pluginTextInputEditText.apply {
                hint = attributes.getText(0)
                imeOptions = attributes.getInt(1, imeOptions)
                inputType = attributes.getInt(2, inputType)
                attributes.getInt(3, -1).takeIf { it >= 0 }?.let { maxLength ->
                    filters = filters + InputFilter.LengthFilter(maxLength)
                }
                maxLines = attributes.getInt(4, maxLines)
                isFocusableInTouchMode = true
                isClickable = true
                setOnClickListener { focusEditor() }
            }
        } finally {
            attributes.recycle()
        }
        setOnClickListener { focusEditor() }
    }

    val text get() = binding.pluginTextInputEditText.text

    fun setText(value: CharSequence?) = binding.pluginTextInputEditText.setText(value)

    fun setTextColor(color: Int) = binding.pluginTextInputEditText.setTextColor(color)

    fun setHintTextColor(color: Int) = binding.pluginTextInputEditText.setHintTextColor(color)

    fun setOnSend(listener: () -> Unit) {
        binding.pluginTextInputEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                listener()
                true
            } else {
                false
            }
        }
    }

    fun showEmojiPicker() {
        val activity = ActivityProvider.getActivity() as? FragmentActivity ?: return
        WidgetEmojiPickerChatSheet.show(activity.supportFragmentManager, object : EmojiPickerListener {
            override fun onEmojiPicked(emoji: Emoji) = insertEmoji(emoji)
        })
    }

    private fun insertEmoji(emoji: Emoji) {
        val input = binding.pluginTextInputEditText
        val replacement = if (emoji is ModelEmojiCustom) emoji.getMessageContentReplacement() else emoji.getChatInputText()
        val editable = input.text ?: return
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, replacement)
        Selection.setSelection(editable, start + replacement.length)
        if (emoji is ModelEmojiCustom) {
            renderCustomEmoji(input, start, start + replacement.length, replacement, emoji)
        }
        focusEditor()
    }

    private fun renderCustomEmoji(
        input: com.lytefast.flexinput.widget.FlexEditText,
        start: Int,
        end: Int,
        replacement: String,
        emoji: ModelEmojiCustom,
    ) {
        Fresco.getImagePipeline()
            .fetchDecodedImage(ImageRequest.fromUri(emoji.getImageUri(emoji.isAnimated, dp(24), input.context)), this)
            .subscribe(object : BaseBitmapDataSubscriber() {
                override fun onNewResultImpl(bitmap: Bitmap?) {
                    val editable = input.text ?: return
                    if (bitmap == null || editable.length < end || editable.substring(start, end) != replacement) return
                    val size = input.lineHeight
                    val drawable = BitmapDrawable(input.resources, bitmap).apply { setBounds(0, 0, size, size) }
                    editable.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    input.invalidate()
                }

                override fun onFailureImpl(dataSource: DataSource<CloseableReference<CloseableImage>>) = Unit
            }, CallerThreadExecutor.getInstance())
    }

    private fun focusEditor() {
        binding.pluginTextInputEditText.requestFocus()
        binding.pluginTextInputEditText.post {
            (binding.pluginTextInputEditText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.pluginTextInputEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
