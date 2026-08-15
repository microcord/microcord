package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetVoicePrivacyCodeBottomSheetBinding
import com.discord.rtcconnection.dave.generateDisplayableCode
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.intent.IntentUtils
import com.discord.widgets.settings.SettingsStrings
import android.util.Base64

/**
 * The call's DAVE privacy code, for reading out loud against everyone else's.
 *
 * The digits come from the MLS epoch authenticator, so they change whenever somebody joins or
 * leaves; the sheet is a snapshot of the code as it stood when it was opened.
 */
class WidgetVoicePrivacyCodeBottomSheet : AppBottomSheet() {

    private var _binding: WidgetVoicePrivacyCodeBottomSheetBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId(): Int = R.layout.widget_voice_privacy_code_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetVoicePrivacyCodeBottomSheetBinding.bind(view)

        val chunks = chunks(argumentsOrDefault.getString(EXTRA_EPOCH_AUTHENTICATOR))
        if (chunks == null) {
            dismiss()
            return
        }
        renderGrid(chunks)

        val isStream = argumentsOrDefault.getBoolean(EXTRA_IS_STREAM)
        binding.privacyCodeTitle.setText(
            if (isStream) R.string.e2ee_stream_privacy_code else R.string.e2ee_voice_privacy_code,
        )
        binding.privacyCodeSubtitle.setText(
            if (isStream) {
                R.string.e2ee_stream_verification_subtitle
            } else {
                R.string.e2ee_call_verification_subtitle
            },
        )

        val code = chunks.joinToString(" ")
        binding.privacyCodeClose.setOnClickListener { dismiss() }
        binding.privacyCodeCopy.setOnClickListener { AppToast.copyToClipboard(requireContext(), code) }
        binding.privacyCodeShare.setOnClickListener { IntentUtils.performChooserSendIntent(requireContext(), code) }
        binding.privacyCodeFooter.text = SettingsStrings.render(
            view.context,
            if (isStream) {
                R.string.e2ee_stream_verification_footer_text
            } else {
                R.string.e2ee_call_verification_footer_text
            },
            "helpArticle" to E2EE_HELP_URL,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderGrid(chunks: List<String>) {
        val textColor = ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary)
        val dividerColor = ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundModifierAccent)
        binding.privacyCodeGrid.removeAllViews()
        chunks.chunked(COLUMNS).forEachIndexed { index, row ->
            if (index > 0) {
                binding.privacyCodeGrid.addView(
                    View(requireContext()).apply {
                        setBackgroundColor(dividerColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    },
                )
            }
            val rowView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            row.forEach { chunk ->
                rowView.addView(
                    TextView(requireContext()).apply {
                        text = chunk
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(textColor)
                        setPadding(0, dpToPixels(PADDING_VERTICAL_DP), 0, dpToPixels(PADDING_VERTICAL_DP))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
            }
            binding.privacyCodeGrid.addView(rowView)
        }
    }

    companion object {
        private const val EXTRA_EPOCH_AUTHENTICATOR = "extra_epoch_authenticator"
        private const val EXTRA_IS_STREAM = "extra_is_stream"
        private const val CODE_LENGTH = 30
        private const val CHUNK_SIZE = 5
        private const val COLUMNS = 3
        private const val PADDING_VERTICAL_DP = 8
        private const val E2EE_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/25968222946071"

        /** Null when the authenticator is missing or too short to make a full code out of. */
        fun chunks(epochAuthenticator: String?): List<String>? {
            if (epochAuthenticator.isNullOrEmpty()) return null
            val bytes = try {
                Base64.decode(epochAuthenticator, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val code = generateDisplayableCode(bytes, CODE_LENGTH, CHUNK_SIZE) ?: return null
            return code.chunked(CHUNK_SIZE)
        }

        fun show(fragmentManager: FragmentManager, epochAuthenticator: String, isStream: Boolean) {
            WidgetVoicePrivacyCodeBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_EPOCH_AUTHENTICATOR, epochAuthenticator)
                    putBoolean(EXTRA_IS_STREAM, isStream)
                }
            }.show(fragmentManager, WidgetVoicePrivacyCodeBottomSheet::class.java.name)
        }
    }
}
