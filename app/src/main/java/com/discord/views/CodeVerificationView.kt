package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.discord.R
import com.google.android.flexbox.FlexboxLayout

class CodeVerificationView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs, 0) {
    enum class InputType { NUMERIC, ALPHANUMERIC }

    private var inputType = InputType.NUMERIC
    private var inputFormat = "xxx-xxx"
    private val cells = mutableListOf<TextView>()
    private val unfocusedBackground = requireNotNull(
        ContextCompat.getDrawable(context, R.drawable.drawable_uikit_background_tertiary_button),
    )
    private val focusedBackground = requireNotNull(
        ContextCompat.getDrawable(context, R.drawable.drawable_uikit_background_tertiary_button_outline),
    )

    var code: String = ""
        private set
    var onCodeEntered: (String) -> Unit = {}

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CodeVerificationView).run {
                try {
                    inputFormat = getString(R.styleable.CodeVerificationView_cvv_character_format) ?: inputFormat
                    inputType = InputType.entries.getOrElse(
                        getInt(R.styleable.CodeVerificationView_cvv_character_input_type, 0),
                    ) { InputType.NUMERIC }
                } finally {
                    recycle()
                }
            }
        }

        val content = LayoutInflater.from(context).inflate(R.layout.view_code_verification, this, false)
        addView(content)
        val flexbox = content as FlexboxLayout
        inputFormat.forEach { character ->
            when (character) {
                'x' -> {
                    val cell = LayoutInflater.from(context)
                        .inflate(R.layout.view_code_verification_text, flexbox, false) as TextView
                    cells += cell
                    flexbox.addView(cell)
                }
                '-' -> flexbox.addView(
                    LayoutInflater.from(context).inflate(
                        R.layout.view_code_verification_space,
                        flexbox,
                        false,
                    ),
                )
                else -> error("Invalid format for $character")
            }
        }
        require(cells.isNotEmpty()) { "Verification format must contain at least one input cell" }

        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { showKeyboard() }
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showKeyboard() }
        cells.first().requestFocus()
        if (ViewCompat.isLaidOut(this)) showKeyboard() else addOnLayoutChangeListener(
            object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    removeOnLayoutChangeListener(this)
                    showKeyboard()
                }
            },
        )
        refresh()
    }

    /** Fills the cells without submitting; the caller decides when the pasted code is verified. */
    fun setCode(code: CharSequence) {
        val value = code.toString()
        if (value == this.code) return
        this.code = value
        refresh(notify = false)
    }

    fun clearCode() {
        if (code.isNotEmpty()) {
            code = ""
            refresh(notify = false)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ours = event.keyCode == KeyEvent.KEYCODE_DEL ||
            event.keyCode == KeyEvent.KEYCODE_0 ||
            (event.unicodeChar != 0 && event.keyCode != KeyEvent.KEYCODE_ENTER)
        // Anything else — Back above all — has to reach the rest of the window.
        if (!ours) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                event.keyCode == KeyEvent.KEYCODE_DEL -> if (code.isNotEmpty()) {
                    code = code.dropLast(1)
                    refresh()
                }
                event.keyCode == KeyEvent.KEYCODE_0 -> append('0')
                else -> append(event.unicodeChar.toChar())
            }
        }
        return true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (inputType == InputType.NUMERIC) 18 else 4224
        return BaseInputConnection(this, false)
    }

    private fun append(character: Char) {
        if (code.length < cells.size) {
            code += character
            refresh()
        }
    }

    private fun refresh(notify: Boolean = true) {
        cells.forEachIndexed { index, cell ->
            cell.text = code.getOrNull(index)?.toString().orEmpty()
            cell.background = if (index == minOf(code.length, cells.lastIndex)) {
                focusedBackground
            } else {
                unfocusedBackground
            }
        }
        if (notify && code.length == cells.size) onCodeEntered(code)
    }

    private fun showKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_FORCED)
    }
}
