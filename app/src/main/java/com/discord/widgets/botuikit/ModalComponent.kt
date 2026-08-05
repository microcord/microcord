package com.discord.widgets.botuikit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.CheckboxComponent
import com.discord.api.botuikit.CheckboxGroupComponent
import com.discord.api.botuikit.Component
import com.discord.api.botuikit.ComponentOption
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.FileUploadComponent
import com.discord.api.botuikit.LabelComponent
import com.discord.api.botuikit.RadioGroupComponent
import com.discord.api.botuikit.SelectComponent
import com.discord.api.botuikit.SelectItem
import com.discord.api.botuikit.TextComponent
import com.discord.api.botuikit.TextDisplayComponent
import com.discord.api.botuikit.TextStyle
import com.discord.api.interaction.InteractionModalCreate
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.BotUikitModalCheckboxBinding
import com.discord.databinding.BotUikitModalLabelBinding
import com.discord.databinding.BotUikitModalSelectBinding
import com.discord.databinding.BotUikitModalTextDisplayBinding
import com.discord.databinding.BotUikitTextInputBinding
import com.discord.databinding.ViewInteractionModalBinding
import com.discord.i18n.i18nFormat
import com.discord.i18n.setI18nText
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreApplicationInteractions
import com.discord.stores.StoreStream
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.widgets.botuikit.views.select.ComponentContext
import com.discord.widgets.botuikit.views.select.SelectComponentBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

class ModalComponent : AppFragment(R.layout.view_interaction_modal) {

    private var sentNonce: String? = null
    private var binding: ViewInteractionModalBinding? = null
    private val fields = mutableListOf<ModalField>()
    private val selections = mutableMapOf<String, SelectionField>()
    private val fileFields = mutableMapOf<String, FileField>()
    private var activeFileField: FileField? = null
    private var restoredState: Bundle? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val field = activeFileField ?: return@registerForActivityResult
        if (uris.isNotEmpty()) loadFiles(field, uris.take(field.max))
    }

    @Suppress("DEPRECATION")
    private val options: Options
        get() = requireNotNull(arguments?.getParcelable(EXTRA_OPTIONS))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoredState = savedInstanceState
        super.onViewCreated(view, savedInstanceState)
    }

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

        parentFragmentManager.setFragmentResultListener(
            SelectComponentBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            val customId = result.getString(SelectComponentBottomSheet.RESULT_CUSTOM_ID) ?: return@setFragmentResultListener
            selections[customId]?.apply {
                values = result.getStringArrayList(SelectComponentBottomSheet.RESULT_VALUES).orEmpty()
                refresh()
            }
        }

        fields.clear()
        selections.clear()
        fileFields.clear()
        modal.components.mapNotNullTo(fields, ::createField)
        restoreFields()
        fields.forEach { binding.components.addView(it.root) }

        binding.close.setOnClickListener { activity?.finish() }
        binding.submit.setOnClickListener {
            if (!fields.map(ModalField::validate).all { it }) return@setOnClickListener
            submit(fields, modal)
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
        fields.clear()
        selections.clear()
        fileFields.clear()
        activeFileField = null
        restoredState = null
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fields.flatMap(ModalField::flatten).forEach { field ->
            when (field) {
                is TextField -> outState.putString(stateKey(field.component.customId), field.input.editText.text?.toString())
                is SelectionField -> outState.putStringArrayList(stateKey(field.customId), ArrayList(field.values))
                is CheckboxField -> outState.putBoolean(stateKey(field.component.customId), field.checkbox.isChecked)
                is FileField -> outState.putStringArrayList(
                    stateKey(field.component.customId),
                    ArrayList(
                        (field.attachments.map(UploadAttachment::uri).ifEmpty { field.pendingUris })
                            .map(Uri::toString),
                    ),
                )
                else -> Unit
            }
        }
        activeFileField?.let { outState.putString(STATE_ACTIVE_FILE, it.component.customId) }
    }

    private fun createField(component: Component): ModalField? = when (component) {
        is ActionRowComponent -> component.components.firstOrNull()?.let(::createField)?.let { child ->
            LayoutField(
                component,
                child.root,
                child,
                wrapsSingleComponent = false,
            )
        }
        is LabelComponent -> createField(component.component)?.let { child ->
            val label = BotUikitModalLabelBinding.inflate(LayoutInflater.from(requireContext()))
            label.label.text = component.label
            label.description.text = component.description
            label.description.visibility = if (component.description.isNullOrBlank()) View.GONE else View.VISIBLE
            if (child is TextField) child.input.label.visibility = View.GONE
            label.component.addView(child.root)
            LayoutField(component, label.root, child, wrapsSingleComponent = true)
        }
        is TextDisplayComponent -> {
            val display = BotUikitModalTextDisplayBinding.inflate(LayoutInflater.from(requireContext()))
            val rendered = MessageTextRenderer.render(
                Message(id = -options.modal.id, content = component.content),
                display.content,
                parserOptions = DiscordParser.ParserOptions.DEFAULT,
            )
            display.content.setDraweeSpanStringBuilder(rendered.text)
            ContentField(component, display.root)
        }
        is TextComponent -> TextField(component, createInput(component))
        is SelectComponent -> selectionField(component)
        is RadioGroupComponent -> selectionField(component)
        is CheckboxGroupComponent -> selectionField(component)
        is CheckboxComponent -> {
            val checkbox = BotUikitModalCheckboxBinding.inflate(LayoutInflater.from(requireContext()))
            checkbox.checkbox.isChecked = component.default == true
            CheckboxField(component, checkbox.root, checkbox.checkbox)
        }
        is FileUploadComponent -> {
            val select = BotUikitModalSelectBinding.inflate(LayoutInflater.from(requireContext()))
            FileField(component, select.root, select.select).also { field ->
                fileFields[component.customId] = field
                select.select.setText(R.string.select_image_modal_upload_file)
                select.select.setOnClickListener {
                    activeFileField = field
                    filePicker.launch(field.mimeTypes())
                }
            }
        }
        else -> null
    }

    private fun createInput(component: TextComponent): BotUikitTextInputBinding =
        BotUikitTextInputBinding.inflate(LayoutInflater.from(requireContext())).apply {
            label.text = component.label
            layout.counterMaxLength = component.maxLength ?: MAX_TEXT_LENGTH
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

    private fun selectionField(component: SelectComponent): SelectionField = SelectionField(
        type = component.type,
        id = component.id,
        customId = component.customId,
        placeholder = component.placeholder ?: getString(R.string.select),
        min = if (component.required == false) 0 else component.minValues ?: 1,
        max = component.maxValues ?: 1,
        options = selectOptions(component),
        values = component.defaultValues.orEmpty().map { it.id.toString() }
            .ifEmpty { component.options.orEmpty().filter { it.default }.map(SelectItem::value) },
        singleValue = false,
    ).register()

    private fun selectionField(component: RadioGroupComponent): SelectionField = SelectionField(
        type = component.type,
        id = component.id,
        customId = component.customId,
        placeholder = getString(R.string.select),
        min = if (component.required == false) 0 else 1,
        max = 1,
        options = component.options.map { it.toSelectItem() },
        values = component.options.filter { it.default == true }.map(ComponentOption::value),
        singleValue = true,
    ).register()

    private fun selectionField(component: CheckboxGroupComponent): SelectionField = SelectionField(
        type = component.type,
        id = component.id,
        customId = component.customId,
        placeholder = getString(R.string.select),
        min = if (component.required == false) 0 else component.minValues ?: 1,
        max = component.maxValues ?: component.options.size,
        options = component.options.map { it.toSelectItem() },
        values = component.options.filter { it.default == true }.map(ComponentOption::value),
        singleValue = false,
    ).register()

    private fun SelectionField.register() = apply {
        selections[customId] = this
        button.setOnClickListener { showSelection(this) }
        refresh()
    }

    private fun showSelection(field: SelectionField) {
        val modal = options.modal
        val sent = StoreStream.getInteractions().getSentInteraction(modal.nonce)
        SelectComponentBottomSheet.show(
            fragmentManager = parentFragmentManager,
            componentContext = ComponentContext(
                guildId = sent?.guildId,
                messageId = sent?.messageId ?: 0,
                channelId = sent?.channelId ?: 0,
                messageFlags = null,
                applicationId = modal.application.id,
            ),
            componentIndex = field.id ?: 0,
            customId = field.customId,
            placeholder = field.placeholder,
            min = field.min,
            max = field.max,
            options = field.options,
            selectedItems = field.options.filter { it.value in field.values },
            emojiAnimationsEnabled = false,
        )
    }

    private fun selectOptions(component: SelectComponent): List<SelectItem> {
        if (component.type == ComponentType.SELECT) return component.options.orEmpty()
        val guildId = StoreStream.getInteractions().getSentInteraction(options.modal.nonce)?.guildId
        val defaults = component.defaultValues.orEmpty().mapTo(hashSetOf()) { it.id }
        val items = mutableListOf<SelectItem>()
        if (component.type == ComponentType.USER_SELECT || component.type == ComponentType.MENTIONABLE_SELECT) {
            val users = StoreStream.getUsers().getUsers()
            val userIds = guildId?.let { StoreStream.getGuilds().getMembers()[it]?.keys }
                ?: StoreStream.getChannels().getChannel(
                    StoreStream.getInteractions().getSentInteraction(options.modal.nonce)?.channelId ?: 0,
                )?.recipientIds.orEmpty()
            userIds.mapNotNull(users::get).forEach { user ->
                items += SelectItem(user.username, user.id.toString(), null, null, user.id in defaults)
            }
        }
        if (guildId != null && (component.type == ComponentType.ROLE_SELECT || component.type == ComponentType.MENTIONABLE_SELECT)) {
            StoreStream.getGuilds().getRoles()[guildId].orEmpty().values.forEach { role ->
                items += SelectItem(role.name, role.id.toString(), null, null, role.id in defaults)
            }
        }
        if (guildId != null && component.type == ComponentType.CHANNEL_SELECT) {
            StoreStream.getChannels().getChannelsForGuild(guildId).values
                .filter { component.channelTypes.isNullOrEmpty() || it.type in component.channelTypes }
                .forEach { channel ->
                    items += SelectItem(channel.name ?: channel.id.toString(), channel.id.toString(), null, null, channel.id in defaults)
                }
        }
        return items
    }

    private fun loadFiles(field: FileField, uris: List<Uri>) {
        field.pendingUris = uris
        rx.Observable.from(uris)
            .map { uri ->
                UploadAttachment.from(
                    requireContext().contentResolver,
                    id = 0,
                    uri = uri,
                    displayName = uri.lastPathSegment ?: "attachment",
                )
            }
            .toList()
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                ModalComponent::class.java,
                errorHandler = { showSubmissionError() },
            ) { attachments ->
                field.attachments = attachments
                field.pendingUris = emptyList()
                field.refresh()
            }
    }

    private fun restoreFields() {
        val state = restoredState ?: return
        fields.flatMap(ModalField::flatten).forEach { field ->
            when (field) {
                is TextField -> state.getString(stateKey(field.component.customId))?.let(field.input.editText::setText)
                is SelectionField -> state.getStringArrayList(stateKey(field.customId))?.let {
                    field.values = it
                    field.refresh()
                }
                is CheckboxField -> if (state.containsKey(stateKey(field.component.customId))) {
                    field.checkbox.isChecked = state.getBoolean(stateKey(field.component.customId))
                }
                is FileField -> state.getStringArrayList(stateKey(field.component.customId))
                    ?.map(Uri::parse)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { loadFiles(field, it) }
                else -> Unit
            }
        }
        activeFileField = state.getString(STATE_ACTIVE_FILE)?.let(fileFields::get)
    }

    private fun stateKey(customId: String) = "$STATE_FIELD_PREFIX$customId"

    private fun submit(fields: List<ModalField>, modal: InteractionModalCreate) {
        var nextId = 0L
        val attachments = fields.flatMap(ModalField::flatten).filterIsInstance<FileField>().flatMap { field ->
            field.attachments = field.attachments.map { it.copy(id = nextId++) }
            field.attachments
        }
        val components = fields.map(ModalField::payload)
        setSubmitting(true)
        if (attachments.isEmpty()) {
            send(modal, components, emptyList())
            return
        }
        rx.Observable.fromCallable { SendUtils.buildUploads(requireContext().contentResolver, attachments) }
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                ModalComponent::class.java,
                errorHandler = { showSubmissionError() },
            ) { uploads -> send(modal, components, uploads) }
    }

    private fun send(
        modal: InteractionModalCreate,
        components: List<RestAPIParams.ModalInteractionDataComponent>,
        uploads: List<FileUpload>,
    ) {
        StoreStream.getInteractions().sendModalInteraction(
            modal,
            components,
            uploads,
            onNext = { sentNonce = it },
            onError = { view?.post(::showSubmissionError) },
        )
    }

    private fun setSubmitting(submitting: Boolean) {
        sentNonce = null
        binding?.error?.visibility = View.GONE
        binding?.submit?.isEnabled = !submitting
    }

    private fun showSubmissionError() {
        sentNonce = null
        binding?.error?.visibility = View.VISIBLE
        binding?.submit?.isEnabled = true
    }

    private fun ComponentOption.toSelectItem() =
        SelectItem(label, value, null, description, default == true)

    private sealed class ModalField(open val component: Component, open val root: View) {
        abstract fun payload(): RestAPIParams.ModalInteractionDataComponent
        open fun validate() = true
        open fun flatten(): List<ModalField> = listOf(this)
    }

    private class LayoutField(
        override val component: Component,
        override val root: View,
        private val child: ModalField,
        private val wrapsSingleComponent: Boolean,
    ) : ModalField(component, root) {
        override fun payload() = RestAPIParams.ModalInteractionDataLayout(
            component.type,
            component.id,
            components = if (wrapsSingleComponent) null else listOf(child.payload()),
            component = if (wrapsSingleComponent) child.payload() else null,
        )
        override fun validate() = child.validate()
        override fun flatten() = listOf(this) + child.flatten()
    }

    private class ContentField(
        override val component: Component,
        override val root: View,
    ) : ModalField(component, root) {
        override fun payload() = RestAPIParams.ModalInteractionDataContent(component.type, component.id)
    }

    private class TextField(
        override val component: TextComponent,
        val input: BotUikitTextInputBinding,
    ) : ModalField(component, input.root) {
        override fun payload() = RestAPIParams.ModalInteractionDataText(
            component.type,
            component.id,
            component.customId,
            input.editText.text?.toString().orEmpty(),
        )
        override fun validate(): Boolean {
            val value = input.editText.text?.toString().orEmpty()
            val valid = accepts(component, value)
            input.layout.error = if (valid) null else input.root.context.i18nFormat(
                R.string.form_length_error,
                "min" to (component.minLength ?: 0),
                "max" to (component.maxLength ?: MAX_TEXT_LENGTH),
            )
            return valid
        }
    }

    private inner class SelectionField(
        val type: ComponentType,
        val id: Int?,
        val customId: String,
        val placeholder: String,
        val min: Int,
        val max: Int,
        val options: List<SelectItem>,
        var values: List<String>,
        private val singleValue: Boolean,
    ) : ModalField(
        ComponentRef(type, id),
        BotUikitModalSelectBinding.inflate(LayoutInflater.from(requireContext())).root,
    ) {
        val button: MaterialButton = root as MaterialButton

        override fun payload(): RestAPIParams.ModalInteractionDataComponent = if (singleValue) {
            RestAPIParams.ModalInteractionDataText(type, id, customId, values.firstOrNull().orEmpty())
        } else {
            RestAPIParams.ModalInteractionDataValues(type, id, customId, values)
        }

        override fun validate(): Boolean {
            val valid = values.size in min..max
            button.error = if (valid) null else getString(R.string.component_modal_selection_required, min, max)
            return valid
        }

        fun refresh() {
            button.text = if (values.isEmpty()) placeholder
            else getString(R.string.component_modal_selection_count, values.size)
        }
    }

    private data class ComponentRef(
        override val type: ComponentType,
        override val id: Int?,
    ) : Component

    private class CheckboxField(
        override val component: CheckboxComponent,
        override val root: View,
        val checkbox: MaterialCheckBox,
    ) : ModalField(component, root) {
        override fun payload() = RestAPIParams.ModalInteractionDataBoolean(
            component.type,
            component.id,
            component.customId,
            checkbox.isChecked,
        )
    }

    private inner class FileField(
        override val component: FileUploadComponent,
        override val root: View,
        private val button: MaterialButton,
    ) : ModalField(component, root) {
        val min = if (component.required == true) component.minValues ?: 1 else component.minValues ?: 0
        val max = component.maxValues ?: 10
        var attachments: List<UploadAttachment> = emptyList()
        var pendingUris: List<Uri> = emptyList()

        override fun payload() = RestAPIParams.ModalInteractionDataValues(
            component.type,
            component.id,
            component.customId,
            attachments.map { it.id.toString() },
        )

        override fun validate(): Boolean {
            val validCount = attachments.size in min..max
            val validTypes = attachments.all(::accepts)
            button.error = when {
                !validTypes -> getString(R.string.component_modal_file_type_invalid)
                !validCount -> getString(R.string.component_modal_files_required, min, max)
                else -> null
            }
            return validCount && validTypes
        }

        fun refresh() {
            button.text = if (attachments.isEmpty()) getString(R.string.select_image_modal_upload_file)
            else getString(R.string.component_modal_files_count, attachments.size)
            button.error = if (attachments.all(::accepts)) null
            else getString(R.string.component_modal_file_type_invalid)
        }

        fun mimeTypes(): Array<String> {
            val filters = component.fileTypes.orEmpty()
            if (filters.isEmpty() || filters.any { it.startsWith('.') }) return arrayOf("*/*")
            return filters.mapNotNull {
                when (it) {
                    "image" -> "image/*"
                    "video" -> "video/*"
                    "audio" -> "audio/*"
                    else -> null
                }
            }.distinct().ifEmpty { listOf("*/*") }.toTypedArray()
        }

        private fun accepts(attachment: UploadAttachment): Boolean {
            val filters = component.fileTypes.orEmpty()
            if (filters.isEmpty()) return true
            val name = attachment.fileName.lowercase()
            return filters.any { filter ->
                when (filter) {
                    "image" -> IMAGE_EXTENSIONS.any(name::endsWith)
                    "video" -> VIDEO_EXTENSIONS.any(name::endsWith)
                    "audio" -> AUDIO_EXTENSIONS.any(name::endsWith)
                    else -> filter.startsWith('.') && name.endsWith(filter.lowercase())
                }
            }
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
            if (value.isEmpty()) component.required == false
            else value.length in (component.minLength ?: 0)..(component.maxLength ?: MAX_TEXT_LENGTH)

        internal fun completion(
            nonce: String?,
            interactions: Map<String, StoreApplicationInteractions.SentInteraction>,
        ): Completion? = when (nonce?.let(interactions::get)?.state) {
            StoreApplicationInteractions.SentInteractionState.FAILURE -> Completion.FAILURE
            StoreApplicationInteractions.SentInteractionState.SUCCESS -> Completion.SUCCESS
            else -> null
        }

        private const val MAX_TEXT_LENGTH = 4000
        private const val STATE_ACTIVE_FILE = "modal_active_file"
        private const val STATE_FIELD_PREFIX = "modal_field:"
        private val IMAGE_EXTENSIONS = setOf(".png", ".gif", ".jpg", ".jpeg", ".jfif", ".webp", ".avif")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".mov", ".qt", ".webm")
        private val AUDIO_EXTENSIONS = setOf(".mp3", ".m4a", ".wav", ".ogg", ".opus", ".flac")
    }
}
