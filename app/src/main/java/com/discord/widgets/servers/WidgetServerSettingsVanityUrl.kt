package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import com.discord.R
import com.discord.api.guild.VanityUrlResponse
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnEditTextFocusChangeListener
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * The guild's `discord.gg/<code>` vanity invite.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsVanityUrl`. The current code and its
 * use count come from `GET /guilds/{id}/vanity-url`, not from the guild object, so the screen owns a
 * REST read as well as a write.
 *
 * Two behaviours are easy to lose and are reproduced deliberately:
 * - **Taking a code that is already in use** comes back as API error `50020`, and the reference
 *   replaces the usual error toast with the inline red `change_vanity_url_error` line. This project's
 *   [com.discord.utilities.error.RestApiError] has no per-error toast suppression, so the toast is
 *   turned off for this call (`context = null`) and the error handler renders every failure itself.
 * - **The `discord.gg/` prefix slides out of the way** while the field has focus, so a long code is
 *   not squeezed into the remaining width.
 */
class WidgetServerSettingsVanityUrl : AppFragment(R.layout.widget_server_settings_vanity_url) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsVanityUrlViewModel by appViewModels {
        ServerSettingsVanityUrlViewModel(guildId)
    }

    private val state = StatefulViews(R.id.server_settings_vanity_input)

    private lateinit var input: TextInputLayout
    private lateinit var prefix: TextView
    private lateinit var inputContainer: View
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var currentUrl: TextView
    private lateinit var header: TextView
    private lateinit var remove: TextView
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        input = view.findViewById(R.id.server_settings_vanity_input)
        prefix = view.findViewById(R.id.server_settings_vanity_url_prefix)
        inputContainer = view.findViewById(R.id.server_settings_vanity_url_input_container)
        loadingIndicator = view.findViewById(R.id.server_settings_vanity_url_loading_indicator)
        errorText = view.findViewById(R.id.server_settings_vanity_url_error_text)
        currentUrl = view.findViewById(R.id.server_settings_vanity_url_current_url)
        header = view.findViewById(R.id.server_settings_vanity_url_header)
        remove = view.findViewById(R.id.server_settings_vanity_url_remove)
        save = view.findViewById(R.id.server_settings_vanity_url_save)

        input.setOnEditTextFocusChangeListener { _, hasFocus ->
            val distance = prefix.width
            if (hasFocus) {
                prefix.translateLeft(distance)
                input.translateLeft(distance)
            } else {
                prefix.translateToOriginX()
                input.translateToOriginX()
            }
        }
        // Tapping anywhere in the row focuses the field; the field itself is narrow.
        inputContainer.setOnClickListener { showKeyboard(input) }

        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, input)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // The REST read is part of the state, so the screen is loading until it lands.
        showLoadingUI(true)
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsVanityUrlViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsVanityUrlViewModel.ViewState.Uninitialized -> showLoadingUI(true)
            // No Manage Server, or the guild lost the feature: nothing to show.
            is ServerSettingsVanityUrlViewModel.ViewState.Unavailable -> appActivity?.onBackPressed()
            is ServerSettingsVanityUrlViewModel.ViewState.Loaded -> {
                showLoadingUI(false)
                setActionBarTitle(R.string.vanity_url)
                setActionBarSubtitle(viewState.guildName)
                configureInviteCode(viewState.vanityUrl, viewState.vanityUrlUses)
                state.configureSaveActionView(save)
                save.setOnClickListener {
                    updateVanityUrl(viewState.guildId, input.getTextOrEmpty().trim())
                }
                remove.setOnClickListener { updateVanityUrl(viewState.guildId, "") }
            }
        }
    }

    private fun configureInviteCode(code: String?, uses: Int) {
        // Rendering must not clobber a code the user is halfway through typing.
        input.setText(state.get(input.id, code))
        input.clearFocus()

        val hasCode = !code.isNullOrEmpty()
        if (hasCode) {
            currentUrl.text = SettingsStrings.render(
                requireContext(),
                R.string.vanity_url_help_extended,
                "url" to "$VANITY_URL_PREFIX$code",
            )
        }
        currentUrl.visibility = if (hasCode) View.VISIBLE else View.GONE
        // There is nothing to remove until a code exists.
        remove.visibility = if (hasCode) View.VISIBLE else View.GONE

        header.text = if (code != null) {
            SettingsStrings.render(
                requireContext(),
                R.string.vanity_url_header_uses,
                "uses" to requireContext()
                    .i18nPlural(R.plurals.vanity_url_header_uses_uses, uses, "uses" to uses),
            )
        } else {
            getString(R.string.vanity_url)
        }
    }

    private fun showLoadingUI(loading: Boolean) {
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        // Any new attempt clears the previous rejection.
        errorText.visibility = View.GONE
        remove.isEnabled = !loading
    }

    private fun updateVanityUrl(guildId: Long, code: String) {
        val api = RestAPI.apiOrNull ?: return
        showLoadingUI(true)
        api.updateVanityUrl(guildId, RestAPIParams.VanityUrl(code))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetServerSettingsVanityUrl::class.java,
                // Deliberately no context: this call renders its own errors, see the class KDoc.
                context = null,
                errorHandler = { error ->
                    showLoadingUI(false)
                    if (error.code == VANITY_URL_UNAVAILABLE) {
                        errorText.visibility = View.VISIBLE
                    } else {
                        error.showToasts(context)
                    }
                },
            ) { response: VanityUrlResponse ->
                showLoadingUI(false)
                hideKeyboard()
                configureInviteCode(response.code, response.uses)
                // The saved value is the new baseline, so the save action hides again.
                state.clear(clearDefaults = true)
            }
    }

    private fun View.translateLeft(distance: Int) {
        animate().translationXBy(-distance.toFloat())
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun View.translateToOriginX() {
        animate().translationX(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val ANIMATION_DURATION = 250L
        private const val VANITY_URL_PREFIX = "https://discord.gg/"

        /** API error for "that vanity code is already taken". */
        private const val VANITY_URL_UNAVAILABLE = 50020

        fun newInstance(guildId: Long) = WidgetServerSettingsVanityUrl().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsVanityUrl::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsVanityUrl].
 *
 * Reference: `WidgetServerSettingsVanityUrl.Model`. Note the permission test here is plain
 * [PermissionUtils.can], **not** the elevated variant the other server-settings screens use — the
 * reference does not require MFA elevation for the vanity URL.
 */
class ServerSettingsVanityUrlViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsVanityUrlViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guildId: Long,
        val guildName: String?,
        val vanityUrlCode: String?,
        val vanityUrlUses: Int,
        val canManage: Boolean,
        val canHaveVanityUrl: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** The guild cannot have a vanity URL, or the user may not manage it. */
        object Unavailable : ViewState()

        data class Loaded(
            val guildId: Long,
            val guildName: String?,
            val vanityUrl: String?,
            val vanityUrlUses: Int,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState =
            if (!storeState.canHaveVanityUrl || !storeState.canManage) {
                ViewState.Unavailable
            } else {
                ViewState.Loaded(
                    guildId = storeState.guildId,
                    guildName = storeState.guildName,
                    vanityUrl = storeState.vanityUrlCode,
                    vanityUrlUses = storeState.vanityUrlUses,
                )
            }

        fun observeStoreState(guildId: Long): Observable<StoreState> {
            val vanityUrl = RestAPI.apiOrNull
                ?.getVanityUrl(guildId)
                ?.restSubscribeOn()
                ?: Observable.just(VanityUrlResponse(code = "", uses = 0))
            return Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
                StoreStream.getGuilds().observeGuild(guildId),
                vanityUrl,
            ) { meUser, guildPermissions, guild, vanityUrlResponse ->
                StoreState(
                    guildId = guildId,
                    guildName = guild?.name,
                    // The guild object carries the code; the REST read carries the use count.
                    vanityUrlCode = guild?.vanityUrlCode,
                    vanityUrlUses = vanityUrlResponse.uses,
                    canManage = guild != null && (
                        guild.isOwner(meUser.id) ||
                            PermissionUtils.can(Permission.MANAGE_GUILD, guildPermissions)
                        ),
                    canHaveVanityUrl = guild?.canHaveVanityURL() == true,
                )
            }
        }
    }
}
