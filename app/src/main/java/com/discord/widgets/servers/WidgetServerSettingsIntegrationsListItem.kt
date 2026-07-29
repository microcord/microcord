package com.discord.widgets.servers

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.models.domain.ModelGuildIntegration
import com.discord.restapi.RestAPIParams
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import rx.functions.Action1

/**
 * One integration row: the connected account, whether it is syncing roles into this guild, and a way
 * into its settings.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsIntegrationsListItem`. Three things in
 * it are easy to get wrong and are reproduced deliberately:
 *
 * - **While an integration is syncing the whole row is inert** — the header click, the switch and
 *   the settings icon are all disabled and a translucent overlay covers the row. Discord's sync is a
 *   server-side batch job, so acting on a half-synced integration would race it.
 * - **Turning the switch *off* is the destructive direction** and goes through a confirmation
 *   dialog, because it deletes the integration rather than pausing it (`DELETE .../integrations/{id}`).
 *   Turning it *on* is immediate.
 * - **The switch is moved optimistically only when enabling**, together with putting the row into
 *   the syncing state, because the gateway's integration update lags the REST call.
 */
class WidgetServerSettingsIntegrationsListItem(
    adapter: WidgetServerSettingsIntegrations.Adapter,
) : MGRecyclerViewHolder<WidgetServerSettingsIntegrations.Adapter, WidgetServerSettingsIntegrations.Model.IntegrationItem>(
    R.layout.widget_server_settings_integration_list_item,
    adapter,
) {

    private val headerContainer: View = itemView.findViewById(R.id.integration_header_container)
    private val disabledOverlay: View = itemView.findViewById(R.id.integration_header_disabled_overlay)
    private val icon: ImageView = itemView.findViewById(R.id.integration_icon)
    private val name: TextView = itemView.findViewById(R.id.integration_name)
    private val ownerName: TextView = itemView.findViewById(R.id.integration_owner_name)
    private val settingsIcon: ImageView = itemView.findViewById(R.id.integration_settings_icon)
    private val syncSwitch: CheckedSetting = itemView.findViewById(R.id.integration_sync_switch)
    private val syncingProgressBar: ProgressBar =
        itemView.findViewById(R.id.integration_syncing_progress_bar)

    override fun onConfigure(
        position: Int,
        data: WidgetServerSettingsIntegrations.Model.IntegrationItem,
    ) {
        super.onConfigure(position, data)
        val integration = data.integration
        val isTwitch = integration.type == ModelGuildIntegration.TYPE_TWITCH
        val isSyncing = integration.syncing
        val isEnabled = integration.enabled
        val integrationId = integration.id

        headerContainer.setOnClickListener(
            if (isSyncing) null else View.OnClickListener { adapter.onIntegrationSelected(integrationId) },
        )

        name.text = integration.displayName
        ownerName.text = integration.user?.username
        icon.setImageResource(
            if (isTwitch) R.drawable.asset_account_sync_twitch else R.drawable.asset_account_sync_youtube,
        )
        // Reference bug reproduced: this line intends to dim a *disabled* integration as well as a
        // syncing one, but `showSyncingUI` immediately below re-assigns the same view from
        // `isSyncing` alone, so the disabled case never actually dims. Kept bug-for-bug; the
        // ordering is what the reference does.
        disabledOverlay.visibility = if (isSyncing || !isEnabled) View.VISIBLE else View.GONE
        showSyncingUI(isSyncing)

        syncSwitch.setChecked(isEnabled, false)
        syncSwitch.isEnabled = !isSyncing
        if (isSyncing) {
            syncSwitch.setOnCheckedListener(null)
        } else {
            syncSwitch.setOnCheckedListener(
                Action1 { checked ->
                    if (checked) {
                        // The row's switch is already on, so a "checked" callback means the user
                        // just turned it off — and that deletes the integration.
                        showDisableSyncDialog(
                            data.guildId,
                            integrationId,
                            isTwitch,
                            syncSwitch.context,
                        )
                    } else {
                        enableIntegration(data.guildId, integration)
                    }
                },
            )
        }
    }

    private fun enableIntegration(guildId: Long, integration: ModelGuildIntegration) {
        val api = RestAPI.apiOrNull ?: return
        api.enableIntegration(
            guildId,
            RestAPIParams.EnableIntegration(
                type = integration.type,
                id = integration.id.toString(),
            ),
        )
            .restSubscribeOn()
            .ui()
            .appSubscribe(
                WidgetServerSettingsIntegrationsListItem::class.java,
                syncSwitch.context,
            ) {
                // The gateway's integration update lags the response, so the row shows the outcome
                // immediately and the store correction is a no-op when it lands.
                syncSwitch.setChecked(true, false)
                syncSwitch.isEnabled = false
                showSyncingUI(true)
            }
    }

    /**
     * Confirms removing the integration.
     *
     * The body differs per platform because the consequences differ — a Twitch integration is what
     * grants subscriber roles, a YouTube one what grants member roles.
     */
    private fun showDisableSyncDialog(
        guildId: Long,
        integrationId: Long,
        isTwitch: Boolean,
        context: Context,
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.widget_server_settings_confirm_disable_integration, null, false)
        val dialog = AlertDialog.Builder(context).setView(view).create()
        view.findViewById<TextView>(R.id.server_settings_confirm_disable_integration_body).setText(
            if (isTwitch) {
                R.string.disable_integration_twitch_body
            } else {
                R.string.disable_integration_youtube_body
            },
        )
        view.findViewById<MaterialButton>(R.id.server_settings_confirm_disable_integration_cancel)
            .setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.server_settings_confirm_disable_integration_confirm)
            .setOnClickListener {
                val api = RestAPI.apiOrNull ?: return@setOnClickListener
                api.deleteGuildIntegration(guildId, integrationId)
                    .restSubscribeOn()
                    .ui()
                    .appSubscribe(
                        WidgetServerSettingsIntegrationsListItem::class.java,
                        context,
                    ) { dialog.dismiss() }
            }
        dialog.show()
    }

    private fun showSyncingUI(isSyncing: Boolean) {
        settingsIcon.visibility = if (isSyncing) View.GONE else View.VISIBLE
        syncingProgressBar.visibility = if (isSyncing) View.VISIBLE else View.GONE
        disabledOverlay.visibility = if (isSyncing) View.VISIBLE else View.GONE
        // Called on its own after an optimistic enable, so the header must be re-disabled here too.
        if (isSyncing) headerContainer.setOnClickListener(null)
    }
}
