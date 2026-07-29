package com.discord.widgets.nux

import com.discord.api.science.AnalyticsSchema
import com.discord.app.LoggingConfig

class WidgetHubGuildTemplates : WidgetGuildTemplates() {
    override val loggingConfig = LoggingConfig(
        autoLogImpressionOnChanged = false,
        impressionSchemaProvider = { HubTemplateImpression },
    )

    private object HubTemplateImpression : AnalyticsSchema {
        override val analyticsSchemaTypeName = "impression_hub_create_guild_template"
    }
}
