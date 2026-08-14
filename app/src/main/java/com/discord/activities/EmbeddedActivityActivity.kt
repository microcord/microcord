package com.discord.activities

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.PowerManager
import android.util.Rational
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.PermissionRequest
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.databinding.ActivityEmbeddedActivityBinding
import com.discord.dialogs.WidgetMaskedLinksDialog
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.uri.UriHandler
import com.discord.views.OAuthPermissionViews
import com.discord.widgets.guilds.invite.WidgetGuildInviteShareSheet
import com.discord.widgets.settings.connections.WidgetSettingsUserConnections
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import com.discord.i18n.i18nRender
import com.discord.i18n.setI18nText

class EmbeddedActivityActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmbeddedActivityBinding
    private var session: EmbeddedActivitySession? = null
    private var webView: EmbeddedActivityWebView? = null
    private var launchSubscription: Subscription? = null
    private var participantSubscription: Subscription? = null
    private var storeSubscription: Subscription? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var crashRecoveryCount = 0
    private var leaveSent = false

    private var pendingImageUpload: ((String?) -> Unit)? = null
    private var imageUploadSubscription: Subscription? = null
    private var pendingAuthorization: (() -> Unit)? = null
    private var trustedLinkSubscription: Subscription? = null
    private var applicationSubscription: Subscription? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uploadPickedImage(uri) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        pendingPermissionRequest?.let { request ->
            val granted = request.resources.all { resource ->
                val permission = if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    Manifest.permission.RECORD_AUDIO
                } else {
                    Manifest.permission.CAMERA
                }
                grants[permission] == true
            }
            if (granted) request.grant(request.resources) else request.deny()
        }
        pendingPermissionRequest = null
    }

    private val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                webView?.onPause()
                showError(getString(R.string.embedded_activity_thermal), retry = true)
            }
        }
    } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmbeddedActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemUi()
        applyChrome()
        listOf(binding.activityClose, binding.activityCloseLandscape).forEach { view ->
            view.setOnClickListener { leaveAndFinish() }
        }
        listOf(binding.activityMinimize, binding.activityMinimizeLandscape).forEach { view ->
            view.setOnClickListener { enterPip() }
        }
        listOf(binding.activityInvite, binding.activityInviteLandscape).forEach { view ->
            view.setOnClickListener { session?.channel?.let(::showInviteSheet) }
        }
        binding.activityChromePortrait.setOnTouchListener(MinimizeDragListener())
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val md = dp(CHROME_PADDING_MD_DP)
            val lg = dp(CHROME_PADDING_LG_DP)
            binding.activityChromePortrait.setPadding(bars.left, bars.top, bars.right, 0)
            binding.activityChromeLandscape.setPadding(md, bars.top + lg, bars.right + md, bars.bottom + lg)
            insets
        }
        binding.activityRetry.setOnClickListener { startSession() }
        startSession(savedInstanceState?.getString(STATE_INSTANCE_ID))
    }

    override fun onResume() {
        super.onResume()
        applySystemUi()
        webView?.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { getSystemService(PowerManager::class.java).addThermalStatusListener(it) }
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        webView?.onPause()
        super.onPause()
    }

    /** Being stopped while still minimised means the picture in picture window was closed. */
    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) leaveAndFinish()
    }

    private fun applySystemUi() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.navigationBars())
            if (isLandscape()) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun applyChrome() {
        val landscape = isLandscape()
        val minimized = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        binding.activityChromePortrait.visibility = if (!minimized && !landscape) View.VISIBLE else View.GONE
        binding.activityChromeLandscape.visibility = if (!minimized && landscape) View.VISIBLE else View.GONE
        webView?.setLandscape(landscape)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemUi()
        applyChrome()
    }

    private inner class MinimizeDragListener : View.OnTouchListener {
        private var startY = 0f
        private var popped = false
        private var velocity: VelocityTracker? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    popped = false
                    velocity = VelocityTracker.obtain().apply { addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    val distance = (event.rawY - startY).coerceAtLeast(0f)
                    if (distance == 0f) popped = false
                    if (!popped && distance >= dp(PIP_POP_HEIGHT_DP)) {
                        popped = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    binding.root.translationY = if (popped) distance else distance * (1f - POP_RESISTANCE)
                }
                MotionEvent.ACTION_UP -> {
                    velocity?.addMovement(event)
                    val downwards = velocity?.run { computeCurrentVelocity(1000); yVelocity > 0f } ?: false
                    recycleVelocity()
                    if (!popped || !downwards || !enterPip()) springBack()
                }
                MotionEvent.ACTION_CANCEL -> {
                    recycleVelocity()
                    springBack()
                }
            }
            return true
        }

        private fun recycleVelocity() {
            velocity?.recycle()
            velocity = null
        }

        private fun springBack() {
            binding.root.animate()
                .translationY(0f)
                .setDuration(SPRING_BACK_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session?.instance?.compositeInstanceId?.let { outState.putString(STATE_INSTANCE_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.root.translationY = 0f
        applyChrome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val deny = pendingAuthorization
        if (deny != null) settleAuthorization(deny) else super.onBackPressed()
    }

    override fun onDestroy() {
        launchSubscription?.unsubscribe()
        applicationSubscription?.unsubscribe()
        trustedLinkSubscription?.unsubscribe()
        pendingAuthorization?.invoke()
        pendingAuthorization = null
        participantSubscription?.unsubscribe()
        storeSubscription?.unsubscribe()
        imageUploadSubscription?.unsubscribe()
        pendingImageUpload?.invoke(null)
        pendingImageUpload = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        webView?.destroySafely()
        webView = null
        if (isFinishing) leave()
        super.onDestroy()
    }

    private fun startSession(restoredInstanceId: String? = null) {
        launchSubscription?.unsubscribe()
        participantSubscription?.unsubscribe()
        storeSubscription?.unsubscribe()
        webView?.destroySafely()
        webView = null
        showLoading()
        val applicationId = intent.getLongExtra(EXTRA_APPLICATION_ID, 0L)
        if (applicationId <= 0L) {
            showError("Activity is unavailable", retry = false)
            return
        }
        val frameId = UUID.randomUUID().toString()
        val webView = EmbeddedActivityWebView(
            this,
            applicationId,
            frameId,
            EmbeddedActivitySessionManager.rpcConfig,
            onExternalUrl = ::openExternalUrl,
            onPermissionRequest = ::requestWebPermissions,
            onAuthorizeRequest = ::requestOAuthAuthorization,
            onCrash = ::recoverWebView,
            onLoadError = { showError("Unable to load activity", retry = true) },
            onOrientationLockState = { lockState, _, _ -> applyOrientation(lockState) },
            onInviteDialog = ::showInviteSheet,
            onShareLink = ::shareLink,
            onImageUpload = ::pickImageForUpload,
            onNavigateToConnections = { WidgetSettingsUserConnections.launch(this) },
        )
        this.webView = webView
        binding.activityWebContainer.addView(
            webView,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        webView.loadShell()
        applicationSubscription?.unsubscribe()
        applicationSubscription = StoreStream.getApplication()
            .also { it.fetchIfNonexisting(applicationId) }
            .observeApplication(applicationId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { showActivityName(it?.name) }
        val request = EmbeddedActivityLaunchRequest(
            applicationId = applicationId,
            channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L).takeIf { it > 0L },
            instanceId = restoredInstanceId ?: intent.getStringExtra(EXTRA_INSTANCE_ID),
            customId = intent.getStringExtra(EXTRA_CUSTOM_ID),
            referrerId = intent.getStringExtra(EXTRA_REFERRER_ID),
        )
        launchSubscription = EmbeddedActivitySessionManager.prepare(request, frameId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(::showSession) { failure ->
                val message = (failure as? EmbeddedActivityLaunchException)?.message
                    ?: "Unable to start activity"
                showError(message.orEmpty(), retry = true)
            }
    }

    private fun showSession(session: EmbeddedActivitySession) {
        this.session = session
        showActivityName(session.application.name)
        applyOrientation(session.config.defaultOrientationLockState)
        binding.activityStatus.visibility = View.GONE
        val canInvite = session.channel.guildId > 0L
        binding.activityInvite.visibility = if (canInvite) View.VISIBLE else View.GONE
        binding.activityInviteLandscape.visibility = if (canInvite) View.VISIBLE else View.GONE
        val webView = this.webView ?: return
        webView.attachSession(session)
        participantSubscription = StoreStream.getEmbeddedActivities()
            .observeInstancesForChannel(session.channel.id)
            .map { it[session.application.id] }
            .filter { it != null }
            .map { checkNotNull(it) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { instance ->
                this.session = this.session?.copy(instance = instance)
                webView.updateInstance(instance)
            }
        storeSubscription = Observable
            .merge(
                StoreStream.getUsers().observeMe().map { Unit },
                StoreStream.getMediaSettings().getVoiceConfiguration().map { Unit },
                StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId().map { Unit },
            )
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { webView.onStoresChanged() }
    }

    private fun showInviteSheet(channel: Channel) {
        val guildId = channel.guildId.takeIf { it > 0L } ?: return
        WidgetGuildInviteShareSheet.show(supportFragmentManager, channel.id, guildId, "Activity Invite")
    }

    private fun shareLink(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                null,
            ),
        )
    }

    private fun pickImageForUpload(onResult: (String?) -> Unit) {
        if (pendingImageUpload != null) {
            onResult(null)
            return
        }
        pendingImageUpload = onResult
        imagePickerLauncher.launch("image/*")
    }

    private fun uploadPickedImage(uri: Uri?) {
        val onResult = pendingImageUpload ?: return
        pendingImageUpload = null
        val applicationId = session?.application?.id
        val api = RestAPI.embeddedActivitiesApiOrNull
        if (uri == null || applicationId == null || api == null) {
            onResult(null)
            return
        }
        imageUploadSubscription = Observable
            .fromCallable {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read image")
                val type = contentResolver.getType(uri) ?: "image/*"
                MultipartBody.Part.createFormData(
                    "file",
                    "upload",
                    bytes.toRequestBody(type.toMediaTypeOrNull()),
                )
            }
            .flatMap { api.uploadAttachment(applicationId, it) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onResult(it.url) }, { onResult(null) })
    }

    private fun requestOAuthAuthorization(
        request: EmbeddedActivityAuthorizeRequest,
        approve: () -> Unit,
        deny: () -> Unit,
    ) {
        val currentSession = session
        if (currentSession == null) {
            deny()
            return
        }
        bindOAuthDetails(currentSession, request)
        pendingAuthorization = deny
        binding.activityOauth.activityOauthAuthorize.setOnClickListener { settleAuthorization(approve) }
        binding.activityOauth.activityOauthCancel.setOnClickListener { settleAuthorization(deny) }
        binding.activityOauth.root.visibility = View.VISIBLE
    }

    private fun settleAuthorization(outcome: () -> Unit) {
        pendingAuthorization = null
        binding.activityOauth.root.visibility = View.GONE
        outcome()
    }

    private fun bindOAuthDetails(session: EmbeddedActivitySession, request: EmbeddedActivityAuthorizeRequest) {
        val consent = binding.activityOauth
        val me = StoreStream.getUsers().getMe()
        consent.activityOauthTitle.text =
            getString(R.string.embedded_activity_oauth_title, session.application.name)
        consent.activityOauthUserIcon.setImageURI(IconUtils.getForUser(me, size = 128))
        consent.activityOauthUserName.text = me.globalName?.takeIf(String::isNotBlank) ?: me.username
        consent.activityOauthApplicationIcon.setImageURI(
            session.application.icon?.let { IconUtils.getApplicationIcon(session.application.id, it, 128) },
        )
        consent.activityOauthApplicationName.text = session.application.name
        consent.activityOauthScopesLabel
            .setI18nText(R.string.oauth2_scopes_label, "application" to session.application.name)
        consent.activityOauthScopes.apply {
            removeAllViews()
            request.scopes.forEach { scope ->
                val row = layoutInflater.inflate(R.layout.oauth_token_permission_list_item, this, false) as TextView
                runCatching { OAuthPermissionViews.setScopePermissionText(row, scope) }
                    .onFailure { row.text = scope }
                addView(row)
            }
        }
        val securityNotice = if ("messages.read" in request.scopes) {
            R.string.oauth2_can_read_notice
        } else {
            R.string.oauth2_cannot_read_send_notice
        }
        consent.activityOauthSecurityNotice.text = i18nRender(securityNotice)
    }

    private fun showLoading() {
        binding.activityStatus.visibility = View.VISIBLE
        binding.activityLoading.visibility = View.VISIBLE
        binding.activityError.visibility = View.GONE
        binding.activityRetry.visibility = View.GONE
    }

    private fun showError(message: String, retry: Boolean) {
        binding.activityStatus.visibility = View.VISIBLE
        binding.activityLoading.visibility = View.GONE
        binding.activityError.visibility = View.VISIBLE
        binding.activityError.text = message
        binding.activityRetry.visibility = if (retry) View.VISIBLE else View.GONE
        binding.activityRetry.setIsLoading(false)
    }

    private fun recoverWebView() {
        webView?.destroySafely()
        webView = null
        if (crashRecoveryCount++ == 0) startSession(session?.instance?.compositeInstanceId)
        else showError(getString(R.string.embedded_activity_crashed), retry = true)
    }

    private fun requestWebPermissions(request: PermissionRequest) {
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        permissionLauncher.launch(
            request.resources.mapNotNull { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    else -> null
                }
            }.distinct().toTypedArray(),
        )
    }

    /** A failed or slow trust check is not an error; it just means ask. */
    private fun openExternalUrl(url: String, result: (Boolean) -> Unit) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            result(false)
            return
        }
        val api = RestAPI.embeddedActivitiesApiOrNull
        val applicationId = session?.application?.id
        trustedLinkSubscription?.unsubscribe()
        trustedLinkSubscription = if (api == null || applicationId == null) {
            Observable.just(false)
        } else {
            api.trustedLink(applicationId, url)
                .map { it.trusted }
                .timeout(TRUSTED_LINK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .onErrorReturn { false }
                .subscribeOn(Schedulers.io())
        }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { trusted ->
                if (trusted || UriHandler.isTrustedDomain(url)) {
                    UriHandler.handle(this, url)
                    result(true)
                } else {
                    WidgetMaskedLinksDialog.show(supportFragmentManager, url, onResult = result)
                }
            }
    }

    private fun showActivityName(name: String?) {
        binding.activityTitle.text = name.orEmpty()
        binding.activityTitle.visibility = if (name.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** A translucent window cannot request an orientation before API 27. */
    private fun applyOrientation(lockState: Int) {
        val orientation = when (lockState) {
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        runCatching { requestedOrientation = orientation }
    }

    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView == null) return false
        return enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
        )
    }

    private fun leaveAndFinish() {
        leave()
        finish()
    }

    private fun leave() {
        if (leaveSent) return
        leaveSent = true
        session?.let { EmbeddedActivitySessionManager.leave(it).subscribeOn(Schedulers.io()).subscribe({}, {}) }
    }

    companion object {
        const val EXTRA_APPLICATION_ID = "embedded_activity_application_id"
        const val EXTRA_CHANNEL_ID = "embedded_activity_channel_id"
        const val EXTRA_INSTANCE_ID = "embedded_activity_instance_id"
        const val EXTRA_CUSTOM_ID = "embedded_activity_custom_id"
        const val EXTRA_REFERRER_ID = "embedded_activity_referrer_id"
        private const val STATE_INSTANCE_ID = "embedded_activity_restored_instance_id"

        private const val TRUSTED_LINK_TIMEOUT_MS = 500L
        private const val PIP_POP_HEIGHT_DP = 140
        private const val POP_RESISTANCE = 0.55f
        private const val SPRING_BACK_MS = 200L
        private const val CHROME_PADDING_MD_DP = 16
        private const val CHROME_PADDING_LG_DP = 24
    }
}
