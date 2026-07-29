package com.discord.widgets.botuikit

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import com.discord.R
import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.TextComponent
import com.discord.api.botuikit.TextStyle
import com.discord.api.interaction.InteractionModalCreate
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.BotUikitTextInputBinding
import com.discord.databinding.ViewInteractionModalBinding
import com.discord.i18n.i18nFormat
import com.discord.i18n.setI18nText
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher

class ModalComponent : AppFragment(R.layout.view_interaction_modal) {

    private var sentNonce: String? = null
    private var binding: ViewInteractionModalBinding? = null

    @Suppress("DEPRECATION")
    private val options: Options
        get() = requireNotNull(arguments?.getParcelable(EXTRA_OPTIONS))

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = ViewInteractionModalBinding.bind(view).also { this.binding = it }
        val modal = options.modal

        binding.title.text = modal.title
        binding.icon.contentDescription = modal.application.name
        modal.application.icon?.let { icon ->
            IconUtils.setIcon(binding.icon, IconUtils.getApplicationIcon(modal.application.id, icon))
        }
        binding.warning.setI18nText(
            R.string.interaction_form_submission_application,
            "applicationName" to modal.application.name,
        )

        val inputs = modal.components.filterIsInstance<ActionRowComponent>().mapNotNull { row ->
            (row.components.firstOrNull() as? TextComponent)?.let { component ->
                component to createInput(component)
            }
        }
        inputs.forEach { binding.components.addView(it.second.root) }

        binding.close.setOnClickListener { activity?.finish() }
        binding.submit.setOnClickListener {
            val values = inputs.map { (component, input) ->
                component to input.editText.text?.toString().orEmpty()
            }
            var valid = true
            values.forEachIndexed { index, (component, value) ->
                if (!accepts(component, value)) {
                    inputs[index].second.layout.error = requireContext().i18nFormat(
                        R.string.form_length_error,
                        "min" to component.minLength,
                        "max" to component.maxLength,
                    )
                    valid = false
                }
            }
            if (!valid) return@setOnClickListener

            sentNonce = null
            binding.error.visibility = View.GONE
            binding.submit.isEnabled = false
            StoreStream.getInteractions().sendModalInteraction(
                modal,
                values.map { (component, value) -> component.customId to value },
                onNext = { sentNonce = it },
                onError = {
                    view.post {
                        sentNonce = null
                        binding.error.visibility = View.VISIBLE
                        binding.submit.isEnabled = true
                    }
                },
            )
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getInteractions().observeSentInteractions()
            .ui(this)
            .appSubscribe(ModalComponent::class.java) { interactions ->
                when (completion(sentNonce, interactions)) {
                    Completion.FAILURE -> {
                        sentNonce = null
                        binding?.error?.visibility = View.VISIBLE
                        binding?.submit?.isEnabled = true
                    }

                    Completion.SUCCESS -> activity?.finish()
                    null -> Unit
                }
            }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun createInput(component: TextComponent): BotUikitTextInputBinding =
        BotUikitTextInputBinding.inflate(LayoutInflater.from(requireContext())).apply {
            label.text = component.label
            layout.counterMaxLength = component.maxLength
            layout.addBindedTextWatcher(this@ModalComponent) { layout.error = null }
            editText.setText(component.value)
            editText.hint = component.placeholder
            if (component.style == TextStyle.PARAGRAPH) {
                editText.maxLines = 5
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.minHeight = dpToPixels(96)
            } else {
                editText.maxLines = 1
                editText.inputType = InputType.TYPE_CLASS_TEXT
            }
        }

    data class Options(val modal: InteractionModalCreate) : Parcelable {
        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeSerializable(modal)

        companion object CREATOR : Parcelable.Creator<Options> {
            @Suppress("DEPRECATION")
            override fun createFromParcel(parcel: Parcel) =
                Options(requireNotNull(parcel.readSerializable() as? InteractionModalCreate))

            override fun newArray(size: Int): Array<Options?> = arrayOfNulls(size)
        }
    }

    internal enum class Completion { FAILURE, SUCCESS }

    companion object {
        private const val EXTRA_OPTIONS = "extra_options"

        fun show(context: Context, options: Options) {
            AppScreenRouter.launch(
                context,
                ModalComponent::class.java,
                Intent().putExtra(EXTRA_OPTIONS, options),
            )
        }

        fun autoOpen(host: AppFragment) {
            StoreStream.getInteractions().observeModalCreate()
                .ui(host)
                .appSubscribe(host.javaClass) { modal ->
                    host.context?.let { show(it, Options(modal)) }
                }
        }

        internal fun accepts(component: TextComponent, value: String): Boolean =
            if (value.isEmpty()) component.required != true
            else value.length in component.minLength..component.maxLength

        internal fun completion(
            nonce: String?,
            interactions: Map<String, StoreApplicationInteractions.SentInteraction>,
        ): Completion? = when (nonce?.let(interactions::get)?.state) {
            StoreApplicationInteractions.SentInteractionState.FAILURE -> Completion.FAILURE
            StoreApplicationInteractions.SentInteractionState.SUCCESS -> Completion.SUCCESS
            else -> null
        }
    }
}
