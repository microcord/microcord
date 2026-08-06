package com.discord.widgets.nux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.databinding.WidgetNewUserOnboardingBinding
import com.discord.api.user.PatchUserBody
import com.discord.api.friendsuggestions.AllowedInSuggestionsType
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.media_picker.MediaPicker
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreNux
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.contact_sync.ContactSyncMode
import com.discord.widgets.contact_sync.ContactSyncAnalytics
import com.discord.widgets.contact_sync.WidgetContactSync
import com.discord.widgets.guilds.invite.WidgetGuildInvite
import com.facebook.drawee.view.SimpleDraweeView
import com.discord.utilities.view.extensions.getTextOrEmpty

/** Current Android new-user flow recovered from `RedesignNewUserManager`. */
class WidgetNewUserOnboarding : AppFragment(R.layout.widget_new_user_onboarding) {
    private var _binding: WidgetNewUserOnboardingBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var pendingAvatar: String? = null
    private var contactSyncLaunched = false
    private var contactSyncPaused = false
    private var renderedStep: StoreNux.NewUserStep? = null
    private var stepStartedAt: Long? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetNewUserOnboardingBinding.bind(view)
        binding.newUserAvatarNext.setIsLoading(false)
        binding.newUserDiscoverabilityNext.setIsLoading(false)
        binding.newUserDiscoverabilityNameNext.setIsLoading(false)

        binding.newUserAvatarSkip.setOnClickListener { confirmAvatarSkip() }
        binding.newUserAvatarUpload.setOnClickListener { openAvatarChooser() }
        binding.newUserAvatarNext.setOnClickListener { saveAvatar() }
        bindDefaultAvatars(binding.newUserDefaultAvatars)

        binding.newUserNotificationsAllow.setOnClickListener {
            requestNotifications(
                onFailure = { complete(StoreNux.NewUserStep.NOTIFICATIONS) },
                onSuccess = { complete(StoreNux.NewUserStep.NOTIFICATIONS) },
            )
        }
        binding.newUserNotificationsSkip.setOnClickListener {
            complete(StoreNux.NewUserStep.NOTIFICATIONS, skipped = true)
        }

        binding.newUserDiscoverabilityToggle.setChecked(true, false)
        binding.newUserDiscoverabilityToggle.setContainerClickListener {
            binding.newUserDiscoverabilityToggle.setChecked(!binding.newUserDiscoverabilityToggle.isChecked, true)
        }
        binding.newUserDiscoverabilityNext.setOnClickListener { saveDiscoverability() }
        binding.newUserDiscoverabilityNameNext.setOnClickListener { saveDiscoverabilityName() }
        setOnBackPressed({
            requireActivity().moveTaskToBack(true)
            true
        })
        renderNext()
    }

    override fun onResume() {
        super.onResume()
        if (contactSyncLaunched && contactSyncPaused) {
            contactSyncLaunched = false
            contactSyncPaused = false
            complete(StoreNux.NewUserStep.CONTACT_SYNC)
        }
    }

    override fun onPause() {
        if (contactSyncLaunched) contactSyncPaused = true
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        if (mimeType !in setOf("image/png", "image/jpeg", "image/jpg")) {
            AppToast.show(this, R.string.avatar_in_onboarding_file_type_error)
            return
        }
        MediaPicker.requestImageCrop(
            mediaPickerProvider(),
            uri,
            MediaPicker.CropTheme(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            aspectRatioX = 1f,
            aspectRatioY = 1f,
            maxOutputDimensionPx = MAX_AVATAR_SIZE,
            onError = { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
        )
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        MGImages.requestDataUrl(
            context,
            uri,
            "image/png",
            resultCallback = { selectAvatar(it, uri.toString()) },
            onError = { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
        )
    }

    private fun renderNext(extraCompleted: StoreNux.NewUserStep? = null, skipped: Boolean = false) {
        val state = StoreStream.getNux().getNuxStateSnapshot()
        if (state.newUserType == null) return finishFlow()
        val completed = state.completedNewUserSteps + listOfNotNull(extraCompleted)
        val me = StoreStream.getUsers().getMe()
        val step = nextStep(completed, hasAvatar = me.avatar != null, hasNotifications = hasNotificationPermission())
        if (step != renderedStep) {
            ContactSyncAnalytics.Production.newUserOnboarding(
                flow = "Mobile NUX Post Reg",
                fromStep = renderedStep?.analyticsName ?: "Registration",
                toStep = step?.analyticsName ?: "NUF Complete",
                stepStartedAtMs = stepStartedAt,
                skip = skipped,
            )
            renderedStep = step
            stepStartedAt = System.currentTimeMillis()
        }
        when (step) {
            StoreNux.NewUserStep.AVATAR -> {
                binding.newUserOnboardingFlipper.displayedChild = PAGE_AVATAR
                binding.newUserAvatarPreview.setImageURI(IconUtils.getForUser(me))
                binding.newUserAvatarNext.isEnabled = pendingAvatar != null
            }
            StoreNux.NewUserStep.NOTIFICATIONS -> binding.newUserOnboardingFlipper.displayedChild = PAGE_NOTIFICATIONS
            StoreNux.NewUserStep.CONTACT_SYNC -> {
                contactSyncLaunched = true
                WidgetContactSync.launch(requireContext(), ContactSyncMode.ONBOARDING)
            }
            StoreNux.NewUserStep.DISCOVERABILITY -> binding.newUserOnboardingFlipper.displayedChild = PAGE_DISCOVERABILITY
            StoreNux.NewUserStep.INVITE -> finishWithDeferredInvite()
            null -> finishFlow()
        }
    }

    private fun bindDefaultAvatars(container: LinearLayout) {
        repeat(DEFAULT_AVATAR_COUNT) { index ->
            val image = SimpleDraweeView(requireContext()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.avatar_in_onboarding_select_default_avatar)
                setImageURI(IconUtils.getDefaultAvatar(index))
                setOnClickListener {
                    val data = requireContext().assets.open("images/default_avatar_$index.png").use { input ->
                        "data:image/png;base64,${Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)}"
                    }
                    selectAvatar(data, IconUtils.getDefaultAvatar(index))
                }
            }
            val size = (48 * resources.displayMetrics.density).toInt()
            val margin = (4 * resources.displayMetrics.density).toInt()
            container.addView(image, LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) })
        }
    }

    private fun selectAvatar(data: String, previewUri: String) {
        pendingAvatar = data
        binding.newUserAvatarPreview.setImageURI(previewUri)
        binding.newUserAvatarNext.isEnabled = true
    }

    private fun saveAvatar() {
        val data = pendingAvatar ?: return
        val api = RestAPI.apiOrNull ?: return
        binding.newUserAvatarNext.setIsLoading(true)
        api.patchUser(PatchUserBody(avatar = NullSerializable.Value(data)))
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                WidgetNewUserOnboarding::class.java,
                context = context,
                errorHandler = {
                    binding.newUserAvatarNext.setIsLoading(false)
                    it.showErrorToasts = true
                },
            ) { user ->
                StoreStream.getUserProfiles().updateUser(user)
                complete(StoreNux.NewUserStep.AVATAR)
            }
    }

    private fun confirmAvatarSkip() {
        SimpleConfirmationDialog.show(
            childFragmentManager,
            SimpleConfirmationDialogArgs(
                title = getString(R.string.avatar_in_onboarding_skip_step_alert_title),
                description = getString(R.string.avatar_in_onboarding_skip_step_alert_subtitle),
                positiveButtonText = getString(R.string.avatar_in_onboarding_skip_for_now),
                negativeButtonText = getString(R.string.avatar_in_onboarding_add_picture_cta),
            ),
        ) { complete(StoreNux.NewUserStep.AVATAR, skipped = true) }
    }

    private fun saveDiscoverability() {
        val api = RestAPI.apiOrNull ?: return
        binding.newUserDiscoverabilityNext.setIsLoading(true)
        val flags = if (binding.newUserDiscoverabilityToggle.isChecked) DISCOVERABLE_BY_PHONE or DISCOVERABLE_BY_EMAIL else 0
        api.updateUserSettings(RestAPIParams.UserSettings.createWithFriendDiscoveryFlags(flags))
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                WidgetNewUserOnboarding::class.java,
                context = context,
                errorHandler = {
                    binding.newUserDiscoverabilityNext.setIsLoading(false)
                    it.showErrorToasts = true
                },
            ) {
                binding.newUserDiscoverabilityNext.setIsLoading(false)
                val needsName = binding.newUserDiscoverabilityToggle.isChecked && StoreStream.getUsers().getMe().phoneNumber != null &&
                    StoreStream.getUserConnections().getConnectedAccounts().none { it.type == CONTACTS_CONNECTION }
                if (needsName) binding.newUserOnboardingFlipper.displayedChild = PAGE_DISCOVERABILITY_NAME
                else complete(StoreNux.NewUserStep.DISCOVERABILITY)
            }
    }

    private fun saveDiscoverabilityName() {
        val api = RestAPI.apiOrNull ?: return
        val name = binding.newUserDiscoverabilityNameWrap.getTextOrEmpty().trim().ifBlank { null }
        binding.newUserDiscoverabilityNameNext.setIsLoading(true)
        api.createConnectionContacts(RestAPIParams.ConnectedAccountContacts(name, friendSync = true))
            .flatMap {
                api.uploadContacts(
                    RestAPIParams.UploadContacts(
                        friendListEntries = emptyList(),
                        background = true,
                        setUseForSuggestions = AllowedInSuggestionsType.ANYONE_WITH_CONTACT_INFO,
                    ),
                )
            }
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                WidgetNewUserOnboarding::class.java,
                context = context,
                errorHandler = {
                    binding.newUserDiscoverabilityNameNext.setIsLoading(false)
                    it.showErrorToasts = true
                },
            ) { complete(StoreNux.NewUserStep.DISCOVERABILITY) }
    }

    private fun finishWithDeferredInvite() {
        StoreStream.getInviteSettings().getInviteCode().take(1).ui(this)
            .appSubscribe(WidgetNewUserOnboarding::class.java) { invite ->
                StoreStream.getNux().completeNewUserStep(StoreNux.NewUserStep.INVITE)
                clearFlow {
                    if (invite != null) WidgetGuildInvite.launch(requireContext(), invite)
                    requireActivity().finish()
                }
            }
    }

    private fun complete(step: StoreNux.NewUserStep, skipped: Boolean = false) {
        StoreStream.getNux().completeNewUserStep(step)
        renderNext(step, skipped)
    }

    private fun finishFlow() {
        clearFlow { activity?.finish() }
    }

    private fun clearFlow(afterClear: () -> Unit) {
        StoreStream.getNux().getNuxState()
            .filter { it.newUserType == null }
            .take(1)
            .ui(this)
            .appSubscribe(WidgetNewUserOnboarding::class.java) { afterClear() }
        StoreStream.getNux().setNewUserType(null)
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAvatarChooser() = requestMedia {
        MediaPicker.openMediaChooser(
            mediaPickerProvider(),
            getString(R.string.choose_an_application),
            "image/png,image/jpeg",
        ) { AppToast.show(this, R.string.default_failure_to_perform_action_message) }
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetNewUserOnboarding.requireContext()
        override fun getImageFile() = imageFile
        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) =
            this@WidgetNewUserOnboarding.startActivityForResult(intent, requestCode)
    }

    companion object {
        private const val PAGE_AVATAR = 0
        private const val PAGE_NOTIFICATIONS = 1
        private const val PAGE_DISCOVERABILITY = 2
        private const val PAGE_DISCOVERABILITY_NAME = 3
        private const val DEFAULT_AVATAR_COUNT = 6
        private const val MAX_AVATAR_SIZE = 1024
        private const val DISCOVERABLE_BY_PHONE = 1
        private const val DISCOVERABLE_BY_EMAIL = 2
        private const val CONTACTS_CONNECTION = "contacts"

        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetNewUserOnboarding::class.java)

        internal fun nextStep(
            completed: Set<StoreNux.NewUserStep>,
            hasAvatar: Boolean,
            hasNotifications: Boolean,
        ): StoreNux.NewUserStep? = when {
            StoreNux.NewUserStep.AVATAR !in completed && !hasAvatar -> StoreNux.NewUserStep.AVATAR
            StoreNux.NewUserStep.NOTIFICATIONS !in completed && !hasNotifications -> StoreNux.NewUserStep.NOTIFICATIONS
            StoreNux.NewUserStep.CONTACT_SYNC !in completed -> StoreNux.NewUserStep.CONTACT_SYNC
            StoreNux.NewUserStep.DISCOVERABILITY !in completed -> StoreNux.NewUserStep.DISCOVERABILITY
            StoreNux.NewUserStep.INVITE !in completed -> StoreNux.NewUserStep.INVITE
            else -> null
        }
    }

    private val StoreNux.NewUserStep.analyticsName: String get() = when (this) {
        StoreNux.NewUserStep.AVATAR -> "choose-avatar"
        StoreNux.NewUserStep.NOTIFICATIONS -> "enable-notification"
        StoreNux.NewUserStep.CONTACT_SYNC -> "contact-sync"
        StoreNux.NewUserStep.DISCOVERABILITY -> "discoverability"
        StoreNux.NewUserStep.INVITE -> "accept-invite"
    }
}
