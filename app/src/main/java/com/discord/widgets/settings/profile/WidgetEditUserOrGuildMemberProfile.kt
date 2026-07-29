package com.discord.widgets.settings.profile

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.widget.doAfterTextChanged
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.media_picker.MediaPicker
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.user.Badge
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.atomic.AtomicBoolean

class WidgetEditUserOrGuildMemberProfile : AppFragment(R.layout.widget_settings_user_profile) {
    private val guildId: Long? get() = arguments?.takeIf { it.containsKey(EXTRA_GUILD_ID) }?.getLong(EXTRA_GUILD_ID)
    private val viewModel: SettingsUserProfileViewModel by appViewModels { SettingsUserProfileViewModel(guildId) }
    private val discardConfirmed = AtomicBoolean()

    private var bindingText = false
    private var imageSelectedResult: (String) -> Unit = {}
    private var bannerSelectedResult: (String) -> Unit = {}
    private var avatarColorHex = DEFAULT_BANNER_COLOR

    private lateinit var root: TouchInterceptingCoordinatorLayout
    private lateinit var header: UserProfileHeaderView
    private lateinit var guildExplainer: View
    private lateinit var guildHelp: TextView
    private lateinit var nickContainer: View
    private lateinit var nickname: TextInputLayout
    private lateinit var bioHeader: TextView
    private lateinit var bioHelp: TextView
    private lateinit var bioPreviewCard: CardView
    private lateinit var bioPreview: LinkifiedTextView
    private lateinit var bioEditorCard: CardView
    private lateinit var bioEditor: TextInputEditText
    private lateinit var bioEditorWrap: TextInputLayout
    private lateinit var save: FloatingActionButton
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        root = view as TouchInterceptingCoordinatorLayout
        header = view.findViewById(R.id.user_settings_profile_header_view)
        guildExplainer = view.findViewById(R.id.guild_profile_explainer_container)
        guildHelp = view.findViewById(R.id.guild_member_profile_help_text_overall)
        nickContainer = view.findViewById(R.id.nick_container)
        nickname = view.findViewById(R.id.set_nickname_text)
        bioHeader = view.findViewById(R.id.bio_header)
        bioHelp = view.findViewById(R.id.bio_help_text)
        bioPreviewCard = view.findViewById(R.id.bio_preview_card)
        bioPreview = view.findViewById(R.id.bio_preview_text)
        bioEditorCard = view.findViewById(R.id.bio_editor_card)
        bioEditor = view.findViewById(R.id.bio_editor_text_input_field)
        bioEditorWrap = view.findViewById(R.id.bio_editor_text_input_field_wrap)
        save = view.findViewById(R.id.save_fab)
        dimmer = view.findViewById(R.id.dimmer_view)

        if (guildId == null) {
            setActionBarTitle(R.string.user_settings_user_profile)
            setActionBarSubtitle(R.string.user_settings)
        } else {
            setActionBarTitle(R.string.change_identity_server_profile)
        }
        setActionBarDisplayHomeAsUpEnabled()

        nickname.editText?.doAfterTextChanged {
            if (!bindingText) viewModel.updateNickname(it?.toString().orEmpty())
        }
        nickname.setEndIconOnClickListener { viewModel.updateNickname("") }
        bioEditor.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        bioPreviewCard.setOnClickListener { viewModel.updateIsEditingBio(true) }
        save.setOnClickListener {
            setCurrentBioFromEditor()
            dimmer.setDimmed(true)
            viewModel.saveChanges(view.context)
        }
        root.onInterceptTouchEvent = ::finishBioEditOutside
        header.setOnAvatarRepresentativeColorUpdated { color -> avatarColorHex = colorHex(color) }
        header.setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java, onNext = ::configureUI)
        viewModel.observeHeaderViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java) { state ->
                (state as? com.discord.widgets.user.profile.UserProfileHeaderViewModel.ViewState.Loaded)
                    ?.let(header::updateViewState)
            }
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java) {
                dimmer.setDimmed(false)
            }
    }

    private fun configureUI(state: SettingsUserProfileViewModel.ViewState) {
        val loaded = state as SettingsUserProfileViewModel.ViewState.Loaded
        loaded.guild?.let { setActionBarSubtitle(it.name) }
        guildExplainer.visibility = visible(loaded.guild != null)
        loaded.guild?.let {
            guildHelp.text = getString(R.string.change_identity_help_text_overall)
                .replace("{guildName}", it.name)
                .replace("!!", "")
        }

        nickContainer.visibility = visible(loaded.guild != null)
        nickname.isEnabled = loaded.canEditNickname
        nickname.hint = getString(
            if (loaded.canEditNickname) R.string.nickname else R.string.change_identity_modal_change_nickname_disabled,
        )
        nickname.placeholderText = loaded.user.username
        nickname.isEndIconVisible = !loaded.currentNickname.isNullOrEmpty()
        val nick = loaded.currentNickname.orEmpty()
        if (nickname.editText?.text?.toString() != nick) {
            bindingText = true
            nickname.editText?.setText(nick)
            nickname.editText?.setSelection(nick.length)
            bindingText = false
        }

        bioHeader.setText(if (loaded.meMember == null) R.string.user_popout_about_me else R.string.change_identity_bio_header)
        bioHelp.visibility = visible(loaded.meMember != null)
        bioEditorCard.visibility = visible(loaded.showBioEditor)
        bioPreviewCard.visibility = visible(!loaded.showBioEditor)
        if (loaded.isEditingBio && bioEditor.text?.toString() != loaded.currentBio.orEmpty()) {
            bioEditor.setText(loaded.currentBio.orEmpty())
            bioEditor.setSelection(bioEditor.length())
            bioEditor.requestFocus()
            showKeyboard(bioEditorWrap)
        } else if (!loaded.showBioEditor) {
            loaded.bioAst?.let { ast ->
                bioPreview.text = AstRenderer.render(
                    ast,
                    MessageRenderContext(
                        requireContext(),
                        loaded.user.id,
                        true,
                        spoilerOnClick = viewModel::handleBioIndexClicked,
                    ),
                )
            }
        }

        configureAvatar(loaded)
        header.setOnBannerPress { configureBanner(loaded) }
        save.visibility = visible(loaded.showSaveFab)
        setOnBackPressed(handler = { handleBackPressed(loaded) })
    }

    private fun configureAvatar(state: SettingsUserProfileViewModel.ViewState.Loaded) {
        header.setOnAvatarEdit {
            val options = buildList {
                add(getString(if (state.meMember == null) R.string.change_avatar else R.string.change_guild_member_avatar))
                if (state.hasAvatarForDisplay) {
                    add(getString(if (state.meMember == null) R.string.user_settings_remove_avatar else R.string.guild_identity_delete_avatar))
                }
            }
            AlertDialog.Builder(requireContext()).setItems(options.toTypedArray()) { _, index ->
                if (index == 0) {
                    imageSelectedResult = { data ->
                        if (!state.canHaveAnimatedAvatars && data.startsWith("data:image/gif")) {
                            AppToast.show(this, R.string.premium_required)
                        } else {
                            viewModel.updateAvatar(data)
                        }
                    }
                    openMediaChooser()
                } else {
                    viewModel.updateAvatar(null)
                }
            }.show()
        }
    }

    private fun configureBanner(state: SettingsUserProfileViewModel.ViewState.Loaded) {
        bannerSelectedResult = viewModel::updateBannerImage
        WidgetEditProfileBannerSheet.show(
            guildId = guildId,
            defaultBannerColorHex = avatarColorHex,
            initialColorPreviewHex = state.nonDefaultColorPreviewHex,
            hasBannerImage = state.hasBannerImageForDisplay,
            fragmentManager = parentFragmentManager,
            onChangeProfileBannerImage = {
                imageSelectedResult = bannerSelectedResult
                openMediaChooser()
            },
            onRemoveProfileBannerImage = { viewModel.updateBannerImage(null) },
            onColorSelected = { color -> viewModel.updateBannerColor(color?.let(::colorHex)) },
        )
    }

    private fun setCurrentBioFromEditor() {
        val state = viewModel.viewState as? SettingsUserProfileViewModel.ViewState.Loaded ?: return
        if (!state.isEditingBio) return
        viewModel.updateBio(bioEditor.text?.toString().orEmpty())
        viewModel.updateIsEditingBio(false)
        bioEditor.clearFocus()
        hideKeyboard(bioEditorWrap)
    }

    private fun finishBioEditOutside(event: MotionEvent?): Boolean {
        if (event?.actionMasked != MotionEvent.ACTION_UP && event?.actionMasked != MotionEvent.ACTION_CANCEL) return false
        val position = IntArray(2)
        bioEditorWrap.getLocationOnScreen(position)
        val outside = event.rawX !in position[0].toFloat()..(position[0] + bioEditorWrap.width).toFloat() ||
            event.rawY !in position[1].toFloat()..(position[1] + bioEditorWrap.height).toFloat()
        if (outside && bioEditorCard.visibility == View.VISIBLE) setCurrentBioFromEditor()
        return false
    }

    private fun handleBackPressed(state: SettingsUserProfileViewModel.ViewState.Loaded): Boolean {
        if (state.isEditingBio) {
            setCurrentBioFromEditor()
            return true
        }
        if (!state.isDirty || discardConfirmed.get()) return false
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.discard_changes)
            .setMessage(R.string.discard_changes_description)
            .setNegativeButton(R.string.discard_changes_cancel, null)
            .setPositiveButton(R.string.discard_changes_confirm) { _, _ ->
                discardConfirmed.set(true)
                requireActivity().onBackPressed()
            }
            .show()
        return true
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        if (!mimeType.startsWith("image")) {
            AppToast.show(this, R.string.upload_area_invalid_file_type_help)
        } else if (mimeType == "image/gif") {
            val size = runCatching { requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
                .getOrNull() ?: -1L
            if (size >= MAX_BANNER_FILE_SIZE_MB * 1024L * 1024L) {
                AppToast.show(
                    this,
                    getString(R.string.upload_area_too_large_help).replace("{maxSize}", "$MAX_BANNER_FILE_SIZE_MB MB"),
                )
            } else {
                MGImages.requestDataUrl(context, uri, mimeType, imageSelectedResult)
            }
        } else {
            val banner = imageSelectedResult === bannerSelectedResult
            MediaPicker.requestImageCrop(
                mediaPickerProvider(),
                uri,
                MediaPicker.CropTheme(Color.BLACK, Color.BLACK, Color.WHITE, Color.WHITE),
                aspectRatioX = if (banner) 5f else 1f,
                aspectRatioY = if (banner) 2f else 1f,
                maxOutputDimensionPx = if (banner) MAX_BANNER_IMAGE_SIZE else MAX_AVATAR_SIZE,
            )
        }
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        MGImages.requestDataUrl(context, uri, mimeType, imageSelectedResult)
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetEditUserOrGuildMemberProfile.requireContext()
        override fun getImageFile() = imageFile
        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) =
            this@WidgetEditUserOrGuildMemberProfile.startActivityForResult(intent, requestCode)
    }

    companion object {
        const val MAX_AVATAR_SIZE = 1024
        const val MAX_BANNER_FILE_SIZE_MB = 30
        const val MAX_BANNER_IMAGE_SIZE = 1080
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val DEFAULT_BANNER_COLOR = "#000000"

        fun launch(context: Context, guildId: Long? = null) {
            val extras = Intent().apply { guildId?.let { putExtra(EXTRA_GUILD_ID, it) } }
            AppScreenRouter.launch(context, WidgetEditUserOrGuildMemberProfile::class.java, extras)
        }

        private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
        private fun colorHex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
    }
}
