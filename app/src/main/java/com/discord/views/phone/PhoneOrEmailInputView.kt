package com.discord.views.phone

import android.animation.LayoutTransition
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
import com.discord.widgets.phone.WidgetPhoneCountryCodeBottomSheet
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * A single identifier field that accepts either an email address or a phone
 * number. In phone mode a country-code field slides in beside it; that field is
 * not typed into, it opens a country picker supplied by the host screen.
 */
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

    /**
     * Invoked when the country-code field is tapped.
     *
     * [bind] installs the country picker here by default, matching the reference
     * (`PhoneOrEmailInputView.b(fragment)` wires `WidgetPhoneCountryCodeBottomSheet` itself), so a
     * host screen only sets this when it wants a different destination. A view that was never
     * bound has no callback and its country field stays pinned to the default country.
     */
    var onCountryCodeClicked: (() -> Unit)? = null
        set(value) {
            field = value
            bindCountryCodeClick()
        }

    init {
        orientation = HORIZONTAL
        // The country-code field appears and disappears as the user types, so the
        // remaining input animates to its new width instead of jumping.
        layoutTransition = LayoutTransition()
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
                // `force_mode` is an enum attribute and therefore arrives as an int
                // index; reading it as a string always yields null, which silently
                // leaves every phone-only screen in adaptive mode.
                mode = modeOf(
                    getInt(R.styleable.PhoneOrEmailInputView_force_mode, Mode.ADAPTIVE.ordinal),
                )
                getResourceId(R.styleable.PhoneOrEmailInputView_nextFocusForward, View.NO_ID)
                    .takeIf { it != View.NO_ID }?.let {
                        mainInputLayout.nextFocusForwardId = it
                        mainEditText.nextFocusForwardId = it
                    }
            } finally {
                recycle()
            }
        }
        bindCountryCodeClick()
        updateModeAppearance()
    }

    /**
     * Attaches the view to [fragment]: text watchers, and the country picker.
     *
     * The reference splits this across two methods — `a(fragment, onAfterTextChanged)` adds the
     * watchers and `b(fragment)` opens `WidgetPhoneCountryCodeBottomSheet` from the country field —
     * and every phone screen calls both. Here they are one call, and the picker is the *default*
     * destination rather than something each caller has to remember to pass. Leaving it null by
     * default meant `widgets/auth` and the phone screens were pinned to the default country code,
     * which is not a behaviour the reference has.
     *
     * The picker reports its choice through `StorePhone`; the listener passed here only fires while
     * that store is unpublished (see `PhoneCountryCodes.select`), so screens that observe the store
     * are not double-notified.
     */
    @JvmOverloads
    fun bind(
        fragment: Fragment,
        onAfterTextChanged: () -> Unit = {},
        onCountryCodeClick: (() -> Unit)? = null,
    ) {
        onCountryCodeClicked = onCountryCodeClick ?: {
            WidgetPhoneCountryCodeBottomSheet.show(fragment.parentFragmentManager) { countryCode ->
                setCountryCode(countryCode)
            }
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

    fun getCountryCode(): String = countryCodeLayout.editText?.text?.toString().orEmpty()

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
        getCountryCode() + (mainEditText.text?.toString() ?: "")
    } else {
        mainEditText.text?.toString() ?: ""
    }

    private fun bindCountryCodeClick() {
        val editText = countryCodeLayout.editText ?: return
        val callback = onCountryCodeClicked
        if (callback == null) {
            editText.setOnClickListener(null)
            editText.isClickable = false
        } else {
            editText.isClickable = true
            editText.setOnClickListener { callback() }
        }
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

    internal companion object {
        /** Maps the `force_mode` enum index onto a mode. */
        fun modeOf(attributeValue: Int): Mode =
            Mode.values().getOrElse(attributeValue) { Mode.ADAPTIVE }
    }
}
