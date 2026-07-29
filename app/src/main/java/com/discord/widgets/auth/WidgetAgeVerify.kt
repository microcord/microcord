package com.discord.widgets.auth

import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppViewFlipper
import com.discord.views.LoadingButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date
import rx.Subscription

class WidgetAgeVerify : AuthFragment(R.layout.widget_age_verify, AuthRoute.AGE_VERIFY) {
    private val flipper get() = requireView().findViewById<AppViewFlipper>(R.id.age_verify_view_flipper)
    private var ageStateSubscription: Subscription? = null
    private var ageEventSubscription: Subscription? = null

    private val ageModel: WidgetAgeVerifyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WidgetAgeVerifyViewModel(
                storeObservable = WidgetAgeVerifyViewModel.observeStores(),
                submitAge = { birthday, callback ->
                    actions.submit(AuthAction.VerifyAge(birthday), callback = callback)
                },
            ) as T
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextInputLayout>(R.id.age_verify_input_wrapper).apply {
            editText?.isFocusable = false
            editText?.setOnClickListener {
                chooseBirthday(ageModel.requireViewState().dateOfBirth, ageModel::setDateOfBirth)
            }
        }
        view.findViewById<LoadingButton>(R.id.age_verify_next_button)
            .setOnClickListener { ageModel.submit(isNsfwChannel) }
        view.findViewById<LoadingButton>(R.id.age_verify_confirm_button)
            .setOnClickListener { ageModel.submit(isNsfwChannel) }
        view.findViewById<MaterialButton>(R.id.age_verify_confirm_back_button)
            .setOnClickListener { ageModel.onConfirmBackClicked() }
        view.findViewById<MaterialButton>(R.id.age_verify_return_to_login_button)
            .setOnClickListener { actions.close() }
        setOnBackPressed(handler = {
            when (ageModel.requireViewState().displayedChild) {
                WidgetAgeVerifyViewModel.Views.VIEW_AGE_CONFIRM -> ageModel.onConfirmBackClicked()
                WidgetAgeVerifyViewModel.Views.VIEW_AGE_GATED -> actions.close()
                else -> ageModel.backToSafety()
            }
            true
        })
    }

    override fun onStart() {
        super.onStart()
        ageStateSubscription = ageModel.observeViewState().subscribe(::renderAge)
        ageEventSubscription = ageModel.observeEvents().subscribe {
            Toast.makeText(context, R.string.age_gate_age_verified, Toast.LENGTH_SHORT).show()
            if (parentFragmentManager.backStackEntryCount > 0) actions.close()
        }
    }

    override fun onStop() {
        ageStateSubscription?.unsubscribe()
        ageEventSubscription?.unsubscribe()
        ageStateSubscription = null
        ageEventSubscription = null
        super.onStop()
    }

    private fun renderAge(state: WidgetAgeVerifyViewModel.ViewState) {
        configureViewFlipper(state.displayedChild)
        val input = requireView().findViewById<TextInputLayout>(R.id.age_verify_input_wrapper)
        input.editText?.setText(
            state.dateOfBirth?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty(),
        )
        input.error = state.errorStringId?.let(::getString)
        requireView().findViewById<LoadingButton>(R.id.age_verify_next_button).apply {
            isEnabled = state.dateOfBirth != null
            setIsLoading(state.isSubmitting)
        }
        requireView().findViewById<LoadingButton>(R.id.age_verify_confirm_button).apply {
            isEnabled = state.dateOfBirth != null
            setIsLoading(state.isSubmitting)
        }
        state.dateOfBirth?.let { birthday ->
            requireView().findViewById<TextView>(R.id.age_verify_confirm_title).text =
                getString(R.string.age_gate_confirm_header)
                    .replace("{age}", WidgetAgeVerifyViewModel.age(birthday).toString())
        }
        state.underageMessage?.let {
            requireView().findViewById<TextView>(R.id.age_verify_underage_description).text = it
        }
        if (state.shouldClose && state.displayedChild != WidgetAgeVerifyViewModel.Views.VIEW_AGE_GATED &&
            parentFragmentManager.backStackEntryCount > 0
        ) actions.close()
    }

    private fun configureViewFlipper(displayedChild: Int) {
        if (displayedChild > flipper.displayedChild) {
            flipper.inAnimation = AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_open_in)
            flipper.outAnimation = AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_open_out)
        } else if (displayedChild < flipper.displayedChild) {
            flipper.inAnimation = AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_close_in)
            flipper.outAnimation = AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_close_out)
        }
        flipper.displayedChild = displayedChild
    }

    private val isNsfwChannel: Boolean
        get() = arguments?.getBoolean(INTENT_EXTRA_NSFW_CHANNEL, false) == true

    companion object {
        const val INTENT_EXTRA_NSFW_CHANNEL = "INTENT_EXTRA_NSFW_CHANNEL"
        fun arguments(isNsfwChannel: Boolean) = Bundle().apply {
            putBoolean(INTENT_EXTRA_NSFW_CHANNEL, isNsfwChannel)
        }
    }
}
