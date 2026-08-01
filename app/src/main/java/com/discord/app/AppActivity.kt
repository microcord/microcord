package com.discord.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.databinding.ActivityAppBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.screenshot_detection.ScreenshotDetector
import com.discord.stores.IntentRouteResult
import com.discord.stores.MainRoute
import com.discord.stores.RequiredAction
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.intent.AppIntentParser
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.views.ThemedColor
import com.discord.views.ToolbarTitleLayout
import com.discord.widgets.auth.AuthAction
import com.discord.widgets.auth.AuthFragment
import com.discord.widgets.auth.AuthRequests
import com.discord.widgets.auth.AuthResult
import com.discord.widgets.auth.AuthRoute
import com.discord.widgets.auth.AuthUiActions
import com.discord.widgets.auth.AuthUiState
import com.discord.widgets.auth.WidgetAgeVerify
import com.discord.widgets.auth.WidgetAuthAgeGated
import com.discord.widgets.auth.WidgetAuthBirthday
import com.discord.widgets.auth.WidgetAuthEmailVerify
import com.discord.widgets.auth.WidgetAuthForceChangePassword
import com.discord.widgets.auth.WidgetAuthLanding
import com.discord.widgets.auth.WidgetAuthLogin
import com.discord.widgets.auth.WidgetAuthMfa
import com.discord.widgets.auth.WidgetAuthPhoneVerify
import com.discord.widgets.auth.WidgetAuthRegisterAccountInformation
import com.discord.widgets.auth.WidgetAuthRegisterIdentity
import com.discord.widgets.auth.WidgetAuthRequiredAction
import com.discord.widgets.auth.WidgetAuthResetPassword
import com.discord.widgets.auth.WidgetAuthUndeleteAccount
import com.discord.widgets.bugreports.WidgetBugReport
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import com.discord.widgets.voice.call.WidgetVoiceCallIncoming
import java.util.Locale
import kotlin.math.roundToInt
import rx.Subscription
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * The single activity that hosts every screen.
 *
 * Beyond routing it owns the process-visible UI services the screens delegate to: the action bar
 * toolbar and its [ToolbarTitleLayout], the soft keyboard, runtime permissions, priority ordered
 * back-press dispatch, new-intent fan-out, and restarting itself when a locale/theme/font-scale
 * change makes the current themed context stale.
 */
open class AppActivity : AppTransitionActivity(), AppComponent, AuthUiActions {
    private lateinit var binding: ActivityAppBinding
    private var routeSubscription: Subscription? = null
    private var settingsSubscription: Subscription? = null
    private var screenshotSubscription: Subscription? = null
    private var screenshotUserSubscription: Subscription? = null
    private var noticeSubscription: Subscription? = null
    private var pendingNotice: StoreNotices.Notice? = null
    private var stopBootLog: (() -> Unit)? = null
    private var pendingLaunchIntent: Intent? = null
    private var fatalCrashShown: Boolean = false
    private var directScreen: Boolean = false

    private val noticeFragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fragmentManager: FragmentManager, fragment: Fragment) {
            tryShowPendingNotice()
        }
    }

    final override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    /** Registered during construction: the Activity Result API forbids later registration. */
    val appPermissions: AppPermissionsRequests = AppPermissions(this)

    /** Priority ordered back-press handlers contributed by the currently bound screens. */
    val backPressHandlers = BackPressHandlerRegistry()

    /**
     * The activity's own back handling, registered *without* a [androidx.lifecycle.LifecycleOwner].
     *
     * That is deliberate. A lifecycle-bound callback only enters the dispatcher's stack when its
     * owner starts, and from API 29 the activity's own `ON_START` is delivered *after* its
     * fragments', which puts the activity on top of the stack and lets it swallow every back press
     * before any fragment sees it. Registering eagerly in [onCreate] pins this callback to the
     * bottom of the stack instead, so screen-owned callbacks and the [backPressHandlers] registry
     * always get the first look and this is the true fallback.
     */
    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = dispatchBackPressed()
    }

    private val newIntentListeners = LinkedHashMap<Int, (Intent) -> Unit>()

    /** Font scale, in percent, that the current themed context was built with. */
    private var appliedFontScale: Int = USE_SYSTEM_FONT_SCALE

    private var appliedLocale: String = ""
    private var appliedTheme: String = ""

    /** Action bar toolbar of the currently bound screen. */
    var toolbar: Toolbar? = null
        private set

    val toolbarTitleLayout: ToolbarTitleLayout?
        get() = toolbar?.getChildAt(0) as? ToolbarTitleLayout

    /** The most recent intent delivered to this activity, never `null`. */
    var mostRecentIntent: Intent = Intent()
        private set

    // region lifecycle

    override fun attachBaseContext(newBase: Context) {
        val configuredLocale = runCatching { StoreStream.getUserSettingsSystem().getLocale() }
            .getOrElse { ModelUserSettings.getDefaultLocale() }
        val locale = ModelUserSettings.getLocaleObject(configuredLocale)
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            val targetFontScale = targetFontScale(newBase)
            fontScale = targetFontScale
            appliedFontScale = (targetFontScale * 100f).roundToInt()
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        // AppCompat overrides the whole configuration to carry the night mode; keep everything the
        // base context computed (locale and font scale) and let only the ui mode through.
        if (overrideConfiguration != null) {
            val uiMode = overrideConfiguration.uiMode
            overrideConfiguration.setTo(baseContext.resources.configuration)
            overrideConfiguration.uiMode = uiMode
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = runCatching { StoreStream.getUserSettingsSystem().getTheme() }.getOrDefault(ModelUserSettings.THEME_DARK)
        appliedTheme = theme
        setTheme(
            when (theme) {
                ModelUserSettings.THEME_LIGHT -> R.style.AppTheme_Light
                ModelUserSettings.THEME_PURE_EVIL -> R.style.AppTheme_Dark_Evil
                else -> R.style.AppTheme_Dark
            }
        )
        super.onCreate(savedInstanceState)
        supportFragmentManager.registerFragmentLifecycleCallbacks(noticeFragmentCallbacks, true)
        try {
            binding = ActivityAppBinding.inflate(layoutInflater)
            setContentView(binding.root)
            onBackPressedDispatcher.addCallback(backPressedCallback)
            pendingLaunchIntent = savedInstanceState?.pendingIntent() ?: intent
            mostRecentIntent = intent ?: Intent()
            consumeIntent(requireNotNull(pendingLaunchIntent))
        } catch (t: Throwable) {
            showFatalCrash(t)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mostRecentIntent = intent
        consumeIntent(intent)
        newIntentListeners.values.toList().forEach { listener -> listener(intent) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (StoreStream.getNavigation().getPendingIntent() != null) {
            outState.putParcelable(STATE_PENDING_INTENT, pendingLaunchIntent)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (fatalCrashShown) return
        stopBootLog = BootLog.observe { lines ->
            runOnUiThread {
                binding.appBootLog.text = lines.joinToString("\n")
                binding.appLoading.post { binding.appLoading.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        if (!directScreen) {
            routeSubscription = StoreStream.getNavigation().observeMainRoute()
                .subscribe { route -> runOnUiThread { showRoute(route) } }
        }
        noticeSubscription = StoreStream.getNotices().getNotices().subscribe { notice ->
            runOnUiThread {
                pendingNotice = notice
                tryShowPendingNotice()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (fatalCrashShown) return
        // An intent tagged as a settings restart must not immediately restart again.
        if (mostRecentIntent.getBooleanExtra(AppScreenRouter.EXTRA_RECREATE, false)) {
            mostRecentIntent.removeExtra(AppScreenRouter.EXTRA_RECREATE)
            return
        }
        val userSettings = StoreStream.getUserSettingsSystem()
        if (appliedLocale.isNotEmpty() && appliedLocale != userSettings.getLocale()) {
            AppScreenRouter.recreate(this)
            return
        }
        appliedLocale = userSettings.getLocale()
        settingsSubscription = userSettings.observeSettings(true)
            .bindToComponentLifecycle(this)
            .subscribe { settings -> runOnUiThread { onUserSettingsChanged(settings.locale, settings.theme, settings.fontScale) } }
        ScreenshotDetector.instance?.let { detector ->
            screenshotSubscription = detector.screenshots
                .bindToComponentLifecycle(this)
                .subscribe { screenshot ->
                    runOnUiThread { WidgetBugReport.launch(this, screenshot.uri, screenshot.filename) }
                }
            screenshotUserSubscription = StoreStream.getUsers().observeMe(true)
                .map { it.isStaff }
                .distinctUntilChanged()
                .bindToComponentLifecycle(this)
                .subscribe { isStaff ->
                    runOnUiThread {
                        val settingEnabled = SharedPreferencesProvider.get()
                            .getBoolean(PREF_BUG_REPORTING_ENABLED, true)
                        detector.setEnabled(isStaff && settingEnabled)
                    }
                }
        }
        tryShowPendingNotice()
    }

    override fun onPause() {
        super.onPause()
        unsubscribeSignal.onNext(null)
        settingsSubscription?.unsubscribe()
        settingsSubscription = null
        screenshotSubscription?.unsubscribe()
        screenshotSubscription = null
        screenshotUserSubscription?.unsubscribe()
        screenshotUserSubscription = null
    }

    override fun onStop() {
        stopBootLog?.invoke()
        stopBootLog = null
        routeSubscription?.unsubscribe()
        routeSubscription = null
        noticeSubscription?.unsubscribe()
        noticeSubscription = null
        pendingNotice = null
        super.onStop()
    }

    override fun onDestroy() {
        newIntentListeners.clear()
        backPressHandlers.clear()
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(noticeFragmentCallbacks)
        super.onDestroy()
    }

    // endregion

    private fun tryShowPendingNotice() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val notice = pendingNotice ?: return
        val validScreens = notice.validScreens
        if (validScreens != null && validScreens.none { screen -> isScreenVisible(screen.java) }) return

        AppLog.recordBreadcrumb("Notice [${notice.name}]", "navigation")
        if (notice.autoMarkSeen) StoreStream.getNotices().markSeen(notice.name)
        notice.show(this)
    }

    private fun isScreenVisible(screen: Class<*>): Boolean =
        screen.isInstance(this) || hasVisibleScreen(supportFragmentManager, screen)

    private fun hasVisibleScreen(fragmentManager: FragmentManager, screen: Class<*>): Boolean =
        fragmentManager.fragments.any { fragment ->
            fragment.isVisible && (
                screen.isInstance(fragment) || hasVisibleScreen(fragment.childFragmentManager, screen)
            )
        }

    // region back press and new intents

    /**
     * Offers the press to every registered screen handler, highest precedence first, and falls back
     * to the platform behaviour when none of them consumed it.
     *
     * "Leave the app" is *not* part of this fallback: the reference lets the screen decide, and
     * `WidgetHome` is the screen that calls `moveTaskToBack` when the left panel is already open.
     */
    private fun dispatchBackPressed() {
        if (backPressHandlers.dispatchBackPressed() || isFinishing) return
        // The reference swallows exactly these two around its `super.onBackPressed()`: a fragment
        // manager whose state was already saved throws IllegalStateException, and a transaction
        // against a torn-down host throws IllegalArgumentException. Neither should crash the app.
        try {
            if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
            else {
                markBackNavigation()
                finish()
            }
        } catch (e: IllegalArgumentException) {
            AppLog.d("Back press could not be completed", e)
        } catch (e: IllegalStateException) {
            AppLog.d("Back press could not be completed", e)
        }
    }

    /** Registers a listener, keyed by the owning fragment id, for intents delivered while resumed. */
    fun setOnNewIntentListener(ownerId: Int, action: (Intent) -> Unit) {
        newIntentListeners[ownerId] = action
    }

    fun removeOnNewIntentListener(ownerId: Int) {
        newIntentListeners.remove(ownerId)
    }

    // endregion

    // region toolbar

    /**
     * Adopts [toolbar] as the action bar and installs the shared [ToolbarTitleLayout] into it.
     *
     * Screens own their toolbar, so this runs again whenever a screen with its own
     * `action_bar_toolbar` binds its view.
     */
    fun setToolbar(toolbar: Toolbar?) {
        this.toolbar = toolbar
        if (toolbarTitleLayout != null || toolbar == null) return
        toolbar.addView(ToolbarTitleLayout(this), 0)
        toolbarTitleLayout?.background = ContextCompat.getDrawable(this, themedResourceId(R.attr.selectableItemBackground))
        toolbar.setNavigationOnClickListener {
            hideKeyboard(null)
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Shows or hides the toolbar up affordance, optionally with a custom icon and label. */
    fun setActionBarDisplayHomeAsUpEnabled(
        showHomeAsUp: Boolean = true,
        @DrawableRes iconRes: Int? = null,
        @StringRes iconAccessibilityLabel: Int? = null,
    ): Toolbar? {
        val toolbar = toolbar ?: return null
        var icon: Drawable? = null
        if (showHomeAsUp) {
            val drawableRes = iconRes ?: themedResourceId(R.attr.ic_action_bar_back)
            icon = ContextCompat.getDrawable(toolbar.context, drawableRes)?.also { drawable ->
                DrawableCompat.setTint(drawable, ThemedColor.resolve(this, R.attr.colorInteractiveActive))
            }
            toolbar.navigationContentDescription = getString(R.string.back)
        }
        toolbar.navigationIcon = icon
        iconAccessibilityLabel?.let { toolbar.navigationContentDescription = getString(it) }
        return toolbar
    }

    // endregion

    // region keyboard

    fun showKeyboard(view: View) = setKeyboardOpen(true, view)

    fun hideKeyboard(view: View? = null) = setKeyboardOpen(false, view)

    private fun setKeyboardOpen(open: Boolean, view: View?) {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (open) {
            // The window needs a moment to settle after a transition before it accepts focus.
            view?.postDelayed({
                try {
                    val target = (view as? com.google.android.material.textfield.TextInputLayout)?.editText ?: view
                    target.requestFocus()
                    inputMethodManager.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                    AppLog.d("Error opening the keyboard", e)
                }
            }, SHOW_KEYBOARD_DELAY_MILLIS)
            return
        }
        val windowToken = view?.windowToken
            ?: findViewById<View>(android.R.id.content)?.let { (it as? android.view.ViewGroup)?.getChildAt(0) }?.applicationWindowToken
        try {
            inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Exception) {
            AppLog.d("Error closing the keyboard", e)
        }
    }

    // endregion

    // region settings driven restarts

    private fun onUserSettingsChanged(locale: String, theme: String, fontScale: Int) {
        val localeChanged = appliedLocale.isNotEmpty() && appliedLocale != locale
        val themeChanged = appliedTheme.isNotEmpty() && appliedTheme != theme
        val fontScaleChanged = if (fontScale == USE_SYSTEM_FONT_SCALE) {
            appliedFontScale != systemFontScalePercent()
        } else {
            appliedFontScale != fontScale
        }
        if (localeChanged || themeChanged || fontScaleChanged) AppScreenRouter.recreate(this)
    }

    private fun targetFontScale(context: Context): Float {
        val configuredScale = runCatching { StoreStream.getUserSettingsSystem().getFontScale() }
            .getOrDefault(USE_SYSTEM_FONT_SCALE)
        if (configuredScale != USE_SYSTEM_FONT_SCALE) return configuredScale / 100f
        return Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
    }

    private fun systemFontScalePercent(): Int =
        (Settings.System.getFloat(contentResolver, Settings.System.FONT_SCALE, 1f) * 100f).roundToInt()

    // endregion

    // region routing

    private fun showFatalCrash(throwable: Throwable) {
        if (fatalCrashShown) {
            finish()
            return
        }
        fatalCrashShown = true
        AppFatalCrash.render(this, throwable, javaClass.name)
    }

    private fun showRoute(route: MainRoute) {
        if (fatalCrashShown) return
        if (route == MainRoute.Loading) {
            binding.appLoading.visibility = View.VISIBLE
            supportFragmentManager.findFragmentById(R.id.app_root)?.view?.visibility = View.GONE
            return
        }
        BootLog.record(BootLogEvent.OPENING_INTERFACE)
        val tag = routeTag(route)
        binding.appLoading.visibility = View.GONE
        supportFragmentManager.findFragmentById(R.id.app_root)?.view?.visibility = View.VISIBLE
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val fragment = createRouteFragment(route)
        AppLog.recordNavigation(javaClass.simpleName, fragment.javaClass.simpleName)
        supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, fragment, tag)
            .commitNow()
    }

    private fun consumeIntent(intent: Intent) {
        directScreen = false
        // A screen intent addresses one fragment directly and bypasses MainRoute entirely. Without
        // this branch `AppScreenRouter.launch` silently re-entered the tab host, which left every
        // router-launched screen (media viewer, QR scanner, TOS report) unreachable.
        //
        // Never on `Main`. `Main` is the exported launcher/deep-link component, and EXTRA_SCREEN
        // names a fragment that `showScreen` instantiates with this intent's extras as arguments.
        // Reading it here would let any installed app display any fragment with arguments it chose
        // — including `WidgetIncomingShare`, whose unvalidated `uris` argument is opened with our
        // own contentResolver and would exfiltrate the token from shared_prefs. Router launches go
        // to the non-exported base activity instead; see `AppScreenRouter.createScreenIntent`.
        if (this !is Main) {
            AppScreenRouter.screenOf(intent)?.let { screen ->
                directScreen = true
                @Suppress("DEPRECATION")
                (intent.getSerializableExtra(AppScreenRouter.EXTRA_TRANSITION) as? AppTransitionActivity.Transition)
                    ?.let { transitionAnimations = it.animations }
                showScreen(screen, intent)
                return
            }
        }
        when (val result = StoreStream.getNavigation().routeIntent(AppIntentParser.parse(intent))) {
            is IntentRouteResult.External -> UriHandler.handle(this, result.uri)
            IntentRouteResult.Queued -> pendingLaunchIntent = Intent(intent)
            IntentRouteResult.Rejected -> Unit
        }
        if (intent.action != Intent.ACTION_MAIN) {
            setIntent(Intent(this, Main::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    /**
     * Shows a fragment addressed directly by [AppScreenRouter.EXTRA_SCREEN].
     *
     * The launching intent's extras are carried through as fragment arguments so a screen can read
     * its arguments either way. The fragment is the root of this activity; Back finishes the
     * activity and reveals the previous screen activity exactly as the reference does.
     */
    private fun showScreen(screen: Class<out Fragment>, intent: Intent) {
        val tag = "screen:${screen.name}"
        binding.appLoading.visibility = View.GONE
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, screen.name)
            .apply { arguments = intent.extras }
        AppLog.recordNavigation(javaClass.simpleName, screen.simpleName)
        supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, fragment, tag)
            .commit()
    }

    private fun createRouteFragment(route: MainRoute): Fragment = routeFragmentFactory?.invoke(route) ?: when (route) {
        MainRoute.Loading -> error("Loading has no fragment")
        MainRoute.LoggedIn -> WidgetTabsHost()
        MainRoute.LoggedOut -> WidgetAuthLanding()
        is MainRoute.AgeGated -> WidgetAgeVerify().apply {
            arguments = WidgetAgeVerify.arguments(
                route.ageGate == StoreNavigation.AgeGate.NSFW_CHANNEL_AGE_GATE,
            )
        }
        is MainRoute.Required -> instantiate(
            when (route.action) {
                RequiredAction.UPDATE_PASSWORD -> WidgetAuthForceChangePassword::class.java.name
                RequiredAction.AGREEMENTS, RequiredAction.TOS_UPDATE_ACKNOWLEDGMENT -> "com.discord.widgets.tos.WidgetTos"
                RequiredAction.REQUIRE_VERIFIED_EMAIL, RequiredAction.REQUIRE_VERIFIED_PHONE ->
                    WidgetAuthRequiredAction::class.java.name
                RequiredAction.REQUIRE_CAPTCHA -> WidgetAuthRequiredAction::class.java.name
            },
        )
    }

    private fun instantiate(className: String): Fragment = supportFragmentManager.fragmentFactory
        .instantiate(classLoader, className)

    private fun routeTag(route: MainRoute) = "main-route:$route"

    override fun navigate(route: AuthRoute, state: AuthUiState) {
        val fragment = when (route) {
            AuthRoute.LANDING -> WidgetAuthLanding()
            AuthRoute.LOGIN -> WidgetAuthLogin()
            AuthRoute.REGISTER_IDENTITY -> WidgetAuthRegisterIdentity()
            AuthRoute.REGISTER_ACCOUNT -> WidgetAuthRegisterAccountInformation()
            AuthRoute.BIRTHDAY -> WidgetAuthBirthday()
            AuthRoute.MFA -> WidgetAuthMfa()
            AuthRoute.PHONE_VERIFICATION -> WidgetAuthPhoneVerify()
            AuthRoute.EMAIL_VERIFICATION -> WidgetAuthEmailVerify()
            AuthRoute.REQUIRED_ACTION -> WidgetAuthRequiredAction()
            AuthRoute.AGE_VERIFY -> WidgetAgeVerify()
            AuthRoute.AGE_GATED -> WidgetAuthAgeGated()
            AuthRoute.FORCE_PASSWORD -> WidgetAuthForceChangePassword()
            AuthRoute.RESET_PASSWORD -> WidgetAuthResetPassword()
            AuthRoute.UNDELETE_ACCOUNT -> WidgetAuthUndeleteAccount()
        }.apply { arguments = AuthFragment.arguments(state) }
        AppLog.recordNavigation(javaClass.simpleName, fragment.javaClass.simpleName)
        supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, fragment, "auth:$route")
            .addToBackStack(route.name)
            .commit()
    }

    override fun submit(
        action: AuthAction,
        captcha: com.discord.utilities.captcha.CaptchaHelper.CaptchaPayload?,
        callback: (AuthResult) -> Unit,
    ) {
        authSubmitter?.let {
            it(action, callback)
            return
        }
        AuthRequests.submit(action, captcha, callback)
    }

    override fun close() {
        if (!supportFragmentManager.popBackStackImmediate()) moveTaskToBack(true)
    }

    /**
     * Opens the system credential manager so the user can look up the password they are being
     * asked for.
     *
     * The reference uses Google Smart Lock, which is a Play Services dependency this project does
     * not carry; the platform's own autofill/password settings screen is the closest equivalent
     * that exists on every device. Falling back to Discord's account-recovery page keeps the link
     * from being a dead end on a device without that settings activity.
     */
    override fun openPasswordManager() {
        val intents = buildList {
            // The autofill settings screen only exists from API 26; below that the account-sync
            // screen is the closest a device offers.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                add(Intent(ACTION_REQUEST_SET_AUTOFILL_SERVICE))
            }
            add(Intent(Settings.ACTION_SYNC_SETTINGS))
        }
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(intent) }.onSuccess { return }
            }
        }
        UriHandler.handle(this, PASSWORD_HELP_URL)
    }

    /** Opens Discord support for an account the user cannot recover from inside the app. */
    override fun openSupport() = UriHandler.handle(this, SUPPORT_URL)

    // endregion

    private fun themedResourceId(attributeResId: Int): Int = TypedValue()
        .also { theme.resolveAttribute(attributeResId, it, true) }
        .resourceId

    class Main : AppActivity()

    /** Dedicated single-task host for the active fullscreen call surface. */
    class Call : AppActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            intent.putExtra(AppScreenRouter.EXTRA_SCREEN, WidgetCallFullscreen::class.java)
            super.onCreate(savedInstanceState)
        }

        override fun onNewIntent(intent: Intent) {
            intent.putExtra(AppScreenRouter.EXTRA_SCREEN, WidgetCallFullscreen::class.java)
            super.onNewIntent(intent)
        }
    }

    /** Lock-screen-capable host for an incoming private call. */
    class IncomingCall : AppActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            intent.putExtra(AppScreenRouter.EXTRA_SCREEN, WidgetVoiceCallIncoming::class.java)
            super.onCreate(savedInstanceState)
        }

        override fun onNewIntent(intent: Intent) {
            intent.putExtra(AppScreenRouter.EXTRA_SCREEN, WidgetVoiceCallIncoming::class.java)
            super.onNewIntent(intent)
        }
    }

    companion object {
        private const val STATE_PENDING_INTENT = "pending_launch_intent"
        private const val PREF_BUG_REPORTING_ENABLED = "prefs_ss_bug_reporting_enabled"

        /** Discord's own "I forgot my password" help article. */
        private const val PASSWORD_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/218498068"

        private const val SUPPORT_URL = "https://support.discord.com/hc/en-us/requests/new"

        /** `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`, spelled out because minSdk is 21. */
        private const val ACTION_REQUEST_SET_AUTOFILL_SERVICE =
            "android.settings.REQUEST_SET_AUTOFILL_SERVICE"

        /** Font scale sentinel meaning "follow the system setting". */
        const val USE_SYSTEM_FONT_SCALE = -1

        private const val SHOW_KEYBOARD_DELAY_MILLIS = 250L

        internal var routeFragmentFactory: ((MainRoute) -> Fragment)? = null
        internal var authSubmitter: ((AuthAction, (AuthResult) -> Unit) -> Unit)? = null
    }

    @Suppress("DEPRECATION")
    private fun Bundle.pendingIntent(): Intent? = getParcelable(STATE_PENDING_INTENT)
}
