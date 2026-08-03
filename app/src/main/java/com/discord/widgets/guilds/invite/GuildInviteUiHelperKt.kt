@file:JvmName("GuildInviteUiHelperKt")

package com.discord.widgets.guilds.invite

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppToast
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelInvite
import com.discord.models.invite.InviteUtils

fun copyLinkClick(context: Context, invite: ModelInvite?, mostRecentIntent: Intent) =
    copyLinkClick(context, invite, mostRecentIntent.getStringExtra(EXTRA_ORIGIN_SOURCE).orEmpty())

@Suppress("UNUSED_PARAMETER")
fun copyLinkClick(context: Context, invite: ModelInvite?, analyticsSource: String) {
    copyLink(context, getInviteLink(invite, context.resources))
}

@Suppress("UNUSED_PARAMETER")
fun copyLinkClick(
    context: Context,
    guildInvite: GuildInvite,
    channel: Channel?,
    analyticsSource: String,
) {
    copyLink(context, guildInvite.toLink())
}

fun shareLinkClick(context: Context?, invite: ModelInvite) {
    context ?: return
    shareLink(context, getInviteLink(invite, context.resources))
}

@Suppress("UNUSED_PARAMETER")
fun shareLinkClick(context: Context?, guildInvite: GuildInvite, channel: Channel?) {
    context ?: return
    shareLink(context, guildInvite.toLink())
}

fun getInviteLinkText(context: Context): CharSequence = context.i18nFormat(
    R.string.invite_links_expire_after_default,
    "days" to context.i18nPlural(R.plurals.duration_days_days, DEFAULT_EXPIRATION_DAYS, "days" to DEFAULT_EXPIRATION_DAYS),
)

private const val DEFAULT_EXPIRATION_DAYS = 7

fun getInviteSettingsText(context: Context, expirationDurationMs: Long): CharSequence =
    if (expirationDurationMs <= 0L) {
        context.getString(R.string.max_age_never_description_mobile)
    } else {
        context.i18nFormat(
            R.string.invite_expired_subtext_mobile,
            "maxAge" to humanizeDurationRounded(context, expirationDurationMs),
        )
    }

fun getInviteSettingsText(context: Context, expirationDurationMs: Long, maxUses: Int): CharSequence {
    val usesText = if (maxUses == 0) {
        context.getString(R.string.max_uses_description_unlimited_uses)
    } else {
        context.i18nPlural(
            R.plurals.max_uses_description_mobile_maxUses,
            maxUses,
            "maxUses" to maxUses,
        )
    }
    return if (expirationDurationMs <= 0L) {
        context.i18nFormat(
            R.string.invite_settings_description_no_expiration,
            "maxAge" to context.getString(R.string.max_age_never_description_mobile),
            "maxUses" to usesText,
        )
    } else {
        context.i18nFormat(
            R.string.invite_settings_expired_description,
            "maxAge" to humanizeDurationRounded(context, expirationDurationMs),
            "maxUses" to usesText,
        )
    }
}

private fun copyLink(context: Context, inviteLink: String) =
    AppToast.copyToClipboard(context, inviteLink, R.string.invite_link_copied)

private fun shareLink(context: Context, inviteLink: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, inviteLink)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.tip_instant_invite_title3)))
}

private fun getInviteLink(inviteModel: ModelInvite?, resources: Resources): String =
    inviteModel?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE
