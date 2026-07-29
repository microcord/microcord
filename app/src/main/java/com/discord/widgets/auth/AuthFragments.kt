package com.discord.widgets.auth

import android.app.DatePickerDialog
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.captcha.CaptchaHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import rx.Subscription

abstract class AuthFragment(
    @LayoutRes layoutId: Int,
    private val route: AuthRoute,
) : AppFragment(layoutId) {
    var authUiActions: AuthUiActions? = null
    protected val model: AuthUiViewModel by viewModels()
    private var stateSubscription: Subscription? = null

    protected val actions: AuthUiActions
        get() = authUiActions ?: (activity as? AuthUiActions)
        ?: error("Auth host must implement AuthUiActions or inject authUiActions")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val restored = savedInstanceState?.getSerializable(STATE) as? AuthUiState
            ?: @Suppress("DEPRECATION") (arguments?.getSerializable(STATE) as? AuthUiState)
        model.restore((restored ?: AuthUiState(route = route)).copy(route = route))
    }

    override fun onStart() {
        super.onStart()
        stateSubscription = model.observe().subscribe(::render)
    }

    override fun onStop() {
        stateSubscription?.unsubscribe()
        stateSubscription = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureState()
        outState.putSerializable(STATE, model.state)
        super.onSaveInstanceState(outState)
    }

    protected open fun captureState() = Unit
    protected open fun render(state: AuthUiState) = Unit

    /**
     * The action a captcha is currently blocking, replayed once the challenge is solved.
     *
     * Deliberately in memory only: it can hold a password, so it must never reach saved instance
     * state or a log. If the process dies mid-challenge the user re-enters the form, which is the
     * correct trade.
     */
    private var captchaBlockedAction: AuthAction? = null

    private val captchaLauncher = WidgetAuthCaptcha.registerForResult(this) { payload ->
        captchaBlockedAction?.let { blocked ->
            captchaBlockedAction = null
            submit(blocked, payload)
        }
    }

    protected fun submit(action: AuthAction, captcha: CaptchaHelper.CaptchaPayload? = null) {
        model.update { it.copy(loading = true, fieldErrors = emptyMap(), message = null) }
        actions.submit(action, captcha) { result ->
            if (!isAdded) return@submit
            requireActivity().runOnUiThread {
                when (result) {
                    is AuthResult.Success -> {
                        model.update { it.clearCredentials().copy(loading = false, message = result.message) }
                        result.nextRoute?.let(actions::navigate)
                    }
                    is AuthResult.Failure -> model.update {
                        it.copy(loading = false, fieldErrors = result.fieldErrors, message = result.message)
                    }
                    is AuthResult.Challenge -> {
                        model.update { it.copy(loading = false) }
                        actions.navigate(result.route, result.state.copy(route = result.route))
                    }
                    // Hold the action, run the challenge, and re-submit it verbatim afterwards so
                    // the user never re-types the form.
                    is AuthResult.CaptchaRequired -> {
                        model.update { it.copy(loading = false) }
                        captchaBlockedAction = action
                        WidgetAuthCaptcha.launch(requireContext(), captchaLauncher, result.error)
                    }
                }
            }
        }
    }

    protected fun TextInputLayout.text() = editText?.text?.toString().orEmpty()
    protected fun TextInputLayout.setErrorFrom(state: AuthUiState, key: String) { error = state.fieldErrors[key] }
    protected fun TextInputLayout.onDone(action: () -> Unit) {
        editText?.setOnEditorActionListener { _, id, event ->
            val done = id == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (done) action()
            done
        }
    }

    protected open fun pasteCodeFromClipboard(): Boolean = false

    protected fun clipboardCode(length: Int, stripSpaces: Boolean = false): String? {
        val context = context ?: return null
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        val normalized = if (stripSpaces) value.replace(" ", "") else value
        return normalized.takeIf { it.length == length && it.all(Char::isDigit) }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, view, menuInfo)
        MenuInflater(requireContext()).inflate(R.menu.menu_code_verification, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.menu_code_verification_paste && pasteCodeFromClipboard()) true
        else super.onContextItemSelected(item)

    protected fun chooseBirthday(current: Long?, callback: (Long) -> Unit) {
        val initial = Calendar.getInstance().apply {
            timeInMillis = current ?: run {
                add(Calendar.YEAR, -18)
                timeInMillis
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                callback(Calendar.getInstance().apply { set(year, month, day, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
            },
            initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    companion object {
        private const val STATE = "auth_ui_state"
        fun arguments(state: AuthUiState) = Bundle().apply { putSerializable(STATE, state) }
    }
}
class WidgetAuthEmailVerify : AuthFragment(R.layout.widget_user_email_verify, AuthRoute.EMAIL_VERIFICATION) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.alert_verify_email_resend).setOnClickListener { submit(AuthAction.ResendEmail(model.state.email)) }
        view.findViewById<MaterialButton>(R.id.alert_verify_email_change).setOnClickListener {
            actions.navigate(AuthRoute.REGISTER_IDENTITY, model.state.copy(route = AuthRoute.REGISTER_IDENTITY, identityMode = AuthIdentityMode.EMAIL))
        }
    }

    override fun render(state: AuthUiState) {
        requireView().findViewById<MaterialButton>(R.id.alert_verify_email_resend).isEnabled = !state.loading
    }
}

class WidgetAuthRequiredAction : AuthFragment(R.layout.widget_user_account_verify, AuthRoute.REQUIRED_ACTION) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<View>(R.id.verify_account_by_email).setOnClickListener { navigate(AuthRequiredAction.VERIFY_EMAIL) }
        view.findViewById<View>(R.id.verify_account_by_phone).setOnClickListener { navigate(AuthRequiredAction.VERIFY_PHONE) }
        view.findViewById<TextView>(R.id.verify_account_support).setOnClickListener { actions.openSupport() }
    }

    private fun navigate(action: AuthRequiredAction) = actions.navigate(AuthUiRules.routeFor(action), model.state.copy(route = AuthUiRules.routeFor(action)))
    override fun render(state: AuthUiState) {
        requireView().findViewById<View>(R.id.verify_account_by_email).visibility = if (state.allowEmail) View.VISIBLE else View.GONE
        requireView().findViewById<View>(R.id.verify_account_by_phone).visibility = if (state.allowPhone) View.VISIBLE else View.GONE
        state.requiredAction?.takeIf { it == AuthRequiredAction.UPDATE_PASSWORD }?.let { navigate(it) }
    }
}
