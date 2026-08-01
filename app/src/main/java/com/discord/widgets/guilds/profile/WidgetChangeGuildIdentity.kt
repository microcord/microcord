package com.discord.widgets.guilds.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppDialog
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.databinding.WidgetChangeGuildIdentityBinding
import com.discord.media_picker.MediaPicker
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.google.android.material.button.MaterialButton
import java.util.concurrent.atomic.AtomicBoolean

class WidgetChangeGuildIdentity : AppFragment(R.layout.widget_change_guild_identity) {
    private val discardConfirmed = AtomicBoolean()
    private var bindingText = false
    private var imageSelectedResult: (String) -> Unit = {}

    private val guildId get() = requireArguments().getLong(EXTRA_GUILD_ID)
    private val source get() = requireArguments().getString(EXTRA_SOURCE).orEmpty()
    private val viewModel: WidgetChangeGuildIdentityViewModel by appViewModels {
        WidgetChangeGuildIdentityViewModel(guildId, source)
    }

    private var _binding: WidgetChangeGuildIdentityBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetChangeGuildIdentityBinding.bind(view).also { _binding = it }
        setActionBarTitle(R.string.change_identity)
        setActionBarDisplayHomeAsUpEnabled(false)

        binding.setNicknameText.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!bindingText) viewModel.updateNickname(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.setNicknameText.setEndIconOnClickListener { viewModel.updateNickname("") }
        binding.saveFab.setOnClickListener { viewModel.saveMemberChanges(view.context) }
        binding.getPremiumButton.setOnClickListener {
            AppToast.show(this, R.string.premium_required)
        }
        requireContext().getSharedPreferences("com.discord_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean(USER_GUILD_PROFILE_VIEWED_CACHE_KEY, true).apply()
        showKeyboard(binding.setNicknameText)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetChangeGuildIdentity::class.java, onNext = ::configureUI)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetChangeGuildIdentity::class.java, onNext = ::handleEvent)
    }

    private fun configureUI(state: WidgetChangeGuildIdentityViewModel.ViewState) {
        val binding = _binding ?: return
        val loaded = state as? WidgetChangeGuildIdentityViewModel.ViewState.Loaded ?: return
        binding.setNicknameText.isEnabled = loaded.canChangeNickname
        binding.setNicknameText.hint = getString(
            if (loaded.canChangeNickname) R.string.nickname else R.string.change_identity_modal_change_nickname_disabled,
        )
        binding.setNicknameText.placeholderText = loaded.meUser.username
        binding.setNicknameText.isEndIconVisible = !loaded.currentNickname.isNullOrEmpty()
        val text = loaded.currentNickname.orEmpty()
        if (binding.setNicknameText.editText?.text?.toString() != text) {
            bindingText = true
            binding.setNicknameText.editText?.setText(text)
            binding.setNicknameText.editText?.setSelection(text.length)
            bindingText = false
        }
        IconUtils.setIcon(binding.guildAvatar, loaded.displayedAvatarURL)
        binding.guildAvatarContainer.setOnClickListener {
            if (loaded.shouldUpsell) {
                AppToast.show(this, R.string.premium_required)
            } else {
                val options = buildList {
                    add(getString(R.string.change_guild_member_avatar))
                    if (loaded.displayingGuildAvatar) add(getString(R.string.change_identity_modal_reset_primary_avatar))
                }
                GuildIdentityAvatarSheet.show(childFragmentManager, options) { index ->
                    if (index == 0) {
                        imageSelectedResult = viewModel::updateAvatar
                        openMediaChooser()
                    } else {
                        viewModel.updateAvatar(null)
                    }
                }
            }
        }
        binding.upsellSection.visibility = if (loaded.shouldUpsell) View.VISIBLE else View.GONE
        binding.saveFab.visibility = if (loaded.showSaveFab) View.VISIBLE else View.GONE
        binding.dimmerView.setDimmed(loaded.dimmed)
        setOnBackPressed(handler = { handleBackPressed(loaded) })
    }

    private fun handleBackPressed(state: WidgetChangeGuildIdentityViewModel.ViewState.Loaded): Boolean {
        hideKeyboard(binding.setNicknameText)
        if (!state.isDirty || discardConfirmed.get()) return false
        GuildIdentityDiscardDialog.show(childFragmentManager) {
                discardConfirmed.set(true)
                requireActivity().onBackPressed()
        }
        return true
    }

    private fun handleEvent(event: WidgetChangeGuildIdentityViewModel.Event) = when (event) {
        is WidgetChangeGuildIdentityViewModel.Event.MemberUpdateFailed ->
            AppToast.show(context, event.errorMessage ?: getString(R.string.change_identity_modal_unknown_error))
        WidgetChangeGuildIdentityViewModel.Event.MemberUpdateSucceeded -> {
            AppToast.show(this, R.string.per_guild_identity_saved)
            discardConfirmed.set(true)
            activity?.onBackPressed()
        }
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        if (!mimeType.startsWith("image")) {
            AppToast.show(this, R.string.upload_area_invalid_file_type_help)
        } else if (mimeType == "image/gif") {
            MGImages.requestDataUrl(context, uri, mimeType, imageSelectedResult)
        } else {
            MediaPicker.requestImageCrop(
                mediaPickerProvider(),
                uri,
                MediaPicker.CropTheme(
                    android.graphics.Color.BLACK,
                    android.graphics.Color.BLACK,
                    android.graphics.Color.WHITE,
                    android.graphics.Color.WHITE,
                ),
                maxOutputDimensionPx = 1024,
            )
        }
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        MGImages.requestDataUrl(context, uri, mimeType, imageSelectedResult)
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetChangeGuildIdentity.requireContext()
        override fun getImageFile() = imageFile
        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) =
            this@WidgetChangeGuildIdentity.startActivityForResult(intent, requestCode)
    }

    companion object {
        const val USER_GUILD_PROFILE_VIEWED_CACHE_KEY = "USER_GUILD_PROFILE_VIEWED_CACHE_KEY"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_SOURCE = "com.discord.intent.extra.EXTRA_SOURCE"

        fun launch(guildId: Long, source: String, context: Context) = AppScreenRouter.launch(
            context,
            WidgetChangeGuildIdentity::class.java,
            Intent().putExtra(EXTRA_GUILD_ID, guildId).putExtra(EXTRA_SOURCE, source),
        )
    }
}

internal class GuildIdentityAvatarSheet : AppBottomSheet() {
    var options: List<String> = emptyList()
    var onSelected: ((Int) -> Unit)? = null

    override fun getContentViewResId() = R.layout.bottom_sheet_simple_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        view.findViewById<View>(R.id.widget_simple_bottom_sheet_selector_header).visibility = View.GONE
        view.findViewById<MaxHeightRecyclerView>(R.id.widget_simple_bottom_sheet_selector_recycler).adapter =
            SimpleRecyclerAdapter(options) { inflater, parent ->
                OptionHolder(inflater.inflate(R.layout.bottom_sheet_simple_selector_item, parent, false))
            }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private inner class OptionHolder(view: View) : SimpleRecyclerAdapter.ViewHolder<String>(view) {
        override fun bind(data: String) {
            itemView.findViewById<View>(R.id.select_component_sheet_item_icon).visibility = View.GONE
            itemView.findViewById<View>(R.id.select_component_sheet_item_description).visibility = View.GONE
            itemView.findViewById<TextView>(R.id.select_component_sheet_item_title).apply {
                text = data
                setTextColor(
                    if (bindingAdapterPosition == 1) ContextCompat.getColor(context, R.color.status_red_500)
                    else currentTextColor,
                )
            }
            itemView.setOnClickListener {
                onSelected?.invoke(bindingAdapterPosition)
                dismiss()
            }
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager, options: List<String>, onSelected: (Int) -> Unit) =
            GuildIdentityAvatarSheet().apply {
                this.options = options
                this.onSelected = onSelected
            }.show(fragmentManager, GuildIdentityAvatarSheet::class.java.name)
    }
}

internal class GuildIdentityDiscardDialog : AppDialog(R.layout.view_dialog_confirmation) {
    var onConfirm: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_header).setText(R.string.discard_changes)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text)
            .setText(R.string.discard_changes_description)
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(R.string.okay)
            setOnClickListener {
                dismiss()
                onConfirm?.invoke()
            }
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager, onConfirm: () -> Unit) =
            GuildIdentityDiscardDialog().apply { this.onConfirm = onConfirm }
                .show(fragmentManager, GuildIdentityDiscardDialog::class.java.name)
    }
}
