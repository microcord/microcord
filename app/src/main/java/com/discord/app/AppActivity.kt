package com.discord.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.databinding.ActivityAppBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.MainRoute
import com.discord.stores.RequiredAction
import com.discord.stores.StoreStream
import com.discord.stores.IntentRouteResult
import com.discord.utilities.intent.AppIntentParser
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.auth.AuthAction
import com.discord.widgets.auth.AuthFragment
import com.discord.widgets.auth.AuthResult
import com.discord.widgets.auth.AuthRoute
import com.discord.widgets.auth.AuthUiActions
import com.discord.widgets.auth.AuthUiState
import com.discord.widgets.auth.AuthUiRules
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
import java.util.Locale
import rx.Subscription

open class AppActivity : AppCompatActivity(), AuthUiActions {
    private lateinit var binding: ActivityAppBinding
    private var routeSubscription: Subscription? = null
    private var stopBootLog: (() -> Unit)? = null
    private var pendingLaunchIntent: Intent? = null

    override fun attachBaseContext(newBase: Context) {
        val locale = ModelUserSettings.getLocaleObject(StoreStream.getUserSettingsSystem().getLocale())
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            when (StoreStream.getUserSettingsSystem().getTheme()) {
                ModelUserSettings.THEME_LIGHT -> R.style.AppTheme_Light
                ModelUserSettings.THEME_PURE_EVIL -> R.style.AppTheme_Dark_Evil
                else -> R.style.AppTheme_Dark
            }
        )
        super.onCreate(savedInstanceState)
        binding = ActivityAppBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
                else moveTaskToBack(true)
            }
        })
        pendingLaunchIntent = savedInstanceState?.pendingIntent() ?: intent
        consumeIntent(requireNotNull(pendingLaunchIntent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (StoreStream.getNavigation().getPendingIntent() != null) {
            outState.putParcelable(STATE_PENDING_INTENT, pendingLaunchIntent)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        stopBootLog = BootLog.observe { lines ->
            runOnUiThread {
                binding.appBootLog.text = lines.joinToString("\n")
                binding.appLoading.post { binding.appLoading.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        routeSubscription = StoreStream.getNavigation().observeMainRoute()
            .subscribe { route -> runOnUiThread { showRoute(route) } }
    }

    override fun onStop() {
        stopBootLog?.invoke()
        stopBootLog = null
        routeSubscription?.unsubscribe()
        routeSubscription = null
        super.onStop()
    }

    private fun showRoute(route: MainRoute) {
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, createRouteFragment(route), tag)
            .commitNow()
    }

    private fun consumeIntent(intent: Intent) {
        when (val result = StoreStream.getNavigation().routeIntent(AppIntentParser.parse(intent))) {
            is IntentRouteResult.External -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.uri)))
            }
            IntentRouteResult.Queued -> pendingLaunchIntent = Intent(intent)
            IntentRouteResult.Rejected -> Unit
        }
        if (intent.action != Intent.ACTION_MAIN) {
            setIntent(Intent(this, Main::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    private fun createRouteFragment(route: MainRoute): Fragment = routeFragmentFactory?.invoke(route) ?: when (route) {
        MainRoute.Loading -> error("Loading has no fragment")
        MainRoute.LoggedIn -> WidgetTabsHost()
        MainRoute.LoggedOut -> WidgetAuthLanding()
        is MainRoute.AgeGated -> WidgetAgeVerify()
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, fragment, "auth:$route")
            .addToBackStack(route.name)
            .commit()
    }

    override fun submit(action: AuthAction, callback: (AuthResult) -> Unit) {
        authSubmitter?.let {
            it(action, callback)
            return
        }
        if (action is AuthAction.TokenLogin) {
            val errors = AuthUiRules.token(action.token)
            if (errors.isEmpty()) {
                StoreStream.getAuthentication().loginWithToken(action.token)
                callback(AuthResult.Success())
            } else {
                callback(AuthResult.Failure(fieldErrors = errors))
            }
        } else {
            callback(AuthResult.Failure(message = "Authentication is not initialized."))
        }
    }

    override fun close() {
        if (!supportFragmentManager.popBackStackImmediate()) moveTaskToBack(true)
    }

    class Main : AppActivity()

    companion object {
        private const val STATE_PENDING_INTENT = "pending_launch_intent"
        internal var routeFragmentFactory: ((MainRoute) -> Fragment)? = null
        internal var authSubmitter: ((AuthAction, (AuthResult) -> Unit) -> Unit)? = null
    }

    @Suppress("DEPRECATION")
    private fun Bundle.pendingIntent(): Intent? = getParcelable(STATE_PENDING_INTENT)
}
