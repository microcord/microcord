package com.discord.widgets.guilds.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.media_picker.MediaPicker
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
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

    private lateinit var nickname: TextInputLayout
    private lateinit var avatar: SimpleDraweeView
    private lateinit var avatarContainer: View
    private lateinit var upsell: View
    private lateinit var save: FloatingActionButton
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.change_identity)
        setActionBarDisplayHomeAsUpEnabled(false)
        nickname = view.findViewById(R.id.set_nickname_text)
        avatar = view.findViewById(R.id.guild_avatar)
        avatarContainer = view.findViewById(R.id.guild_avatar_container)
        upsell = view.findViewById(R.id.upsell_section)
        save = view.findViewById(R.id.save_fab)
        dimmer = view.findViewById(R.id.dimmer_view)

        nickname.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!bindingText) viewModel.updateNickname(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        nickname.setEndIconOnClickListener { viewModel.updateNickname("") }
        save.setOnClickListener { viewModel.saveMemberChanges(view.context) }
        view.findViewById<View>(R.id.get_premium_button).setOnClickListener {
            AppToast.show(this, R.string.premium_required)
        }
        requireContext().getSharedPreferences("com.discord_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean(USER_GUILD_PROFILE_VIEWED_CACHE_KEY, true).apply()
        showKeyboard(nickname)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetChangeGuildIdentity::class.java, onNext = ::configureUI)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetChangeGuildIdentity::class.java, onNext = ::handleEvent)
    }

    private fun configureUI(state: WidgetChangeGuildIdentityViewModel.ViewState) {
        val loaded = state as? WidgetChangeGuildIdentityViewModel.ViewState.Loaded ?: return
        nickname.isEnabled = loaded.canChangeNickname
        nickname.hint = getString(
            if (loaded.canChangeNickname) R.string.nickname else R.string.change_identity_modal_change_nickname_disabled,
        )
        nickname.placeholderText = loaded.meUser.username
        nickname.isEndIconVisible = !loaded.currentNickname.isNullOrEmpty()
        val text = loaded.currentNickname.orEmpty()
        if (nickname.editText?.text?.toString() != text) {
            bindingText = true
            nickname.editText?.setText(text)
            nickname.editText?.setSelection(text.length)
            bindingText = false
        }
        IconUtils.setIcon(avatar, loaded.displayedAvatarURL)
        avatarContainer.setOnClickListener {
            if (loaded.shouldUpsell) {
                AppToast.show(this, R.string.premium_required)
            } else {
                val options = buildList {
                    add(getString(R.string.change_avatar))
                    if (loaded.displayingGuildAvatar) add(getString(R.string.change_identity_modal_reset_primary_avatar))
                }
                AlertDialog.Builder(requireContext()).setItems(options.toTypedArray()) { _, index ->
                    if (index == 0) {
                        imageSelectedResult = viewModel::updateAvatar
                        openMediaChooser()
                    } else {
                        viewModel.updateAvatar(null)
                    }
                }.show()
            }
        }
        upsell.visibility = if (loaded.shouldUpsell) View.VISIBLE else View.GONE
        save.visibility = if (loaded.showSaveFab) View.VISIBLE else View.GONE
        dimmer.setDimmed(loaded.dimmed)
        setOnBackPressed(handler = { handleBackPressed(loaded) })
    }

    private fun handleBackPressed(state: WidgetChangeGuildIdentityViewModel.ViewState.Loaded): Boolean {
        hideKeyboard(nickname)
        if (!state.isDirty || discardConfirmed.get()) return false
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.unsaved_changes)
            .setMessage(R.string.discard_changes_description)
            .setNegativeButton(R.string.discard_changes_cancel, null)
            .setPositiveButton(R.string.discard_changes_confirm) { _, _ ->
                discardConfirmed.set(true)
                requireActivity().onBackPressed()
            }
            .show()
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
