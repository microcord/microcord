package com.discord.chips_view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.discord.R

class ChipsView<K, V : ChipsView.a?>(context: Context, attrs: AttributeSet) :
    ScrollView(context, attrs), InputConnectionWrapperFactory {

    interface a {
        fun getDisplayString(): String
    }

    private val density = resources.displayMetrics.density
    private val chipsContainer = RelativeLayout(context)
    private val editText = ChipsEditText(context, this)
    private val chipList = LinkedHashMap<K, Chip<K, V>>()
    private val rootChipsLayout: ChipsVerticalLinearLayout
    private var currentEditTextSpan: Any? = null

    private var maxHeight: Int
    private var verticalSpacingPx: Int
    private var chipHeightPx: Int
    private var chipsColor: Int
    private var chipsColorClicked: Int
    private var chipsBgColor: Int
    private var chipsBgResId: Int
    private var chipsBgColorClicked: Int
    private var chipsTextColor: Int
    private var chipsTextColorClicked: Int
    private var chipsHintRes: Int
    private var chipsSearchTextColor: Int
    private var chipsSearchTextSize: Float
    private var chipLayout: Int

    var chipAddedListener: ((V) -> Unit)? = null
    var chipDeletedListener: ((V) -> Unit)? = null
    var textChangedListener: ((CharSequence) -> Unit)? = null
    private var isRebuilding = false

    init {
        addView(chipsContainer)

        LinearLayout(context).also {
            it.layoutParams = ViewGroup.LayoutParams(0, 0)
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            chipsContainer.addView(it)
        }

        context.theme.obtainStyledAttributes(attrs, R.styleable.ChipsView, 0, 0).run {
            try {
                maxHeight = getDimensionPixelSize(R.styleable.ChipsView_cv_max_height, -1)
                verticalSpacingPx = getDimensionPixelSize(
                    R.styleable.ChipsView_cv_vertical_spacing,
                    density.toInt(),
                )
                chipHeightPx = getDimensionPixelSize(
                    R.styleable.ChipsView_cv_height,
                    (24 * density).toInt(),
                )
                chipsColor = getColor(
                    R.styleable.ChipsView_cv_color,
                    ContextCompat.getColor(context, android.R.color.transparent),
                )
                chipsColorClicked = getColor(
                    R.styleable.ChipsView_cv_color_clicked,
                    ContextCompat.getColor(context, android.R.color.holo_blue_dark),
                )
                chipsBgColor = getColor(
                    R.styleable.ChipsView_cv_bg_color,
                    ContextCompat.getColor(context, android.R.color.holo_blue_dark),
                )
                chipsBgResId = getResourceId(R.styleable.ChipsView_cv_chip_bg_res, 0)
                chipsBgColorClicked = getColor(
                    R.styleable.ChipsView_cv_bg_color_clicked,
                    ContextCompat.getColor(context, android.R.color.darker_gray),
                )
                chipsTextColor = getColor(R.styleable.ChipsView_cv_text_color, Color.BLACK)
                chipsTextColorClicked = getColor(R.styleable.ChipsView_cv_text_color_clicked, -1)
                chipsHintRes = getResourceId(R.styleable.ChipsView_cv_hint, 0)
                getResourceId(R.styleable.ChipsView_cv_icon_placeholder, 0)
                chipsSearchTextColor = getColor(
                    R.styleable.ChipsView_cv_search_text_color,
                    Color.BLACK,
                )
                chipsSearchTextSize = getDimensionPixelSize(
                    R.styleable.ChipsView_cv_search_text_size,
                    49,
                ).toFloat()
                chipLayout = getResourceId(
                    R.styleable.ChipsView_cv_chip_layout,
                    R.layout.view_chip_default,
                )
            } finally {
                recycle()
            }
        }

        val rowHeight = chipHeightPx + verticalSpacingPx
        editText.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            rowHeight,
        ).apply {
            leftMargin = (4 * density).toInt()
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
        }
        editText.setPadding(0, 0, 0, verticalSpacingPx)
        editText.setBackgroundColor(Color.TRANSPARENT)
        editText.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        editText.inputType = 1
        editText.setTextColor(chipsSearchTextColor)
        editText.setTextSize(0, chipsSearchTextSize)
        chipsContainer.addView(editText)

        rootChipsLayout = ChipsVerticalLinearLayout(context, rowHeight).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        chipsContainer.addView(rootChipsLayout)

        chipsContainer.setOnClickListener {
            editText.requestFocus()
            selectChip(null)
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) {
                if (!isRebuilding) textChangedListener?.invoke(s)
            }
        })
        editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectChip(null)
        }
        rebuildChips(false)
    }

    fun b(displayName: String?, imageContentDescription: String?, key: K, data: V) {
        if (chipList.containsKey(key)) return

        editText.setText("")
        val chip = Chip(
            displayName,
            imageContentDescription,
            key,
            data,
            ChipParams(
                chipsBgColorClicked,
                chipsBgResId,
                chipsBgColor,
                chipsTextColor,
                chipsTextColorClicked,
                chipHeightPx,
            ),
            this,
        )
        editText.text?.let { text ->
            currentEditTextSpan?.let(text::removeSpan)
            currentEditTextSpan?.let { text.setSpan(it, 0, 0, 17) }
        }
        chipList[key] = chip
        chipAddedListener?.invoke(data)
        rebuildChips(true)
        post { fullScroll(FOCUS_DOWN) }
    }

    fun d(pruneData: Collection<*>) {
        var changed = false
        val iterator = chipList.entries.iterator()
        while (iterator.hasNext()) {
            if (!pruneData.contains(iterator.next().key)) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) rebuildChips(true)
    }

    var text: String
        get() = editText.text.toString()
        set(value) = editText.setText(value)

    override fun wrap(target: InputConnection): InputConnection = ChipsInputConnection(target)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean = true

    private fun deleteChip(chip: Chip<K, V>) {
        chipList.remove(chip.key)
        chipDeletedListener?.invoke(chip.data)
        rebuildChips(true)
    }

    private fun selectChip(chip: Chip<*, *>?) {
        chipList.values.forEach {
            if (it !== chip) it.selected = false
        }
        rebuildChips(false)
    }

    private fun rebuildChips(moveCursor: Boolean) {
        val chipsWidth = rootChipsLayout.setChips(chipList.values)
        if (chipList.isEmpty()) {
            if (chipsHintRes != 0) editText.setHint(chipsHintRes)
        } else {
            editText.hint = ""
        }

        if (chipsWidth == null) {
            post { rebuildChips(moveCursor) }
            return
        }

        val editable = editText.text
        // `setText` with the view's own Editable still runs the full replace: it resets the
        // selection to 0 and re-fires the watcher, so the caret is restored and the republish
        // suppressed around it.
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd
        currentEditTextSpan?.let { editable?.removeSpan(it) }
        currentEditTextSpan = LeadingMarginSpan.Standard(chipsWidth, 0)
        editable?.setSpan(currentEditTextSpan, 0, 0, 17)
        isRebuilding = true
        editText.setText(editable)
        isRebuilding = false
        val length = editText.length()
        if (moveCursor) {
            editText.setSelection(length)
        } else {
            editText.setSelection(selectionStart.coerceIn(0, length), selectionEnd.coerceIn(0, length))
        }
    }

    private inner class ChipsInputConnection(target: InputConnection) :
        InputConnectionWrapper(target, true) {
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            return if (editText.length() == 0 && beforeLength == 1 && afterLength == 0) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) &&
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            } else {
                super.deleteSurroundingText(beforeLength, afterLength)
            }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (editText.length() == 0 &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_DEL
            ) {
                try {
                    val chip = chipList.values.lastOrNull()
                    if (chip != null) {
                        selectChip(chip)
                        if (chip.selected) {
                            deleteChip(chip)
                        } else {
                            chip.selected = true
                            rebuildChips(false)
                        }
                    }
                } catch (exception: IndexOutOfBoundsException) {
                    Log.e("ChipsView", "Out of bounds", exception)
                }
                return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private data class ChipParams(
        val selectedBackgroundColor: Int,
        val backgroundResource: Int,
        val backgroundColor: Int,
        val textColor: Int,
        val selectedTextColor: Int,
        val height: Int,
    )

    private class Chip<K, V : a?>(
        displayName: String?,
        val imageContentDescription: String?,
        val key: K,
        val data: V,
        val params: ChipParams,
        val container: ChipsView<K, V>,
    ) : View.OnClickListener {
        var binding: ChipBinding? = null
        var selected = false
        val displayName = (displayName ?: data?.getDisplayString()).let {
            if (it != null && it.length > 30) it.substring(0, 30) + "..." else it
        }

        override fun onClick(v: View) {
            container.editText.clearFocus()
            container.selectChip(this)
            container.deleteChip(this)
        }
    }

    private data class ChipBinding(
        val root: RelativeLayout,
        val image: ImageView,
        val text: TextView,
    )

    private inner class ChipsVerticalLinearLayout(context: Context, private val rowHeight: Int) :
        LinearLayout(context) {
        private val rows = ArrayList<LinearLayout>()

        fun setChips(chips: Collection<Chip<K, V>>): Int? {
            rows.forEach(ViewGroup::removeAllViews)
            rows.clear()
            removeAllViews()
            val availableWidth = width
            if (availableWidth == 0) return null

            var row = addRow()
            var usedWidth = 0
            chips.forEach { chip ->
                val binding = chip.binding ?: createBinding(chip).also { chip.binding = it }
                binding.text.text = chip.displayName
                if (chip.selected) {
                    binding.root.background?.setColorFilter(
                        chip.params.selectedBackgroundColor,
                        PorterDuff.Mode.SRC_ATOP,
                    )
                    binding.text.setTextColor(chip.params.selectedTextColor)
                } else {
                    binding.root.background?.setColorFilter(
                        chip.params.backgroundColor,
                        PorterDuff.Mode.SRC_ATOP,
                    )
                    binding.text.setTextColor(chip.params.textColor)
                }

                binding.root.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                if (binding.root.measuredWidth + usedWidth > availableWidth) {
                    row = addRow()
                    usedWidth = 0
                }
                usedWidth += binding.root.measuredWidth +
                    (binding.root.layoutParams as LinearLayout.LayoutParams).rightMargin
                row.addView(binding.root)
            }

            // The caller indents the input past the chips, so this is the width they occupy on the
            // last row — zero once too little is left and the input drops to a row of its own.
            if (availableWidth - usedWidth < availableWidth * 0.15f) {
                addRow()
                usedWidth = 0
            }
            return usedWidth
        }

        private fun addRow() = LinearLayout(context).also {
            it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
            it.orientation = LinearLayout.HORIZONTAL
            it.setPadding(0, 0, 0, 0)
            addView(it)
            rows.add(it)
        }

        @Suppress("UNUSED_EXPRESSION")
        private fun createBinding(chip: Chip<K, V>): ChipBinding {
            chipLayout // The 126.21 binary parses this attribute but inflates the default layout.
            val root = LayoutInflater.from(context)
                .inflate(R.layout.view_chip_default, null, false) as RelativeLayout
            val image = requireNotNull(root.findViewById<ImageView>(R.id.chip_image))
            val text = requireNotNull(root.findViewById<TextView>(R.id.chip_text))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                chip.params.height,
            )
            root.setBackgroundResource(chip.params.backgroundResource)
            root.post {
                root.background.setColorFilter(chip.params.backgroundColor, PorterDuff.Mode.SRC_ATOP)
            }
            root.setOnClickListener(chip)
            image.setOnClickListener(chip)
            image.contentDescription = chip.imageContentDescription
            text.setTextColor(chip.params.textColor)
            return ChipBinding(root, image, text)
        }
    }
}

private interface InputConnectionWrapperFactory {
    fun wrap(target: InputConnection): InputConnection
}

@SuppressLint("ViewConstructor")
private class ChipsEditText(context: Context, private val wrapperFactory: InputConnectionWrapperFactory) :
    AppCompatEditText(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection =
        wrapperFactory.wrap(requireNotNull(super.onCreateInputConnection(outAttrs)))
}
