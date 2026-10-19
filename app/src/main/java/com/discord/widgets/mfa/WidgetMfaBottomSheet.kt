package com.discord.widgets.mfa

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.databinding.WidgetMfaBottomSheetBinding
import com.discord.api.auth.mfa.FinishMfaRequestBody
import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethod
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.app.AppBottomSheet
import com.discord.i18n.FormatUtils
import com.discord.restapi.RestAPIParams
import com.discord.utilities.mfa.MfaElevation
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.widgets.mfa.WidgetMfaMethodSheet.Companion.addMethodRow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

private const val ARG_CHALLENGE_ID = "INTENT_EXTRA_MFA_CHALLENGE_ID"

/**
 * "Verify it's you" prompt for a request parked inside `MfaInterceptor`.
 *
 * Every path out of here reports exactly once. Outcomes and the challenge go through process-local
 * maps keyed by challenge id: the ticket is a bearer credential and must not reach a `Bundle`.
 */
class WidgetMfaBottomSheet : AppBottomSheet() {

    private lateinit var binding: WidgetMfaBottomSheetBinding

    private val challengeId: String by lazy { argumentsOrDefault.getString(ARG_CHALLENGE_ID, "") }
    private val challenge: MfaChallenge? by lazy { challenges[challengeId] }

    private var selected: MfaMethod? = null
    private var outcomeDelivered: Boolean = false

    override fun getContentViewResId(): Int = R.layout.widget_mfa_bottom_sheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live[challengeId] = WeakReference(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = WidgetMfaBottomSheetBinding.bind(view)
        // Fully open and resized for the keyboard, so the code field's Confirm button stays visible.
        setBottomSheetCollapsedStateDisabled()

        val challenge = challenge ?: return deliverAndDismiss(null)
        binding.mfaSwitchMethod.setOnClickListener { showChooser() }
        binding.mfaConfirm.setOnClickListener { submit() }
        binding.mfaCodeWrap.editText?.setOnEditorActionListener { _, _, _ -> submit(); true }

        // One offered method is not a choice; go straight to it.
        challenge.methods.singleOrNull()?.let(::select) ?: showChooser()
    }

    override fun onDestroy() {
        if (live[challengeId]?.get() === this) live.remove(challengeId)
        super.onDestroy()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        deliver(null)
    }

    private fun showChooser() {
        val challenge = challenge ?: return
        selected = null
        binding.mfaTitle.setText(R.string.mfa_v2_choose)
        binding.mfaBody.visibility = View.VISIBLE
        binding.mfaBody.setText(R.string.mfa_v2_choose_description)
        binding.mfaCodeWrap.visibility = View.GONE
        binding.mfaConfirm.visibility = View.GONE
        binding.mfaSwitchMethod.visibility = View.GONE
        binding.mfaMethodList.visibility = View.VISIBLE
        binding.mfaMethodList.removeAllViews()
        challenge.methods.forEach { method ->
            binding.mfaMethodList.addMethodRow(method.type) { select(method) }
        }
    }

    private fun select(method: MfaMethod) {
        selected = method
        binding.mfaMethodList.visibility = View.GONE
        binding.mfaCodeWrap.visibility = View.VISIBLE
        binding.mfaConfirm.visibility = View.VISIBLE
        binding.mfaConfirm.setIsLoading(false)
        binding.mfaSwitchMethod.visibility =
            if ((challenge?.methods?.size ?: 0) > 1) View.VISIBLE else View.GONE

        binding.mfaTitle.setText(method.type.titleResId())
        // Only the SMS leg has anything to say under the title, and it says it once the code is sent.
        binding.mfaBody.visibility = if (method.type == MfaMethodType.SMS) View.VISIBLE else View.GONE
        if (method.type == MfaMethodType.SMS) binding.mfaBody.setText(R.string.two_fa_enter_sms_token_sending)
        binding.mfaCodeWrap.error = null
        binding.mfaCodeWrap.editText?.setText("")
        binding.mfaCodeWrap.hint = getString(method.type.hintResId())
        binding.mfaCodeWrap.editText?.inputType = if (method.type == MfaMethodType.PASSWORD) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (method.type == MfaMethodType.SMS) sendSms()
    }

    /** The SMS leg needs a code sent before it can be answered; the reply names the number. */
    private fun sendSms() {
        val ticket = challenge?.ticket ?: return
        RestAPI.api.sendMFASms(RestAPIParams.MFASendSms(ticket))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetMfaBottomSheet::class.java) { sent ->
                sent.phone?.let {
                    binding.mfaBody.text = FormatUtils.format(
                        getString(R.string.two_fa_enter_sms_token_sent),
                        "phoneNumber" to it,
                    )
                }
            }
    }

    private fun submit() {
        val challenge = challenge ?: return
        val method = selected ?: return
        val data = binding.mfaCodeWrap.getTextOrEmpty()
        if (data.isBlank()) return

        binding.mfaConfirm.setIsLoading(true)
        binding.mfaCodeWrap.error = null
        RestAPI.api.finishMFA(FinishMfaRequestBody(challenge.ticket, method.type, data))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetMfaBottomSheet::class.java,
                errorHandler = { error ->
                    // Retried in place: bubbling this out would fail the request being verified.
                    binding.mfaConfirm.setIsLoading(false)
                    error.showErrorToasts = false
                    binding.mfaCodeWrap.error = error.message ?: error.displayString(requireContext())
                },
            ) { verified ->
                deliverAndDismiss(MfaElevation(verified.token))
            }
    }

    private fun deliverAndDismiss(elevation: MfaElevation?) {
        deliver(elevation)
        dismiss()
    }

    private fun deliver(elevation: MfaElevation?) {
        if (outcomeDelivered) return
        outcomeDelivered = true
        challenges.remove(challengeId)
        pending.remove(challengeId)?.invoke(elevation)
    }

    companion object {
        private val pending = ConcurrentHashMap<String, (MfaElevation?) -> Unit>()
        private val challenges = ConcurrentHashMap<String, MfaChallenge>()
        private val live = ConcurrentHashMap<String, WeakReference<WidgetMfaBottomSheet>>()

        /** [onOutcome] runs exactly once, on the main thread; null means the user declined. */
        @MainThread
        fun show(
            fragmentManager: FragmentManager,
            challengeId: String,
            challenge: MfaChallenge,
            onOutcome: (MfaElevation?) -> Unit,
        ) {
            pending[challengeId] = onOutcome
            challenges[challengeId] = challenge
            WidgetMfaBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_CHALLENGE_ID, challengeId) }
            }.show(fragmentManager, WidgetMfaBottomSheet::class.java.name)
        }

        /** Drops a challenge nobody is waiting for any more, closing the prompt if it is still up. */
        @MainThread
        fun cancel(challengeId: String) {
            pending.remove(challengeId)
            challenges.remove(challengeId)
            live.remove(challengeId)?.get()?.dismissAllowingStateLoss()
        }

        /** How the method reads as the heading once chosen; the row label lives on the sheet. */
        private fun MfaMethodType.titleResId(): Int = when (this) {
            MfaMethodType.TOTP -> R.string.mfa_v2_totp
            MfaMethodType.BACKUP -> R.string.mfa_v2_backup
            MfaMethodType.SMS -> R.string.enter_code
            MfaMethodType.PASSWORD -> R.string.mfa_v2_password
            MfaMethodType.WEBAUTHN -> R.string.two_fa_passkey_unsupported_title
        }

        private fun MfaMethodType.hintResId(): Int = when (this) {
            MfaMethodType.TOTP -> R.string.two_fa_auth_code_no_backup
            MfaMethodType.BACKUP -> R.string.two_fa_backup_code_hint
            MfaMethodType.SMS -> R.string.two_fa_auth_code_no_backup
            MfaMethodType.PASSWORD -> R.string.form_label_password
            MfaMethodType.WEBAUTHN -> R.string.two_fa_passkey_unsupported_title
        }
    }
}
