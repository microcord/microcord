package com.discord.views

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.discord.R
import com.discord.views.ThemedColor
import com.google.android.material.textfield.TextInputEditText

class SearchInputView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs) {
    private val clearView: ImageView
    private val editText: TextInputEditText
    private var searchContentDescription: CharSequence = ""
    var onClearClicked: () -> Unit

    init {
        LayoutInflater.from(context).inflate(R.layout.search_input_view, this)
        clearView = requireNotNull(findViewById(R.id.search_clear))
        editText = requireNotNull(findViewById(R.id.search_input_edit_text))
        onClearClicked = { editText.setText("") }

        background = ContextCompat.getDrawable(context, R.drawable.drawable_bg_corners_4dp)?.mutate()?.also {
            DrawableCompat.setTint(it, ThemedColor.resolve(context, R.attr.colorBackgroundTertiary))
        }
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.SearchInputView).run {
                try {
                    editText.hint = getText(R.styleable.SearchInputView_siv_hint)
                    searchContentDescription =
                        getText(R.styleable.SearchInputView_siv_search_content_description) ?: ""
                } finally {
                    recycle()
                }
            }
        }
        setText("")
    }

    fun setText(searchQuery: String) {
        editText.setText(searchQuery)
        updateIcon(searchQuery)
    }

    fun setHint(hint: CharSequence) {
        editText.hint = hint
    }

    fun getEditText(): View = editText

    fun bindTextWatcher(fragment: Fragment, onAfterTextChanged: (String) -> Unit) {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                val query = text.toString()
                onAfterTextChanged(query)
                updateIcon(query)
            }
        }
        editText.addTextChangedListener(watcher)
        fragment.viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                editText.removeTextChangedListener(watcher)
            }
        })
    }

    private fun updateIcon(query: String) {
        val empty = query.isEmpty()
        clearView.setImageResource(if (empty) R.drawable.ic_search_16dp else R.drawable.ic_clear_white_24dp)
        ImageViewCompat.setImageTintList(
            clearView,
            android.content.res.ColorStateList.valueOf(
                ThemedColor.resolve(context, if (empty) R.attr.colorTextMuted else R.attr.colorTextNormal),
            ),
        )
        clearView.contentDescription = if (empty) searchContentDescription else context.getText(R.string.reset)
        clearView.importantForAccessibility =
            if (empty) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        clearView.setOnClickListener { if (query.isNotEmpty()) onClearClicked() }
    }

}
