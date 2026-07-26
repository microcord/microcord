package com.discord.widgets.hubs

import android.app.Activity
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * "Tell us your school" — files a waitlist entry for a school that has no Student Hub yet.
 *
 * Binds `R.layout.widget_hub_waitlist`. Finishes with [HubWaitlistResult] so the flow that started it
 * can switch to its "you're on the list" page.
 */
class WidgetHubWaitlist : AppFragment(R.layout.widget_hub_waitlist) {

    private val args: HubWaitlistArgs by hubArgs()

    private val viewModel: WidgetHubWaitlistViewModel by appViewModels {
        WidgetHubWaitlistViewModel(args.email)
    }

    private lateinit var schoolLayout: TextInputLayout
    private lateinit var schoolInput: TextInputEditText
    private lateinit var submitButton: LoadingButton

    private val validationManager: ValidationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                SCHOOL_NAME_FIELD,
                schoolLayout,
                BasicTextInputValidator.createRequiredInputValidator(R.string.member_verification_form_required_item),
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        schoolLayout = view.findViewById(R.id.hub_waitlist_layout)
        schoolInput = view.findViewById(R.id.hub_waitlist_edit_text)
        submitButton = view.findViewById(R.id.hub_waitlist_button)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubWaitlist::class.java) { configureUI(it) }
        submitButton.setOnClickListener { maybeSubmitSchoolName() }
        showKeyboard(schoolInput)
    }

    private fun configureUI(state: HubWaitlistState) {
        val context = context ?: return
        submitButton.setIsLoading(state.waitlistAsync is Loading)
        // Loading is rendered on the button and a failure toasts through the default handler, so only
        // success needs a branch here.
        state.waitlistAsync.handleResponse(context) { success ->
            val activity = activity ?: return@handleResponse
            activity.setResult(Activity.RESULT_OK, HubWaitlistResult(success.response.school).toArgsIntent())
            activity.finish()
        }
    }

    private fun maybeSubmitSchoolName() {
        if (validationManager.validate(showErrors = true)) {
            viewModel.joinWaitlist(schoolInput.text?.toString().orEmpty())
        }
        hideKeyboard(view)
    }

    private companion object {
        const val SCHOOL_NAME_FIELD = "school name"
    }
}
