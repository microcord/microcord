package com.discord.views.phone

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.discord.R
import com.discord.models.phone.PhoneCountryCode
import com.discord.utilities.phone.PhoneUtils
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class PhoneOrEmailInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    enum class Mode { ADAPTIVE, EMAIL, PHONE }

    private val countryCodeLayout: TextInputLayout
    private val mainInputLayout: TextInputLayout
    private var mode = Mode.ADAPTIVE
    private var email: CharSequence = ""
    private var phone: CharSequence = ""

    val mainEditText: TextInputEditText
    val mainTextInputLayout: TextInputLayout
        get() = mainInputLayout

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_phone_or_email_input, this, true)
        countryCodeLayout = requireNotNull(findViewById(R.id.phone_or_email_country_code_wrap))
        mainInputLayout = requireNotNull(findViewById(R.id.phone_or_email_main_input_wrap))
        mainEditText = requireNotNull(findViewById(R.id.phone_or_email_main_input))

        context.obtainStyledAttributes(
            attrs,
            R.styleable.PhoneOrEmailInputView,
            defStyleAttr,
            0,
        ).run {
            try {
                setHint(getText(R.styleable.PhoneOrEmailInputView_hint))
                mode = when (getString(R.styleable.PhoneOrEmailInputView_force_mode)?.lowercase()) {
                    "email", "1" -> Mode.EMAIL
                    "phone", "2" -> Mode.PHONE
                    else -> Mode.ADAPTIVE
                }
                getResourceId(R.styleable.PhoneOrEmailInputView_nextFocusForward, View.NO_ID)
                    .takeIf { it != View.NO_ID }?.let {
                    mainInputLayout.nextFocusForwardId = it
                    mainEditText.nextFocusForwardId = it
                }
            } finally {
                recycle()
            }
        }
        updateModeAppearance()
    }

    fun bind(
        fragment: Fragment,
        onAfterTextChanged: () -> Unit = {},
        onCountryCodeClick: (() -> Unit)? = null,
    ) {
        onCountryCodeClick?.let { callback ->
            countryCodeLayout.editText?.setOnClickListener { callback() }
        }
        addLifecycleTextWatcher(fragment, countryCodeLayout.editText) { onAfterTextChanged() }
        addLifecycleTextWatcher(fragment, mainEditText) {
            rememberCurrentText()
            updateCountryCodeVisibility()
            onAfterTextChanged()
        }
        updateCountryCodeVisibility()
    }

    fun setCountryCode(countryCode: PhoneCountryCode) {
        countryCodeLayout.editText?.setText(countryCode.phoneCountryCode)
    }

    fun setHint(hint: CharSequence?) {
        mainInputLayout.hint = hint
    }

    fun setMode(mode: Mode) {
        rememberCurrentText()
        this.mode = mode
        updateModeAppearance()
    }

    fun setText(text: CharSequence?) {
        mainEditText.setText(text)
        rememberCurrentText()
        updateCountryCodeVisibility()
    }

    fun getTextOrEmpty(): String = if (isPhoneMode()) {
        (countryCodeLayout.editText?.text?.toString() ?: "") + (mainEditText.text?.toString() ?: "")
    } else {
        mainEditText.text?.toString() ?: ""
    }

    private fun updateCountryCodeVisibility() {
        countryCodeLayout.visibility = if (isPhoneMode()) View.VISIBLE else View.GONE
    }

    private fun updateModeAppearance() {
        val phoneMode = mode == Mode.PHONE
        mainEditText.inputType = if (phoneMode) {
            InputType.TYPE_CLASS_PHONE
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mainEditText.setAutofillHints(if (phoneMode) View.AUTOFILL_HINT_PHONE else View.AUTOFILL_HINT_EMAIL_ADDRESS)
        }
        val restored = if (phoneMode) phone else email
        mainEditText.setText(restored)
        mainEditText.setSelection(restored.length)
        updateCountryCodeVisibility()
    }

    private fun isPhoneMode() = when (mode) {
        Mode.EMAIL -> false
        Mode.PHONE -> true
        Mode.ADAPTIVE -> PhoneUtils.isLikelyToContainPhoneNumber(mainEditText.text?.toString() ?: "")
    }

    private fun rememberCurrentText() {
        val text = mainEditText.text?.toString() ?: ""
        if (mode == Mode.PHONE || PhoneUtils.isLikelyToContainPhoneNumber(text)) phone = text else email = text
    }

    private fun addLifecycleTextWatcher(
        fragment: Fragment,
        editText: EditText?,
        afterChanged: () -> Unit,
    ) {
        if (editText == null) return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = afterChanged()
        }
        editText.addTextChangedListener(watcher)
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                editText.removeTextChangedListener(watcher)
            }
        })
    }
}
