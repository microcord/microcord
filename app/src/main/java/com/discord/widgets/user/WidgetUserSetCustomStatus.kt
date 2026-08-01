package com.discord.widgets.user

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.R
import com.discord.databinding.WidgetUserSetCustomStatusBinding
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WidgetUserSetCustomStatus : AppFragment(R.layout.widget_user_set_custom_status) {
    enum class Expiration { NEVER, IN_30_MINUTES, IN_1_HOUR, IN_4_HOURS, TOMORROW }
    data class Form(val text: String = "", val expiration: Expiration = Expiration.TOMORROW, val emojiUri: String? = null)
    data class Submission(val text: String?, val expiresAtUtc: String?, val emojiUri: String?)

    private val viewModel: WidgetUserSetCustomStatusViewModel by appViewModels {
        WidgetUserSetCustomStatusViewModel()
    }
    private var _binding: WidgetUserSetCustomStatusBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val input get() = requireNotNull(binding.setCustomStatusText.editText as? TextInputEditText)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetUserSetCustomStatusBinding.bind(view)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setStatusText(s?.take(MAX_LENGTH).toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.setCustomStatusExpiration.setOnCheckedChangeListener { _, checked ->
            expirationFor(checked)?.let(viewModel::setExpiration)
        }
        binding.setCustomStatusEmojiButton.setOnClickListener {
            onChooseEmoji?.invoke() ?: EmojiPickerNavigator.launchBottomSheet(
                parentFragmentManager,
                object : EmojiPickerListener {
                    override fun onEmojiPicked(emoji: com.discord.models.domain.emoji.Emoji) =
                        viewModel.setStatusEmoji(emoji)
                },
                EmojiPickerContextType.Global,
            )
        }
        binding.setCustomStatusText.setEndIconOnClickListener {
            viewModel.clearStatusTextAndEmoji()
        }
        binding.setCustomStatusSave.setOnClickListener {
            viewModel.saveStatus()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetUserSetCustomStatus::class.java) { state ->
                if (state is WidgetUserSetCustomStatusViewModel.ViewState.Loaded) render(state)
            }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetUserSetCustomStatus::class.java) { event ->
                val message = when (event) {
                    is WidgetUserSetCustomStatusViewModel.Event.SetStatusFailure -> event.failureMessageStringRes
                    is WidgetUserSetCustomStatusViewModel.Event.SetStatusSuccess -> event.successMessageStringRes
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                if (event is WidgetUserSetCustomStatusViewModel.Event.SetStatusSuccess) {
                    parentFragmentManager.popBackStack()
                }
            }
    }

    private fun render(state: WidgetUserSetCustomStatusViewModel.ViewState.Loaded) {
        val form = state.formState
        if (input.text?.toString() != form.text) {
            input.setText(form.text.take(MAX_LENGTH))
            input.setSelection(input.text?.length ?: 0)
        }
        binding.setCustomStatusExpiration.check(idFor(form.expiration))
        binding.setCustomStatusEmoji.apply {
            if (form.emoji == null) {
                setActualImageResource(R.drawable.ic_smiley_desaturated)
            } else {
                setImageURI(form.emoji.getImageUri(true, 64, requireContext()))
            }
        }
        binding.setCustomStatusText.isEndIconVisible = state.showStatusClear
    }

    private fun idFor(value: WidgetUserSetCustomStatusViewModel.FormState.Expiration) = when (value) {
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.NEVER -> R.id.set_custom_status_expiration_never
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_30_MINUTES -> R.id.set_custom_status_expiration_30_minutes
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_1_HOUR -> R.id.set_custom_status_expiration_1_hour
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.IN_4_HOURS -> R.id.set_custom_status_expiration_4_hours
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.TOMORROW -> R.id.set_custom_status_expiration_tomorrow
    }
    private fun expirationFor(id: Int) =
        WidgetUserSetCustomStatusViewModel.FormState.Expiration.entries.firstOrNull { idFor(it) == id }

    companion object {
        const val MAX_LENGTH = 128
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
