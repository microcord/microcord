package com.discord.widgets.auth

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
import com.discord.dialogs.DatePickerDialog
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
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
        stateSubscription = model.observe().subscribe { state ->
            renderScreenError(state)
            render(state)
        }
    }

    /** Shows [AuthUiState.error] — the failures no input owns — if the layout has a slot for it. */
    private fun renderScreenError(state: AuthUiState) {
        val label = view?.findViewById<TextView>(R.id.auth_error) ?: return
        label.text = state.error
        label.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        stateSubscription?.unsubscribe()
        stateSubscription = null
        super.onStop()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // An undeliverable callback would otherwise leave the new view dimmed and inert.
        if (model.state.loading) model.update { it.copy(loading = false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Fragments on the back stack are saved too, and they have no view to read inputs from.
        if (view != null) captureState()
        outState.putSerializable(STATE, model.state)
        super.onSaveInstanceState(outState)
    }

    protected open fun captureState() = Unit
    protected open fun render(state: AuthUiState) = Unit

    protected fun submit(action: AuthAction) {
        // Two triggers can land on one code; the second must not spend a spent ticket.
        if (model.state.loading) return
        cancelPendingVerify()
        model.update { it.copy(loading = true, fieldErrors = emptyMap(), error = null, message = null) }
        actions.submit(action) { result ->
            if (!isAdded) return@submit
            requireActivity().runOnUiThread {
                when (result) {
                    is AuthResult.Success -> {
                        model.update {
                            val state = if (action.keepsCredentials) it else it.clearCredentials()
                            state.copy(loading = false, message = result.message)
                        }
                        result.nextRoute?.let(actions::navigate)
                    }
                    is AuthResult.Failure -> model.update {
                        it.copy(loading = false, fieldErrors = result.fieldErrors, error = result.message)
                    }
                    is AuthResult.Challenge -> {
                        model.update { it.copy(loading = false) }
                        actions.navigate(result.route, result.state.copy(route = result.route))
                    }
                }
            }
        }
    }

    /** [AuthUiRules] reports failures as string resource ids; the screen supplies the strings. */
    protected fun Map<String, Int>.resolve(): Map<String, String> = mapValues { (_, res) -> getString(res) }

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

    private var pendingVerify: Runnable? = null
    private var pendingVerifyView: View? = null

    /** Queues [verify] after [delayMs], replacing any verify an earlier paste left pending. */
    protected fun postVerify(view: View, delayMs: Long, verify: () -> Unit) {
        cancelPendingVerify()
        pendingVerify = Runnable(verify).also { view.postDelayed(it, delayMs) }
        pendingVerifyView = view
    }

    private fun cancelPendingVerify() {
        pendingVerify?.let { pendingVerifyView?.removeCallbacks(it) }
        pendingVerify = null
        pendingVerifyView = null
    }

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
        DatePickerDialog.show(
            parentFragmentManager,
            getString(R.string.age_gate_date_of_birth),
            current ?: subtractYearsFromToday(10),
            subtractYearsFromToday(3),
            callback,
        )
    }

    private fun subtractYearsFromToday(years: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ClockFactory.get().currentTimeMillis()
        set(Calendar.YEAR, get(Calendar.YEAR) - years)
    }.timeInMillis

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

    /** Which factors are offered comes from the gate itself; some accept only one of the two. */
    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUserRequiredActions().observeUserRequiredAction()
            .ui(this)
            .appSubscribe(WidgetAuthRequiredAction::class.java) { action ->
                model.update {
                    if (action.isVerificationGate) {
                        it.copy(allowEmail = action.acceptsEmail, allowPhone = action.acceptsPhone)
                    } else {
                        it
                    }
                }
            }
    }

    private fun navigate(action: AuthRequiredAction) = actions.navigate(AuthUiRules.routeFor(action), model.state.copy(route = AuthUiRules.routeFor(action)))
    override fun render(state: AuthUiState) {
        requireView().findViewById<View>(R.id.verify_account_by_email).visibility = if (state.allowEmail) View.VISIBLE else View.GONE
        requireView().findViewById<View>(R.id.verify_account_by_phone).visibility = if (state.allowPhone) View.VISIBLE else View.GONE
        state.requiredAction?.takeIf { it == AuthRequiredAction.UPDATE_PASSWORD }?.let { navigate(it) }
    }
}
