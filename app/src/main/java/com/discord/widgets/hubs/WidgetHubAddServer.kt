package com.discord.widgets.hubs

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.i18n.I18nSpans
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.ScreenTitleView
import com.discord.views.segmentedcontrol.CardSegment
import com.discord.views.segmentedcontrol.SegmentedControlContainer
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Add a server to <hub>" — picks one of the user's own servers to list in the hub directory.
 *
 * Binds `R.layout.widget_hub_add_server`. Two tabs over one list: servers the user administers that
 * are not listed yet, and the ones that already are. Tapping the first kind opens
 * [WidgetHubDescription]; tapping the second opens the edit/remove options for the existing listing.
 *
 * Activity-scoped view model, because [WidgetHubDescription] is pushed on top of this screen and this
 * screen must still be showing the same lists when it comes back.
 */
class WidgetHubAddServer : AppFragment(R.layout.widget_hub_add_server) {

    private val viewModel: WidgetHubAddServerViewModel by appActivityViewModels { WidgetHubAddServerViewModel() }

    private var adapter: DiscordHubAddServerAdapter? = null
    private lateinit var header: ScreenTitleView
    private lateinit var segmentedControl: SegmentedControlContainer
    private lateinit var createServerButton: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.header)
        segmentedControl = view.findViewById(R.id.segmented_control)
        createServerButton = view.findViewById(R.id.discord_u_add_server_create_server)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        adapter = MGRecyclerAdapter.configure(
            DiscordHubAddServerAdapter(recycler) { guildId -> onServerClicked(guildId) },
        )

        view.findViewById<CardSegment>(R.id.segmented_control_guilds_not_added)
            .setText(getString(R.string.hub_choose_guild_choose_tab))
        view.findViewById<CardSegment>(R.id.segmented_control_guilds_added)
            .setText(getString(R.string.hub_choose_guild_added_tab))
        segmentedControl.setup(WidgetHubAddServerViewModel.CHOOSE_GUILD)
        segmentedControl.setOnSegmentSelectedChangeListener { index -> viewModel.setIndex(index) }

        createServerButton.setOnClickListener { onCreateServerClicked() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubAddServer::class.java) { configureUI(it) }
    }

    override fun onDestroyView() {
        adapter?.unsubscribeFromUpdates()
        adapter = null
        super.onDestroyView()
    }

    private fun configureUI(state: HubAddServerState) {
        val guilds = when (state.selectedIndex) {
            WidgetHubAddServerViewModel.CHOOSE_GUILD -> state.selectableGuilds
            WidgetHubAddServerViewModel.GUILDS_ADDED -> state.addedGuilds
            else -> return
        }
        adapter?.setGuilds(guilds)
        segmentedControl.setSelectedIndex(state.selectedIndex)
        header.setTitle(
            I18nSpans.render(getString(R.string.hub_choose_guild_title), "guildName" to state.hubName),
        )
    }

    /**
     * Opens the listing form, or the options for a server that is already listed.
     *
     * @param isNewGuild the server was created moments ago inside this flow, which the listing form
     *   reports back so the caller can distinguish "added an existing server" from "created one".
     */
    private fun onServerClicked(guildId: Long, isNewGuild: Boolean = false) {
        val context = context ?: return
        val channelId = viewModel.getChannelId()
        if (channelId == null) {
            // No directory channel resolved — either the selected guild is not a hub, or the
            // directory store has not been installed. Report it instead of doing nothing.
            Toast.makeText(context, R.string.error_generic_title, Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.withViewState { state ->
            val existing = state.addedDirectories.firstOrNull { it.guild.id == guildId }
            if (existing != null) {
                showServerOptions(guildId, channelId, existing.description, existing.primaryCategoryId, state.hubName)
            } else {
                AppScreenRouter.replaceFragment(
                    fragmentManager = parentFragmentManager,
                    context = context,
                    screen = WidgetHubDescription::class.java,
                    addToBackStack = true,
                    arguments = HubDescriptionArgs(
                        guildId = guildId,
                        channelId = channelId,
                        isEditing = false,
                        hubName = viewModel.getHubName(),
                        isNewGuild = isNewGuild,
                    ).toArgsBundle(),
                )
            }
        }
    }

    /**
     * Edit / remove for a server that is already listed.
     *
     * The reference shows `DirectoryUtils.showServerOptions`, a bottom sheet that has not been
     * reconstructed (it is owned by the `widgets/directories` wave). The same two actions and the same
     * confirmation string are offered here through a dialog; replace this with the sheet when it
     * lands.
     */
    private fun showServerOptions(
        guildId: Long,
        channelId: Long,
        description: String,
        primaryCategoryId: Int,
        hubName: String,
    ) {
        val context = context ?: return
        val options = arrayOf(
            getString(R.string.hub_entry_update),
            getString(R.string.hub_entry_remove),
        )
        MaterialAlertDialogBuilder(context)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> AppScreenRouter.replaceFragment(
                        fragmentManager = parentFragmentManager,
                        context = context,
                        screen = WidgetHubDescription::class.java,
                        addToBackStack = true,
                        arguments = HubDescriptionArgs(
                            guildId = guildId,
                            channelId = channelId,
                            isEditing = true,
                            hubName = hubName,
                            description = description,
                            primaryCategoryId = primaryCategoryId,
                        ).toArgsBundle(),
                    )
                    1 -> confirmRemoval(guildId, channelId)
                }
            }
            .show()
    }

    private fun confirmRemoval(guildId: Long, channelId: Long) {
        val context = context ?: return
        val guildName = StoreStream.getGuilds().getGuild(guildId)?.name.orEmpty()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.hub_entry_remove)
            .setMessage(
                I18nSpans.render(getString(R.string.hub_entry_remove_body), "guildName" to guildName),
            )
            .setPositiveButton(R.string.remove) { _, _ -> viewModel.removeGuild(guildId, channelId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * The reference opens `widgets/nux/WidgetHubGuildTemplates` for a result here, with
     * `GuildCreateArgs(trigger = DIRECTORY_CHANNEL, GuildTemplateArgs(hub title/subtitle, HUB
     * templates))`, and lists the freshly created server through [onServerClicked] with
     * `isNewGuild = true`.
     *
     * The whole `widgets/nux` package is unreconstructed, so the button reports the gap rather than
     * appearing to work.
     */
    private fun onCreateServerClicked() {
        val context = context ?: return
        Toast.makeText(context, R.string.error_generic_title, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Opens this screen for a result.
         *
         * [resultHandler] must come from [registerAddServerResultHandler], registered by the calling
         * fragment as a field. The reference registers it inside this method, which modern
         * `FragmentActivity` rejects once the caller is `STARTED`.
         */
        fun startScreenForResult(fragment: AppFragment, resultHandler: ActivityResultLauncher<Intent>) {
            AppScreenRouter.launchForResult(
                fragment.requireContext(),
                resultHandler,
                WidgetHubAddServer::class.java,
            )
        }
    }
}

/**
 * Registers the handler that shows [WidgetHubAddServerConfirmationDialog] once a server has been
 * listed. Call from a field initializer, then pass the result to
 * [WidgetHubAddServer.startScreenForResult].
 *
 * Port of `com.discord.utilities.hubs.HubUtilsKt.getAddServerActivityResultHandler`; it lives here
 * only because `utilities/hubs` is outside this wave's ownership.
 */
fun AppFragment.registerAddServerResultHandler(): ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val descriptionResult = result.data?.getArgsFromIntent<DescriptionResult>() ?: return@registerForActivityResult
        WidgetHubAddServerConfirmationDialog.show(
            parentFragmentManager,
            AddServerConfirmationArgs(descriptionResult.guildId, descriptionResult.hubName),
        )
    }
