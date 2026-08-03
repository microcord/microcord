package com.discord.activities

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.discord.R
import com.discord.databinding.ActivityEmbeddedActivityBinding
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.uri.UriHandler
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

class EmbeddedActivityActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmbeddedActivityBinding
    private var session: EmbeddedActivitySession? = null
    private var webView: EmbeddedActivityWebView? = null
    private var launchSubscription: Subscription? = null
    private var participantSubscription: Subscription? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var crashRecoveryCount = 0
    private var leaveSent = false

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
        binding.activityClose.setOnClickListener { leaveAndFinish() }
        binding.activityPip.setOnClickListener { enterPip() }
        binding.activityRetry.setOnClickListener { startSession() }
        startSession(savedInstanceState?.getString(STATE_INSTANCE_ID))
    }

    override fun onResume() {
        super.onResume()
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

    override fun onSaveInstanceState(outState: Bundle) {
        session?.instance?.compositeInstanceId?.let { outState.putString(STATE_INSTANCE_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.activityChrome.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        launchSubscription?.unsubscribe()
        participantSubscription?.unsubscribe()
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
        webView?.destroySafely()
        webView = null
        showLoading()
        val applicationId = intent.getLongExtra(EXTRA_APPLICATION_ID, 0L)
        if (applicationId <= 0L) {
            showError("Activity is unavailable", retry = false)
            return
        }
        val request = EmbeddedActivityLaunchRequest(
            applicationId = applicationId,
            channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L).takeIf { it > 0L },
            instanceId = restoredInstanceId ?: intent.getStringExtra(EXTRA_INSTANCE_ID),
            customId = intent.getStringExtra(EXTRA_CUSTOM_ID),
            referrerId = intent.getStringExtra(EXTRA_REFERRER_ID),
        )
        launchSubscription = EmbeddedActivitySessionManager.prepare(request)
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
        binding.activityTitle.text = session.application.name
        applyOrientation(session.config.defaultOrientationLockState)
        binding.activityStatus.visibility = View.GONE
        binding.activityPip.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
        val webView = EmbeddedActivityWebView(
            this,
            session,
            onExternalUrl = ::openExternalUrl,
            onPermissionRequest = ::requestWebPermissions,
            onCrash = ::recoverWebView,
            onLoadError = { showError("Unable to load activity", retry = true) },
        )
        this.webView = webView
        binding.activityWebContainer.addView(
            webView,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        webView.loadActivity()
        participantSubscription = StoreStream.getEmbeddedActivities()
            .observeInstancesForChannel(session.channel.id)
            .map { it[session.application.id] }
            .filter { it != null }
            .map { checkNotNull(it) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { instance ->
                this.session = this.session?.copy(instance = instance)
                binding.activityParticipants.text = instance.participants.size.toString()
                webView.updateInstance(instance)
            }
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

    private fun openExternalUrl(url: String, result: (Boolean) -> Unit) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            result(false)
            return
        }
        val api = RestAPI.embeddedActivitiesApiOrNull
        val applicationId = session?.application?.id
        if (api == null || applicationId == null) {
            result(false)
            return
        }
        api.trustedLink(applicationId, url)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ trusted ->
                if (trusted.trusted) UriHandler.handle(this, url)
                else showError(getString(R.string.embedded_activity_external_blocked), retry = false)
                result(trusted.trusted)
            }, { result(false) })
    }

    private fun applyOrientation(lockState: Int) {
        requestedOrientation = when (lockState) {
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || webView == null) return
        enterPictureInPictureMode(
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
    }
}
