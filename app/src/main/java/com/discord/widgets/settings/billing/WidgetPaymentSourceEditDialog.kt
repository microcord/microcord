package com.discord.widgets.settings.billing

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppDialog
import com.discord.dialogs.SelectorDialog
import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.PatchPaymentSourceRaw
import com.discord.models.domain.billing.ModelBillingAddress
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.utilities.view.extensions.setHint
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson

class WidgetPaymentSourceEditDialog : AppDialog(R.layout.widget_payment_source_edit_dialog) {
    private lateinit var viewModel: SettingsBillingViewModel
    private lateinit var save: LoadingButton
    private lateinit var delete: View
    private lateinit var error: TextView
    private lateinit var summary: PaymentSourceView
    private lateinit var help: TextView
    private lateinit var name: TextInputLayout
    private lateinit var address1: TextInputLayout
    private lateinit var address2: TextInputLayout
    private lateinit var city: TextInputLayout
    private lateinit var state: TextInputLayout
    private lateinit var postalCode: TextInputLayout
    private lateinit var country: TextInputLayout
    private lateinit var makeDefault: CheckBox
    private lateinit var validation: ValidationManager
    private var paymentSource: ModelPaymentSource? = null

    private val paymentSourceId get() = arguments?.getString(ARG_PAYMENT_SOURCE_ID).orEmpty()
    private val usStates by lazy { loadStates("data/states.json") }
    private val caProvinces by lazy { loadStates("data/canadian-provinces.json") }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        viewModel = ViewModelProvider(this)[SettingsBillingViewModel::class.java]
        save = view.findViewById(R.id.dialog_save)
        delete = view.findViewById(R.id.dialog_delete)
        error = view.findViewById(R.id.payment_source_edit_error)
        summary = view.findViewById(R.id.payment_source_summary)
        help = view.findViewById(R.id.payment_source_edit_help)
        name = view.findViewById(R.id.payment_source_edit_name)
        address1 = view.findViewById(R.id.payment_source_edit_address1)
        address2 = view.findViewById(R.id.payment_source_edit_address2)
        city = view.findViewById(R.id.payment_source_edit_city)
        state = view.findViewById(R.id.payment_source_edit_state)
        postalCode = view.findViewById(R.id.payment_source_edit_postal_code)
        country = view.findViewById(R.id.payment_source_edit_country)
        makeDefault = view.findViewById(R.id.payment_source_edit_default)

        view.findViewById<Toolbar>(R.id.action_bar_toolbar).setNavigationOnClickListener { dismiss() }
        viewModel.observeViewState().ui(this).appSubscribe(javaClass) { viewState ->
            val item = (viewState as? SettingsBillingViewModel.ViewState.Loaded)
                ?.paymentSourceItems
                ?.filterIsInstance<PaymentSourceAdapter.PaymentSourceItem>()
                ?.firstOrNull { it.paymentSource.id == paymentSourceId }
            if (viewState is SettingsBillingViewModel.ViewState.Loaded) {
                if (item == null) dismiss() else bind(item)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun bind(item: PaymentSourceAdapter.PaymentSourceItem) {
        if (paymentSource == item.paymentSource) return
        paymentSource = item.paymentSource
        val source = item.paymentSource
        val billing = source.billingAddress
        summary.bind(source, item.isPremium)
        name.setText(billing.name)
        address1.setText(billing.line_1)
        address2.setText(billing.line_2)
        city.setText(billing.city)
        postalCode.setText(billing.postalCode)
        country.setText(billing.country)
        makeDefault.isChecked = source.default

        val states = statesFor(source)
        state.setText(states.firstOrNull { it.value == billing.state }?.label ?: billing.state)
        if (states.isEmpty()) {
            state.editText?.isFocusableInTouchMode = true
        } else {
            state.editText?.apply {
                isFocusable = false
                setOnClickListener { selectState(states) }
            }
            state.setEndIconOnClickListener { selectState(states) }
        }
        if (billing.country == "CA") {
            state.setHint(R.string.billing_address_province)
            postalCode.setHint(R.string.billing_address_postal_code)
        }

        validation = ValidationManager(
            required("name", name, R.string.billing_address_name_error_required),
            required("line_1", address1, R.string.billing_address_address_error_required),
            required("city", city, R.string.billing_address_city_error_required),
            required(
                "state",
                state,
                if (billing.country == "CA") R.string.billing_address_province_error_required
                else R.string.billing_address_state_error_required,
            ),
            required(
                "postal_code",
                postalCode,
                if (billing.country == "US") R.string.billing_address_zip_code_error_required
                else R.string.billing_address_postal_code_error_required,
            ),
        )

        help.text = if (source is ModelPaymentSource.ModelPaymentSourcePaypal) paypalHelp()
        else getText(R.string.payment_source_edit_help_card)
        help.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        save.setIsLoading(false)
        save.setOnClickListener { update(source) }
        if (item.isPremium) {
            delete.alpha = 0.3f
            delete.setOnClickListener {
                Toast.makeText(context, R.string.payment_source_delete_disabled_tooltip, Toast.LENGTH_SHORT).show()
            }
        } else {
            delete.alpha = 1f
            delete.setOnClickListener { delete(source) }
        }
    }

    private fun update(source: ModelPaymentSource) {
        if (!validation.validate()) return
        val stateText = state.getTextOrEmpty()
        val stateValue = statesFor(source).firstOrNull { it.label.equals(stateText, true) }?.value ?: stateText
        val body = PatchPaymentSourceRaw(
            ModelBillingAddress(
                name.getTextOrEmpty(),
                address1.getTextOrEmpty(),
                address2.getTextOrEmpty(),
                city.getTextOrEmpty(),
                stateValue,
                country.getTextOrEmpty(),
                postalCode.getTextOrEmpty(),
            ),
            makeDefault.isChecked,
        )
        error.visibility = View.GONE
        save.setIsLoading(true)
        RestAPI.api.updatePaymentSource(source.id, body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(javaClass, context, errorHandler = ::handleError) {
                Toast.makeText(context, R.string.payment_source_edit_saved, Toast.LENGTH_SHORT).show()
                dismiss()
            }
    }

    private fun delete(source: ModelPaymentSource) {
        save.setIsLoading(true)
        RestAPI.api.deletePaymentSource(source.id)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(javaClass, context, errorHandler = { save.setIsLoading(false) }) {
                Toast.makeText(context, R.string.payment_source_deleted, Toast.LENGTH_SHORT).show()
                dismiss()
            }
    }

    private fun handleError(restError: RestApiError) {
        save.setIsLoading(false)
        validation.setErrors(restError.messages)
        error.text = restError.message
        error.visibility = if (restError.message.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!restError.message.isNullOrBlank()) restError.showErrorToasts = false
    }

    private fun statesFor(source: ModelPaymentSource) = when (source.billingAddress.country) {
        "US" -> usStates
        "CA" -> caProvinces
        else -> emptyArray()
    }

    private fun selectState(states: Array<StateEntry>) {
        SelectorDialog.show(
            parentFragmentManager,
            getText(R.string.payment_source_edit_select_state),
            states.map { it.label as CharSequence }.toTypedArray(),
        ) { position ->
                state.setText(states[position].label)
        }
        postalCode.requestFocus()
    }

    private fun loadStates(path: String): Array<StateEntry> =
        requireContext().assets.open(path).reader().use {
            Gson().fromJson(it, Array<StateEntry>::class.java)
        }

    private fun required(name: String, input: TextInputLayout, message: Int) =
        Input.TextInputLayoutInput(name, input, BasicTextInputValidator.createRequiredInputValidator(message))

    private fun paypalHelp(): CharSequence {
        val raw = getString(R.string.payment_source_edit_help_paypal)
        val label = "PayPal.com"
        val start = raw.indexOf("[$label]")
        if (start < 0) return raw
        return SpannableString(raw.replace("[$label]({paypalURL})", label)).apply {
            setSpan(URLSpan(PAYPAL_URL), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    data class StateEntry(val label: String, val value: String)

    companion object {
        private const val ARG_PAYMENT_SOURCE_ID = "ARG_PAYMENT_SOURCE_ID"
        private const val PAYPAL_URL = "https://www.paypal.com"

        @JvmStatic
        fun launch(fragmentManager: FragmentManager, paymentSourceId: String) {
            WidgetPaymentSourceEditDialog().apply {
                arguments = Bundle().apply { putString(ARG_PAYMENT_SOURCE_ID, paymentSourceId) }
            }.show(fragmentManager, WidgetPaymentSourceEditDialog::class.java.name)
        }
    }
}
