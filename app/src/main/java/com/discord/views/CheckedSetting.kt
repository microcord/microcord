package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Parcelable
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Checkable
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.widgets.settings.SettingsStrings
import com.discord.utilities.view.text.LinkifiedTextView
import rx.functions.Action0
import rx.functions.Action1

class CheckedSetting(context: Context, attrs: AttributeSet?) : RelativeLayout(context, attrs, 0), Checkable {
    enum class ViewType { CHECK, RADIO, SWITCH }

    private val container: View
    private val button: CompoundButton
    private val label: TextView
    private val leftDrawable: ImageView
    private val tag: TextView
    private val subtext: LinkifiedTextView
    private val viewType: ViewType
    private var checkedListener: Action1<Boolean>? = null

    init {
        // `cs_view_type` is an enum attribute, so it arrives as an int index; reading
        // it as a string or dimension silently yields 0 and renders every switch and
        // radio row as a checkbox.
        val requestedType = if (attrs == null) DEFAULT_VIEW_TYPE else context.obtainStyledAttributes(
            attrs,
            R.styleable.CheckedSetting,
            0,
            0,
        ).run {
            try {
                getInt(R.styleable.CheckedSetting_cs_view_type, DEFAULT_VIEW_TYPE)
            } finally {
                recycle()
            }
        }
        viewType = viewTypeOf(requestedType)
        LayoutInflater.from(context).inflate(layoutFor(viewType), this)
        container = requireNotNull(findViewById(R.id.setting_container))
        button = requireNotNull(findViewById(R.id.setting_button))
        label = requireNotNull(findViewById(R.id.setting_label))
        leftDrawable = requireNotNull(findViewById(R.id.setting_drawable_left))
        tag = requireNotNull(findViewById(R.id.setting_tag))
        subtext = requireNotNull(findViewById(R.id.setting_subtext))

        container.setOnClickListener {
            if (viewType == ViewType.RADIO) button.isChecked = true else button.toggle()
        }
        button.setOnCheckedChangeListener { _, checked -> checkedListener?.call(checked) }
        ViewCompat.setAccessibilityDelegate(container, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = isChecked
                info.roleDescription = context.getString(accessibilityRoleFor(viewType))
            }

            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(host, event)
                event.isChecked = isChecked
            }
        })

        if (attrs != null) applyAttributes(attrs)
    }

    private fun applyAttributes(attrs: AttributeSet) {
        context.obtainStyledAttributes(attrs, R.styleable.CheckedSetting, 0, 0).run {
            try {
                background = getDrawable(R.styleable.CheckedSetting_cs_background)
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.drawable_bg_settings_item_white, context.theme)
                if (hasValue(R.styleable.CheckedSetting_cs_background_tint)) {
                    backgroundTintList = ColorStateList.valueOf(
                        getColor(R.styleable.CheckedSetting_cs_background_tint, 0),
                    )
                }
                getDrawable(R.styleable.CheckedSetting_cs_container_background)?.let {
                    container.background = it
                }
                val horizontal = getDimensionPixelSize(
                    R.styleable.CheckedSetting_cs_padding_horizontal,
                    container.paddingLeft,
                )
                val vertical = getDimensionPixelSize(
                    R.styleable.CheckedSetting_cs_padding_vertical,
                    container.paddingTop,
                )
                container.setPadding(horizontal, vertical, horizontal, vertical)

                leftDrawable.setImageDrawable(getDrawable(R.styleable.CheckedSetting_cs_drawable_left))
                leftDrawable.visibility = if (leftDrawable.drawable == null) View.GONE else View.VISIBLE
                getColorStateList(R.styleable.CheckedSetting_cs_drawable_left_tint)?.let {
                    ImageViewCompat.setImageTintList(leftDrawable, it)
                }
                label.text = renderMarkup(getText(R.styleable.CheckedSetting_cs_text))
                getColorStateList(R.styleable.CheckedSetting_cs_text_color)?.let(label::setTextColor)
                val labelSize = getDimension(R.styleable.CheckedSetting_cs_text_size, 0f)
                if (labelSize > 0f) label.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSize)
                val font = getResourceId(R.styleable.CheckedSetting_cs_text_fontpath, 0)
                if (font != 0) ResourcesCompat.getFont(context, font)?.let { label.typeface = it }

                setSubtext(renderMarkup(getText(R.styleable.CheckedSetting_cs_subtext)))
                getColorStateList(R.styleable.CheckedSetting_cs_subtext_color)?.let(subtext::setTextColor)
                val subtextSize = getDimension(R.styleable.CheckedSetting_cs_subtext_size, 0f)
                if (subtextSize > 0f) subtext.setTextSize(TypedValue.COMPLEX_UNIT_PX, subtextSize)
            } finally {
                recycle()
            }
        }
    }

    override fun isChecked() = button.isChecked

    override fun setChecked(checked: Boolean) = setChecked(checked, false)

    fun setChecked(checked: Boolean, notifyListener: Boolean) {
        if (notifyListener) {
            button.isChecked = checked
        } else {
            val listener = checkedListener
            button.setOnCheckedChangeListener(null)
            button.isChecked = checked
            button.setOnCheckedChangeListener { _, value -> listener?.call(value) }
        }
    }

    override fun toggle() = setChecked(!isChecked, false)

    fun setOnCheckedListener(listener: Action1<Boolean>?) {
        checkedListener = listener
        button.setOnCheckedChangeListener { _, checked -> listener?.call(checked) }
    }

    fun setButtonAlpha(alpha: Float) {
        button.alpha = alpha
    }

    fun setButtonVisibility(visible: Boolean) {
        button.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setLabelTagText(textRes: Int) {
        tag.setText(textRes)
    }

    fun setLabelTagVisibility(visible: Boolean) {
        tag.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Renders the `**bold**` / `[label](url)` markup the recovered strings carry. Only the value
     * that came out of the XML attribute goes through here — text assigned later is left as given,
     * matching [com.discord.app.DiscordViewInflater].
     */
    private fun renderMarkup(text: CharSequence?): CharSequence? =
        text?.takeIf { it.contains("**") || it.contains("](") }
            ?.let { SettingsStrings.render(context, it) }
            ?: text

    fun setSubtext(text: CharSequence?) {
        subtext.text = text
        subtext.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setSubtext(text: CharSequence?, enableLinkMovement: Boolean) {
        setSubtext(text)
        if (enableLinkMovement) subtext.movementMethod = LinkMovementMethod.getInstance()
    }

    fun setSubtextColor(color: Int) {
        subtext.setTextColor(color)
    }

    fun setSubtextOnClickListener(listener: Action0) {
        subtext.setOnClickListener { listener.call() }
    }

    fun setText(text: CharSequence?) {
        label.text = text
    }

    fun setTextColor(color: Int) {
        label.setTextColor(color)
    }

    fun setDisabledTooltip(message: CharSequence?) {
        container.setOnClickListener {
            if (!message.isNullOrEmpty()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        alpha = 0.3f
    }

    fun setDisabledTooltip(messageRes: Int) = setDisabledTooltip(context.getText(messageRes))

    fun setContainerClickListener(listener: OnClickListener?) {
        container.setOnClickListener(listener)
        alpha = 1f
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>?) {
        dispatchFreezeSelfOnly(container)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>?) {
        dispatchThawSelfOnly(container)
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(STATE_SUPER, super.onSaveInstanceState())
        putBoolean(STATE_CHECKED, isChecked)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            button.isChecked = state.getBoolean(STATE_CHECKED)
            super.onRestoreInstanceState(state.getParcelable(STATE_SUPER))
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    internal companion object {
        private const val STATE_SUPER = "STATE_SUPER"
        private const val STATE_CHECKED = "STATE_CHECKED"

        /** Matches the `check` entry of the `cs_view_type` attribute enum. */
        const val DEFAULT_VIEW_TYPE = 0

        /** Maps the `cs_view_type` enum index onto a view type. */
        fun viewTypeOf(attributeValue: Int): ViewType =
            ViewType.values().getOrElse(attributeValue) { ViewType.CHECK }

        fun layoutFor(viewType: ViewType) = when (viewType) {
            ViewType.CHECK -> R.layout.view_setting_check
            ViewType.RADIO -> R.layout.view_setting_radio
            ViewType.SWITCH -> R.layout.view_setting_switch
        }

        fun accessibilityRoleFor(viewType: ViewType) = when (viewType) {
            ViewType.CHECK -> R.string.a11y_role_checkbox
            ViewType.RADIO -> R.string.a11y_role_radio_button
            ViewType.SWITCH -> R.string.a11y_role_switch
        }
    }
}
