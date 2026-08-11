package com.discord.widgets.settings.profile

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.drawable.GradientDrawable
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Nameplate
import com.discord.i18n.I18nHooks
import com.discord.nullserializable.NullSerializable
import com.discord.stores.StoreCollectibles
import androidx.core.view.isVisible
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.settings.premium.PremiumUpsellDialog
import com.discord.views.AvatarDecorationView
import com.discord.views.GuildTagView
import com.discord.views.NameplateView
import com.discord.widgets.user.profile.ProfileEffectView
import com.facebook.drawee.view.SimpleDraweeView
import androidx.core.widget.doAfterTextChanged
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.MediaPickerRequest
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.utilities.user.UserUtils.displayName
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Serializable

class WidgetEditUserOrGuildMemberProfile : AppFragment(R.layout.widget_settings_user_profile) {
    private val guildId: Long? get() = arguments?.takeIf { it.containsKey(EXTRA_GUILD_ID) }?.getLong(EXTRA_GUILD_ID)
    private val viewModel: SettingsUserProfileViewModel by appViewModels { SettingsUserProfileViewModel(guildId) }
    private val discardConfirmed = AtomicBoolean()

    private val chooserResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleMediaPickerActivityResult(MediaPickerRequest.CHOOSER.requestCode, result.resultCode, result.data)
    }
    private val cropResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleMediaPickerActivityResult(MediaPickerRequest.CROP.requestCode, result.resultCode, result.data)
    }

    private var bindingText = false
    private var uploadTarget: UploadTarget? = null
    private var avatarSelection: SelectedImage? = null
    private var bannerSelection: SelectedImage? = null
    private var avatarColorHex = DEFAULT_BANNER_COLOR

    private lateinit var root: TouchInterceptingCoordinatorLayout
    private lateinit var header: UserProfileHeaderView
    private lateinit var guildExplainer: View
    private lateinit var guildHelp: TextView
    private lateinit var nickContainer: View
    private lateinit var nickname: TextInputLayout
    private lateinit var displayNameContainer: View
    private lateinit var displayName: TextInputLayout
    private lateinit var pronouns: TextInputLayout
    private lateinit var pronounsHelp: TextView
    private lateinit var bioHeader: TextView
    private lateinit var bioHelp: TextView
    private lateinit var bioPreviewCard: CardView
    private lateinit var bioPreview: LinkifiedTextView
    private lateinit var bioEditorCard: CardView
    private lateinit var bioEditor: TextInputEditText
    private lateinit var bioEditorWrap: TextInputLayout
    private lateinit var profileEffect: ProfileEffectView
    private lateinit var themeColorsHeader: TextView
    private lateinit var themeColorsCard: CardView
    private lateinit var themeColorsUpsell: MaterialButton
    private lateinit var themeColorPrimary: View
    private lateinit var themeColorAccent: View
    private lateinit var themeColorsReset: TextView
    private lateinit var avatarDecorationHeader: TextView
    private lateinit var nameplateHeader: TextView
    private lateinit var avatarDecorationCard: CardView
    private lateinit var avatarDecorationPreviewAvatar: SimpleDraweeView
    private lateinit var avatarDecorationPreview: AvatarDecorationView
    private lateinit var avatarDecorationValue: TextView
    private lateinit var nameplateCard: CardView
    private lateinit var nameplatePreview: NameplateView
    private lateinit var nameplateValue: TextView
    private lateinit var primaryGuildHeader: TextView
    private lateinit var primaryGuildCard: CardView
    private lateinit var primaryGuildIcon: SimpleDraweeView
    private lateinit var primaryGuildValue: TextView
    private lateinit var primaryGuildTagPreview: GuildTagView
    private lateinit var save: FloatingActionButton
    private lateinit var dimmer: DimmerView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        uploadTarget = savedInstanceState?.getSerializable(STATE_UPLOAD_TARGET) as? UploadTarget
        avatarSelection = savedInstanceState?.getSerializable(STATE_AVATAR_SELECTION) as? SelectedImage
        bannerSelection = savedInstanceState?.getSerializable(STATE_BANNER_SELECTION) as? SelectedImage
        super.onViewCreated(view, savedInstanceState)
        val draft = savedInstanceState?.getSerializable(STATE_DRAFT) as? SettingsUserProfileViewModel.Draft
        draft?.let(viewModel::restoreDraft)
        avatarSelection?.let { restoreSelection(it, UploadTarget.AVATAR, draft) }
        bannerSelection?.let { restoreSelection(it, UploadTarget.BANNER, viewModel.draft() ?: draft) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.draft()?.let { draft ->
            val avatarPending = draft.avatar is com.discord.nullserializable.NullSerializable.Value
            val bannerPending = draft.banner is com.discord.nullserializable.NullSerializable.Value
            outState.putSerializable(
                STATE_DRAFT,
                draft.copy(
                    avatar = if (avatarPending) null else draft.avatar,
                    banner = if (bannerPending) null else draft.banner,
                ),
            )
            if (avatarPending) avatarSelection?.let { outState.putSerializable(STATE_AVATAR_SELECTION, it) }
            if (bannerPending) bannerSelection?.let { outState.putSerializable(STATE_BANNER_SELECTION, it) }
        }
        uploadTarget?.let { outState.putSerializable(STATE_UPLOAD_TARGET, it) }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        root = view as TouchInterceptingCoordinatorLayout
        header = view.findViewById(R.id.user_settings_profile_header_view)
        profileEffect = view.findViewById(R.id.user_settings_profile_effect)
        guildExplainer = view.findViewById(R.id.guild_profile_explainer_container)
        guildHelp = view.findViewById(R.id.guild_member_profile_help_text_overall)
        nickContainer = view.findViewById(R.id.nick_container)
        nickname = view.findViewById(R.id.set_nickname_text)
        displayNameContainer = view.findViewById(R.id.display_name_container)
        displayName = view.findViewById(R.id.set_display_name_text)
        pronouns = view.findViewById(R.id.set_pronouns_text)
        pronounsHelp = view.findViewById(R.id.pronouns_help_text)
        bioHeader = view.findViewById(R.id.bio_header)
        bioHelp = view.findViewById(R.id.bio_help_text)
        bioPreviewCard = view.findViewById(R.id.bio_preview_card)
        bioPreview = view.findViewById(R.id.bio_preview_text)
        bioEditorCard = view.findViewById(R.id.bio_editor_card)
        bioEditor = view.findViewById(R.id.bio_editor_text_input_field)
        bioEditorWrap = view.findViewById(R.id.bio_editor_text_input_field_wrap)
        themeColorsHeader = view.findViewById(R.id.theme_colors_header)
        themeColorsCard = view.findViewById(R.id.theme_colors_card)
        themeColorsUpsell = view.findViewById(R.id.theme_colors_upsell)
        themeColorPrimary = view.findViewById(R.id.theme_color_primary)
        themeColorAccent = view.findViewById(R.id.theme_color_accent)
        themeColorsReset = view.findViewById(R.id.theme_colors_reset)
        avatarDecorationHeader = view.findViewById(R.id.avatar_decoration_header)
        nameplateHeader = view.findViewById(R.id.nameplate_header)
        avatarDecorationCard = view.findViewById(R.id.avatar_decoration_card)
        avatarDecorationPreviewAvatar = view.findViewById(R.id.avatar_decoration_preview_avatar)
        avatarDecorationPreview = view.findViewById(R.id.avatar_decoration_preview)
        avatarDecorationValue = view.findViewById(R.id.avatar_decoration_value)
        nameplateCard = view.findViewById(R.id.nameplate_card)
        nameplatePreview = view.findViewById(R.id.nameplate_preview)
        nameplateValue = view.findViewById(R.id.nameplate_value)
        primaryGuildHeader = view.findViewById(R.id.primary_guild_header)
        primaryGuildCard = view.findViewById(R.id.primary_guild_card)
        primaryGuildIcon = view.findViewById(R.id.primary_guild_icon)
        primaryGuildValue = view.findViewById(R.id.primary_guild_value)
        primaryGuildTagPreview = view.findViewById(R.id.primary_guild_tag_preview)
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
        displayName.editText?.doAfterTextChanged {
            if (!bindingText) viewModel.updateDisplayName(it?.toString().orEmpty())
        }
        displayName.setEndIconOnClickListener { viewModel.updateDisplayName("") }
        pronouns.editText?.doAfterTextChanged {
            if (!bindingText) viewModel.updatePronouns(it?.toString().orEmpty())
        }
        pronouns.setEndIconOnClickListener { viewModel.updatePronouns("") }
        bioEditor.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        save.setOnClickListener {
            setCurrentBioFromEditor()
            dimmer.setDimmed(true)
            viewModel.saveChanges(view.context)
        }
        root.onInterceptTouchEvent = ::finishBioEditOutside
        header.setOnAvatarRepresentativeColorUpdated { color -> avatarColorHex = colorHex(color) }
        header.setOnBadgeClick(Badge.onBadgeClick(parentFragmentManager, requireContext()))
        parentFragmentManager.setFragmentResultListener(
            WidgetEditProfileBannerSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            when (result.getString(WidgetEditProfileBannerSheet.RESULT_ACTION)) {
                WidgetEditProfileBannerSheet.ACTION_CHANGE_IMAGE -> {
                    uploadTarget = UploadTarget.BANNER
                    openProfileMediaChooser()
                }
                WidgetEditProfileBannerSheet.ACTION_REMOVE_IMAGE -> {
                    bannerSelection = null
                    viewModel.updateBannerImage(null)
                }
                // Not gated: owning the effect is what matters, and the picker's own CTA handles
                // a lapsed subscription, exactly as for decorations and nameplates.
                WidgetEditProfileBannerSheet.ACTION_PROFILE_EFFECT ->
                    (viewModel.viewState as? SettingsUserProfileViewModel.ViewState.Loaded)?.let { loaded ->
                        WidgetEditCollectibleSheet.show(
                            parentFragmentManager,
                            WidgetEditCollectibleSheet.Kind.PROFILE_EFFECT,
                            loaded.guild?.id,
                            loaded.previewProfileEffect,
                        )
                    }
                WidgetEditProfileBannerSheet.ACTION_PROFILE_FRAME ->
                    (viewModel.viewState as? SettingsUserProfileViewModel.ViewState.Loaded)?.let { loaded ->
                        WidgetEditCollectibleSheet.show(
                            parentFragmentManager,
                            WidgetEditCollectibleSheet.Kind.PROFILE_FRAME,
                            loaded.guild?.id,
                            loaded.previewProfileFrame,
                        )
                    }
                WidgetEditProfileBannerSheet.ACTION_COLOR -> viewModel.updateBannerColor(
                    result.takeIf { it.getBoolean(WidgetEditProfileBannerSheet.RESULT_HAS_COLOR) }
                        ?.getInt(WidgetEditProfileBannerSheet.RESULT_COLOR)
                        ?.let(::colorHex),
                )
            }
        }
        childFragmentManager.setFragmentResultListener(
            ProfileAvatarSelectorSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getInt(ProfileAvatarSelectorSheet.RESULT_INDEX) == 0) {
                uploadTarget = UploadTarget.AVATAR
                openProfileMediaChooser()
            } else {
                avatarSelection = null
                viewModel.updateAvatar(null)
            }
        }
        parentFragmentManager.setFragmentResultListener(
            ProfileColorPickerDialog.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            val index = when (result.getString(ProfileColorPickerDialog.RESULT_TARGET)) {
                THEME_TARGET_PRIMARY -> 0
                THEME_TARGET_ACCENT -> 1
                else -> return@setFragmentResultListener
            }
            if (!result.getBoolean(ProfileColorPickerDialog.RESULT_HAS_COLOR)) {
                return@setFragmentResultListener viewModel.resetThemeColors()
            }
            val loaded = viewModel.viewState as? SettingsUserProfileViewModel.ViewState.Loaded
                ?: return@setFragmentResultListener
            val (primary, accent) = effectiveThemeColors(loaded)
            val picked = result.getInt(ProfileColorPickerDialog.RESULT_COLOR)
            viewModel.updateThemeColors(
                if (index == 0) listOf(picked, accent) else listOf(primary, picked),
            )
        }
        WidgetEditCollectibleSheet.registerForResult(this) { kind, skuId ->
            when (kind) {
                WidgetEditCollectibleSheet.Kind.AVATAR_DECORATION -> viewModel.selectAvatarDecoration(skuId)
                WidgetEditCollectibleSheet.Kind.NAMEPLATE -> viewModel.selectNameplate(skuId)
                WidgetEditCollectibleSheet.Kind.PROFILE_EFFECT -> viewModel.selectProfileEffect(skuId)
                WidgetEditCollectibleSheet.Kind.PROFILE_FRAME -> viewModel.selectProfileFrame(skuId)
            }
        }
        WidgetPrimaryGuildSheet.bindResult(this) { guildId -> viewModel.selectPrimaryGuild(guildId) }
        childFragmentManager.setFragmentResultListener(
            ProfileDiscardChangesDialog.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            discardConfirmed.set(true)
            requireActivity().onBackPressed()
        }
    }

    /**
     * The server tag row, hidden unless some guild can actually lend one.
     *
     * The reference omits the row entirely rather than showing an empty picker, and the tag is an
     * account-wide identity, so it is absent from the per-guild profile editor too.
     */
    private fun renderPrimaryGuild(loaded: SettingsUserProfileViewModel.ViewState.Loaded) {
        val meId = loaded.user.id
        val eligible = if (loaded.guild != null) {
            emptyList()
        } else {
            WidgetPrimaryGuildSheet.eligibleGuilds(StoreStream.getGuilds().getGuilds().values) { guildId ->
                StoreStream.getGuilds().getMember(guildId, meId)?.pending == true
            }
        }
        val selectedId = loaded.selectedPrimaryGuildId
        val selected = eligible.firstOrNull { it.id == selectedId }
        primaryGuildHeader.isVisible = eligible.isNotEmpty()
        primaryGuildCard.isVisible = eligible.isNotEmpty()
        if (eligible.isEmpty()) return

        primaryGuildIcon.isVisible = selected != null
        if (selected != null) {
            primaryGuildIcon.setImageURI(IconUtils.getForGuild(selected.id, selected.icon))
        }
        primaryGuildValue.text = selected?.name
            ?: getString(R.string.user_settings_primary_guild_tag_placeholder)
        primaryGuildTagPreview.bind(selected?.asWornTag())
        primaryGuildCard.setOnClickListener {
            WidgetPrimaryGuildSheet.show(parentFragmentManager, selectedId)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java, onNext = ::configureUI)
        viewModel.observeHeaderViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java) { state ->
                (state as? com.discord.widgets.user.profile.UserProfileHeaderViewModel.ViewState.Loaded)
                    ?.let { loaded ->
                        header.updateViewState(loaded)
                        // The profile renders the effect over the header, so the editor must too or
                        // an unsaved pick cannot be judged.
                        profileEffect.bind(
                            loaded.profileEffect,
                            loaded.reducedMotionEnabled && !loaded.allowAnimationInReducedMotion,
                        )
                    }
            }
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditUserOrGuildMemberProfile::class.java) {
                dimmer.setDimmed(false)
            }
    }

    /**
     * Primary and accent are written as one pair, so editing either sends both and the reset clears
     * both — the API has no way to express half a theme.
     */
    private fun configureThemeColors(loaded: SettingsUserProfileViewModel.ViewState.Loaded) {
        themeColorsHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, R.drawable.ic_nitro_wheel_16dp, 0,
        )
        // Without Nitro the swatches would advertise avatar-derived colours the profile will never
        // use, so the section is the upsell instead of a preview of something unreachable.
        val canEdit = loaded.canEditThemeColors
        themeColorsCard.visibility = visible(canEdit)
        themeColorsUpsell.visibility = visible(!canEdit)
        themeColorsUpsell.setOnClickListener { showPremiumRequired() }
        if (!canEdit) {
            themeColorsReset.visibility = View.GONE
            return
        }
        val (primary, accent) = effectiveThemeColors(loaded)
        tintSwatch(themeColorPrimary, primary)
        tintSwatch(themeColorAccent, accent)
        themeColorsReset.visibility = visible(loaded.previewThemeColors != null)
        themeColorsReset.setOnClickListener { viewModel.resetThemeColors() }
        themeColorPrimary.setOnClickListener { openThemeColorPicker(loaded, THEME_TARGET_PRIMARY, primary) }
        themeColorAccent.setOnClickListener { openThemeColorPicker(loaded, THEME_TARGET_ACCENT, accent) }
    }

    /**
     * An unset theme shows what the profile actually renders, which the reference derives from the
     * avatar; a placeholder swatch would just look broken.
     */
    private fun effectiveThemeColors(loaded: SettingsUserProfileViewModel.ViewState.Loaded): Pair<Int, Int> {
        loaded.previewThemeColors?.takeIf { it.size == 2 }?.let { return it[0] to it[1] }
        val derived = runCatching { Color.parseColor(avatarColorHex) }
            .getOrDefault(ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundAccent))
        return derived to darken(derived)
    }

    private fun darken(color: Int): Int = FloatArray(3)
        .also { ColorUtils.colorToHSL(color, it) }
        .also { it[2] = it[2] * ACCENT_LIGHTNESS_SCALE }
        .let(ColorUtils::HSLToColor)

    private fun openThemeColorPicker(
        loaded: SettingsUserProfileViewModel.ViewState.Loaded,
        target: String,
        initialColor: Int,
    ) {
        if (!loaded.canEditThemeColors) return showPremiumRequired()
        hideKeyboard()
        ProfileColorPickerDialog.show(parentFragmentManager, initialColor, target)
    }

    private fun tintSwatch(swatch: View, color: Int) {
        (swatch.background as? GradientDrawable)?.mutate()?.let { drawable ->
            (drawable as GradientDrawable).setColor(color)
            swatch.background = drawable
        }
    }

    /**
     * The row shows what the profile will end up with, so in a guild an inherited item still renders
     * and is only labelled as the default.
     */
    private fun configureCollectibles(loaded: SettingsUserProfileViewModel.ViewState.Loaded) {
        val inGuild = loaded.guild != null
        // Per-guild decorations and nameplates are premium-only on the API, so an ungated picker
        // would just earn a rejected save.
        val canEdit = loaded.canEditGuildProfile
        val badge = if (inGuild) R.drawable.ic_nitro_wheel_16dp else 0
        avatarDecorationHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, badge, 0)
        nameplateHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, badge, 0)
        val decoration = loaded.previewAvatarDecoration
        avatarDecorationPreviewAvatar.setImageURI(IconUtils.getForUser(loaded.user))
        avatarDecorationPreview.bind(decoration)
        avatarDecorationValue.text = collectibleLabel(
            decoration,
            loaded.avatarDecorationName,
            isDefault = inGuild && loaded.currentAvatarDecoration.isInherited(loaded.savedAvatarDecoration),
        )
        avatarDecorationCard.setOnClickListener {
            if (!canEdit) return@setOnClickListener showPremiumRequired()
            WidgetEditCollectibleSheet.show(
                parentFragmentManager,
                WidgetEditCollectibleSheet.Kind.AVATAR_DECORATION,
                loaded.guild?.id,
                loaded.currentAvatarDecoration.selectedSkuId(loaded.savedAvatarDecoration?.skuId),
            )
        }

        renderPrimaryGuild(loaded)

        val nameplate = loaded.previewNameplate
        nameplatePreview.bind(nameplate, NameplateView.Placement.MEMBER_LIST, selected = true)
        nameplateValue.text = collectibleLabel(
            nameplate,
            loaded.nameplateName,
            isDefault = inGuild && loaded.currentNameplate.isInherited(loaded.savedNameplate),
        )
        nameplateCard.setOnClickListener {
            if (!canEdit) return@setOnClickListener showPremiumRequired()
            WidgetEditCollectibleSheet.show(
                parentFragmentManager,
                WidgetEditCollectibleSheet.Kind.NAMEPLATE,
                loaded.guild?.id,
                loaded.currentNameplate.selectedSkuId(loaded.savedNameplate?.skuId),
            )
        }
    }

    /** Only "None" when nothing is equipped; an equipped-but-unnamed item is still loading. */
    private fun collectibleLabel(equipped: Any?, productName: String?, isDefault: Boolean): CharSequence {
        val label = productName ?: if (equipped == null) getString(R.string.avatar_decoration_none) else ""
        return if (isDefault && label.isNotEmpty()) {
            I18nHooks.format(requireContext(), R.string.collectibles_default_with_label, mapOf("label" to label))
        } else {
            label
        }
    }

    private fun <T> NullSerializable<T>?.isInherited(saved: T?) =
        this is NullSerializable.Null || (this == null && saved == null)

    private fun <T> NullSerializable<T>?.selectedSkuId(savedSkuId: Long?): Long? = when (this) {
        is NullSerializable.Null -> null
        null -> savedSkuId
        is NullSerializable.Value -> when (val item = value) {
            is AvatarDecorationData -> item.skuId
            is Nameplate -> item.skuId
            else -> null
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

        configureThemeColors(loaded)
        configureCollectibles(loaded)

        nickContainer.visibility = visible(loaded.guild != null)
        // The display name is account-wide, so it has no place on a per-guild profile.
        displayNameContainer.visibility = visible(loaded.guild == null)
        displayName.placeholderText = loaded.user.username
        displayName.isEndIconVisible = !loaded.currentDisplayName.isNullOrEmpty()
        val globalName = loaded.currentDisplayName.orEmpty()
        if (displayName.editText?.text?.toString() != globalName) {
            bindingText = true
            displayName.editText?.setText(globalName)
            displayName.editText?.setSelection(globalName.length)
            bindingText = false
        }
        nickname.isEnabled = loaded.canEditNickname
        nickname.hint = getString(
            if (loaded.canEditNickname) R.string.nickname else R.string.change_identity_modal_change_nickname_disabled,
        )
        nickname.placeholderText = loaded.user.displayName
        nickname.isEndIconVisible = !loaded.currentNickname.isNullOrEmpty()
        val nick = loaded.currentNickname.orEmpty()
        if (nickname.editText?.text?.toString() != nick) {
            bindingText = true
            nickname.editText?.setText(nick)
            nickname.editText?.setSelection(nick.length)
            bindingText = false
        }

        // In a guild, a blank field falls back to the account-wide pronouns.
        pronounsHelp.visibility = visible(loaded.meMember != null)
        pronouns.placeholderText = if (loaded.meMember != null && loaded.defaultPronouns.isNotEmpty()) {
            loaded.defaultPronouns
        } else {
            getString(R.string.profile_customization_pronouns_placeholder)
        }
        pronouns.isEndIconVisible = !loaded.currentPronouns.isNullOrEmpty()
        val currentPronouns = loaded.currentPronouns.orEmpty()
        if (pronouns.editText?.text?.toString() != currentPronouns) {
            bindingText = true
            pronouns.editText?.setText(currentPronouns)
            pronouns.editText?.setSelection(currentPronouns.length)
            bindingText = false
        }

        bioHeader.setText(if (loaded.meMember == null) R.string.user_popout_about_me else R.string.change_identity_bio_header)
        // A per-server bio needs Nitro to *edit*; hiding the section entirely made the setting
        // look absent, so it stays visible and the tap explains itself instead.
        val canEditBio = loaded.canEditGuildProfile
        bioHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (loaded.guild != null) R.drawable.ic_nitro_wheel_16dp else 0, 0,
        )
        bioHeader.visibility = View.VISIBLE
        bioHelp.visibility = visible(loaded.guild != null)
        bioEditorCard.visibility = visible(canEditBio && loaded.showBioEditor)
        bioPreviewCard.visibility = visible(!canEditBio || !loaded.showBioEditor)
        bioPreviewCard.setOnClickListener {
            if (canEditBio) viewModel.updateIsEditingBio(true) else showPremiumRequired()
        }
        bioEditorWrap.placeholderText = loaded.defaultBio.takeIf { loaded.guild != null }
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
        header.setOnBannerPress {
            if (loaded.canEditGuildProfile) configureBanner(loaded) else showPremiumRequired()
        }
        save.visibility = visible(loaded.showSaveFab)
        setOnBackPressed(handler = { handleBackPressed(loaded) })
    }

    private fun configureAvatar(state: SettingsUserProfileViewModel.ViewState.Loaded) {
        header.setOnAvatarEdit {
            if (!state.canEditGuildProfile) return@setOnAvatarEdit showPremiumRequired()
            val options = buildList {
                add(
                    getString(
                        when {
                            state.meMember != null && state.hasAvatarForDisplay -> R.string.change_guild_member_avatar
                            state.meMember != null -> R.string.upload_guild_member_avatar
                            state.hasAvatarForDisplay -> R.string.user_settings_change_avatar
                            else -> R.string.user_settings_upload_avatar
                        },
                    ),
                )
                if (state.hasAvatarForDisplay) {
                    add(
                        getString(
                            if (state.meMember == null) R.string.user_settings_remove_avatar
                            else R.string.change_identity_modal_reset_primary_avatar,
                        ),
                    )
                }
            }
            ProfileAvatarSelectorSheet.show(
                childFragmentManager,
                options.toTypedArray(),
                destructiveIndex = if (state.hasAvatarForDisplay) 1 else -1,
            )
        }
    }

    /** A locked control explains what Nitro unlocks before offering the store. */
    private fun showPremiumRequired() = PremiumUpsellDialog.show(
        parentFragmentManager,
        if (guildId != null) {
            PremiumUpsellDialog.ValueProp.GUILD_IDENTITY
        } else {
            PremiumUpsellDialog.ValueProp.PROFILE_BANNER
        },
        PREMIUM_UPSELL_LOCATION,
    )

    private fun configureBanner(state: SettingsUserProfileViewModel.ViewState.Loaded) {
        WidgetEditProfileBannerSheet.show(
            guildId = guildId,
            defaultBannerColorHex = avatarColorHex,
            initialColorPreviewHex = state.nonDefaultColorPreviewHex,
            hasBannerImage = state.hasBannerImageForDisplay,
            profileEffectName = state.profileEffectName,
            profileFrameName = state.profileFrameName,
            fragmentManager = parentFragmentManager,
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
        ProfileDiscardChangesDialog.show(childFragmentManager)
        return true
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        val target = uploadTarget ?: return
        if (!mimeType.startsWith("image")) {
            uploadTarget = null
            AppToast.show(this, R.string.user_settings_image_upload_filetype_error)
        } else if (mimeType == "image/gif") {
            val size = runCatching { requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
                .getOrNull() ?: -1L
            if (size >= MAX_BANNER_FILE_SIZE_MB * 1024L * 1024L) {
                uploadTarget = null
                AppToast.show(
                    this,
                    getString(R.string.user_settings_image_upload_file_too_large)
                        .replace("{maxFileSizeMB}", MAX_BANNER_FILE_SIZE_MB.toString()),
                )
            } else {
                val stableUri = copyToLocalFile(uri) ?: return
                requestDataUrl(stableUri, mimeType, target)
            }
        } else {
            val banner = target == UploadTarget.BANNER
            MediaPicker.requestImageCrop(
                mediaPickerProvider(),
                uri,
                MediaPicker.CropTheme(Color.BLACK, Color.BLACK, Color.WHITE, Color.WHITE),
                aspectRatioX = if (banner) 5f else 1f,
                aspectRatioY = if (banner) 2f else 1f,
                maxOutputDimensionPx = if (banner) MAX_BANNER_IMAGE_SIZE else MAX_AVATAR_SIZE,
                onError = {
                    uploadTarget = null
                    AppToast.show(this, R.string.default_failure_to_perform_action_message)
                },
            )
        }
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        uploadTarget?.let { requestDataUrl(uri, "image/png", it) }
    }

    private fun requestDataUrl(uri: Uri, mimeType: String, target: UploadTarget) {
        MGImages.requestDataUrl(
            context,
            uri,
            mimeType,
            resultCallback = { data ->
                when (target) {
                    UploadTarget.AVATAR -> {
                        val state = viewModel.viewState as? SettingsUserProfileViewModel.ViewState.Loaded
                        if (data.startsWith("data:image/gif") && state?.canHaveAnimatedAvatars != true) {
                            AppToast.show(this, R.string.premium_required)
                        } else {
                            avatarSelection = SelectedImage(uri.toString(), mimeType)
                            viewModel.updateAvatar(data)
                        }
                    }
                    UploadTarget.BANNER -> {
                        bannerSelection = SelectedImage(uri.toString(), mimeType)
                        viewModel.updateBannerImage(data)
                    }
                }
                uploadTarget = null
            },
            onError = {
                uploadTarget = null
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
            },
        )
    }

    private fun restoreSelection(
        selection: SelectedImage,
        target: UploadTarget,
        draft: SettingsUserProfileViewModel.Draft?,
    ) {
        val base = draft ?: return
        MGImages.requestDataUrl(
            context,
            Uri.parse(selection.uri),
            selection.mimeType,
            resultCallback = { data ->
                viewModel.restoreDraft(
                    when (target) {
                        UploadTarget.AVATAR -> base.copy(avatar = com.discord.nullserializable.NullSerializable.Value(data))
                        UploadTarget.BANNER -> base.copy(banner = com.discord.nullserializable.NullSerializable.Value(data))
                    },
                )
            },
            onError = { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
        )
    }

    private fun copyToLocalFile(uri: Uri): Uri? = runCatching {
        val file = imageFile
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use(input::copyTo)
        } ?: error("Unable to open selected image")
        Uri.fromFile(file)
    }.getOrElse {
        uploadTarget = null
        AppToast.show(this, R.string.default_failure_to_perform_action_message)
        null
    }

    private fun openProfileMediaChooser() {
        requestMedia {
            MediaPicker.openMediaChooser(
                mediaPickerProvider(),
                getString(R.string.choose_an_application),
                "image/*",
            ) {
                uploadTarget = null
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
            }
        }
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetEditUserOrGuildMemberProfile.requireContext()
        override fun getImageFile() = imageFile
        override fun startActivityForResult(intent: Intent, requestCode: Int) = when (requestCode) {
            MediaPickerRequest.CHOOSER.requestCode -> chooserResult.launch(intent)
            MediaPickerRequest.CROP.requestCode -> cropResult.launch(intent)
            else -> error("Unsupported profile media request code: $requestCode")
        }
    }

    companion object {
        private const val PREMIUM_UPSELL_LOCATION = "User Profile Edit"
        private const val THEME_TARGET_PRIMARY = "theme_primary"
        private const val THEME_TARGET_ACCENT = "theme_accent"
        private const val ACCENT_LIGHTNESS_SCALE = 0.7f
        const val MAX_AVATAR_SIZE = 1024
        const val MAX_BANNER_FILE_SIZE_MB = 30
        const val MAX_BANNER_IMAGE_SIZE = 1080
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val DEFAULT_BANNER_COLOR = "#000000"
        private const val STATE_DRAFT = "profile_draft"
        private const val STATE_UPLOAD_TARGET = "profile_upload_target"
        private const val STATE_AVATAR_SELECTION = "profile_avatar_selection"
        private const val STATE_BANNER_SELECTION = "profile_banner_selection"

        fun launch(context: Context, guildId: Long? = null) {
            val extras = Intent().apply { guildId?.let { putExtra(EXTRA_GUILD_ID, it) } }
            AppScreenRouter.launch(context, WidgetEditUserOrGuildMemberProfile::class.java, extras)
        }

        private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
        private fun colorHex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
    }

    private enum class UploadTarget : Serializable { AVATAR, BANNER }
    private data class SelectedImage(val uri: String, val mimeType: String) : Serializable
}
