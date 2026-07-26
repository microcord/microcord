package com.discord.widgets.user

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.R
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WidgetUserSetCustomStatus : AppFragment(R.layout.widget_user_set_custom_status) {
    enum class Expiration { NEVER, IN_30_MINUTES, IN_1_HOUR, IN_4_HOURS, TOMORROW }
    data class Form(val text: String = "", val expiration: Expiration = Expiration.TOMORROW, val emojiUri: String? = null)
    data class Submission(val text: String?, val expiresAtUtc: String?, val emojiUri: String?)

    private var form = Form()
    private lateinit var input: TextInputEditText
    private lateinit var expiration: RadioGroup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        input = requireNotNull(view.findViewById<TextInputLayout>(R.id.set_custom_status_text).editText as? TextInputEditText)
        expiration = view.findViewById(R.id.set_custom_status_expiration)
        form = savedInstanceState?.let { Form(it.getString(TEXT).orEmpty(), Expiration.valueOf(it.getString(EXPIRATION) ?: Expiration.TOMORROW.name), it.getString(EMOJI)) }
            ?: initialState?.invoke() ?: Form()
        render()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { form = form.copy(text = s?.take(MAX_LENGTH).toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        expiration.setOnCheckedChangeListener { _, checked -> expirationFor(checked)?.let { form = form.copy(expiration = it) } }
        view.findViewById<View>(R.id.set_custom_status_emoji_button).setOnClickListener {
            if (onChooseEmoji != null) onChooseEmoji?.invoke() else {
                val emojis = arrayOf("😀", "🎮", "🎵", "💬", "🌙")
                MaterialAlertDialogBuilder(requireContext()).setItems(emojis) { _, index ->
                    form = form.copy(emojiUri = emojis[index])
                    render()
                }.show()
            }
        }
        view.findViewById<TextInputLayout>(R.id.set_custom_status_text).setEndIconOnClickListener {
            form = form.copy(text = "", emojiUri = null); render(); onClear?.invoke()
        }
        view.findViewById<View>(R.id.set_custom_status_save).setOnClickListener {
            onSave?.invoke(submission(form, nowMillis()))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(TEXT, form.text); outState.putString(EXPIRATION, form.expiration.name); outState.putString(EMOJI, form.emojiUri)
        super.onSaveInstanceState(outState)
    }

    fun setEmojiUri(uri: String?) {
        form = form.copy(emojiUri = uri)
        if (view != null) render()
    }

    private fun render() {
        if (input.text?.toString() != form.text) { input.setText(form.text.take(MAX_LENGTH)); input.setSelection(input.text?.length ?: 0) }
        expiration.check(idFor(form.expiration))
        requireView().findViewById<SimpleDraweeView>(R.id.set_custom_status_emoji).apply {
            if (form.emojiUri == null) setActualImageResource(R.drawable.ic_smiley_desaturated) else setImageURI(form.emojiUri)
        }
        requireView().findViewById<TextInputLayout>(R.id.set_custom_status_text).isEndIconVisible = form.text.isNotEmpty() || form.emojiUri != null
    }

    private fun idFor(value: Expiration) = when (value) {
        Expiration.NEVER -> R.id.set_custom_status_expiration_never
        Expiration.IN_30_MINUTES -> R.id.set_custom_status_expiration_30_minutes
        Expiration.IN_1_HOUR -> R.id.set_custom_status_expiration_1_hour
        Expiration.IN_4_HOURS -> R.id.set_custom_status_expiration_4_hours
        Expiration.TOMORROW -> R.id.set_custom_status_expiration_tomorrow
    }
    private fun expirationFor(id: Int) = Expiration.entries.firstOrNull { idFor(it) == id }

    companion object {
        const val MAX_LENGTH = 128
        private const val TEXT = "custom_status_text"
        private const val EXPIRATION = "custom_status_expiration"
        private const val EMOJI = "custom_status_emoji"
        @JvmField var initialState: (() -> Form)? = null
        @JvmField var onChooseEmoji: (() -> Unit)? = null
        @JvmField var onSave: ((Submission) -> Unit)? = null
        @JvmField var onClear: (() -> Unit)? = null
        @JvmField var nowMillis: () -> Long = System::currentTimeMillis

        fun submission(form: Form, now: Long): Submission {
            val text = form.text.take(MAX_LENGTH).trim().ifEmpty { null }
            return Submission(text, if (text == null && form.emojiUri == null) null else expirationUtc(form.expiration, now), form.emojiUri)
        }

        fun expirationUtc(expiration: Expiration, now: Long): String? {
            val millis = when (expiration) {
                Expiration.NEVER -> return null
                Expiration.IN_30_MINUTES -> now + 30 * 60_000L
                Expiration.IN_1_HOUR -> now + 60 * 60_000L
                Expiration.IN_4_HOURS -> now + 4 * 60 * 60_000L
                Expiration.TOMORROW -> Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
                    timeInMillis = now; add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); timeInMillis
                }
            }
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(millis))
        }
    }
}
