package com.discord.widgets.servers.guildboost

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.premium.PremiumUtils

class GuildBoostPerkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    private val container: View
    private val headerText: TextView
    private val boostCount: TextView
    private val unlocked: CardView
    private val contentsHeader: TextView
    private val adapter: GuildBoostPerkViewAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.view_nitro_boost_perks, this, true)
        container = findViewById(R.id.container)
        headerText = findViewById(R.id.perks_level_header_text)
        boostCount = findViewById(R.id.perks_level_header_boosts)
        unlocked = findViewById(R.id.perks_level_header_unlocked)
        contentsHeader = findViewById(R.id.perks_level_contents_header)
        val recycler = findViewById<RecyclerView>(R.id.perks_level_contents_recycler)
        adapter = GuildBoostPerkViewAdapter(recycler)
        recycler.adapter = adapter
        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent) =
                event.action == MotionEvent.ACTION_MOVE

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) = Unit
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
    }

    fun configure(premiumTier: Int, currentPremiumTier: Int, guildId: Long) {
        val guild = StoreStream.getGuilds().getGuild(guildId)
        val isUnlocked = currentPremiumTier >= premiumTier
        container.setBackgroundResource(
            if (isUnlocked) R.drawable.drawable_bg_premium_guild_gradient else 0,
        )
        if (!isUnlocked) container.setBackgroundColor(ColorCompat.getThemedColor(this, R.attr.primary_700))

        headerText.setText(
            when (premiumTier) {
                1 -> R.string.guild_settings_guild_premium_perks_title_tier_1
                2 -> R.string.guild_settings_guild_premium_perks_title_tier_2
                3 -> R.string.guild_settings_guild_premium_perks_title_tier_3
                else -> R.string.guild_settings_guild_premium_perks_title_none
            },
        )
        headerText.setCompoundDrawablesCompat(start = tierIcon(premiumTier, isUnlocked))
        headerText.setTextColor(
            if (isUnlocked) ColorCompat.getColor(context, R.color.white)
            else ColorCompat.getThemedColor(context, R.attr.primary_300),
        )

        val required = boostsRequired(premiumTier, guild)
        boostCount.text = context.i18nPlural(
            R.plurals.guild_settings_premium_guild_tier_requirement_required,
            required,
            "#" to required,
        )
        boostCount.visibility = if (isUnlocked) View.GONE else View.VISIBLE
        unlocked.visibility = if (isUnlocked) View.VISIBLE else View.GONE
        contentsHeader.setText(
            if (premiumTier == 1) R.string.guild_settings_guild_premium_perks_base_perks
            else R.string.guild_settings_guild_premium_perks_previous_perks,
        )
        adapter.configure(perks(premiumTier, isUnlocked, guild?.hasFeature(GuildFeature.THREADS_ENABLED) == true))
    }

    private fun perks(
        tier: Int,
        unlocked: Boolean,
        threadsEnabled: Boolean,
    ): List<GuildBoostPerkViewAdapter.GuildBoostPerkViewListItem> {
        fun item(icon: Int, text: CharSequence) = GuildBoostPerkViewAdapter.GuildBoostPerkViewListItem(
            if (unlocked) R.drawable.ic_check_green_24dp else icon,
            text,
        )
        fun emoji(adding: Int) = item(
            R.drawable.ic_smile_24dp,
            i18nFormat(
                R.string.guild_settings_guild_premium_perk_title_tier_any_emoji,
                "adding" to adding,
                "total" to PremiumUtils.getGuildEmojiMaxCount(tier, false),
            ),
        )
        fun sticker(adding: Int) = item(
            R.drawable.ic_sticker_icon_24dp,
            i18nFormat(
                R.string.guild_settings_guild_premium_perk_title_tier_any_sticker,
                "adding" to adding,
                "total" to PremiumUtils.getGuildStickerMaxCount(tier, false),
            ),
        )
        fun audio() = item(
            R.drawable.ic_headset_blue_24dp,
            i18nFormat(
                R.string.guild_settings_guild_premium_perk_title_tier_any_audio_quality,
                "bitrate" to when (tier) { 1 -> 128; 2 -> 256; else -> 384 },
            ),
        )
        fun upload() = item(
            R.drawable.ic_upload_24dp,
            i18nFormat(
                R.string.guild_settings_guild_premium_perk_title_tier_any_upload_limit,
                "fileSize" to i18nFormat(R.string.file_size_mb, "size" to PremiumUtils.getGuildMaxFileSizeMB(tier)),
            ),
        )

        return when (tier) {
            1 -> listOf(
                emoji(50), sticker(15), audio(),
                item(R.drawable.ic_nitro_gifs_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_1_animated_guild_icon)),
                item(R.drawable.ic_image_library_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_1_splash)),
                item(R.drawable.ic_guild_nitro_perk_stream_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_1_streaming)),
            )
            2 -> listOfNotNull(
                emoji(50), sticker(15), audio(), upload(),
                item(R.drawable.ic_image_library_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_2_banner)),
                item(R.drawable.ic_guild_nitro_perk_stream_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_2_streaming)),
                item(R.drawable.ic_thread_locked, getString(R.string.guild_settings_guild_premium_perk_title_tier_2_thread_private)).takeIf { threadsEnabled },
                item(R.drawable.ic_person_shield_purple_24dp, getString(R.string.premium_guild_guild_role_icons)),
            )
            3 -> listOf(
                emoji(100), sticker(30), audio(), upload(),
                item(R.drawable.ic_star_24dp, getString(R.string.guild_settings_guild_premium_perk_title_tier_3_vanity_url)),
                item(R.drawable.ic_nitro_gifs_24dp, getString(R.string.guild_settings_guild_premium_perk_description_tier_3_animated_guild_banner)),
            )
            else -> emptyList()
        }
    }

    private fun getString(resId: Int, vararg args: Any) = context.getString(resId, *args)

    private fun tierIcon(tier: Int, unlocked: Boolean) = when (tier) {
        1 -> if (unlocked) R.drawable.ic_perk_tier_1_boosted else R.drawable.ic_perk_tier_1_unboosted
        2 -> if (unlocked) R.drawable.ic_perk_tier_2_boosted else R.drawable.ic_perk_tier_2_unboosted
        3 -> if (unlocked) R.drawable.ic_perk_tier_3_boosted else R.drawable.ic_perk_tier_3_unboosted
        else -> 0
    }

    private fun boostsRequired(tier: Int, guild: Guild?): Int {
        val smallBucket = guild?.takeIf {
            it.hasFeature(GuildFeature.BOOSTING_TIERS_EXPERIMENT_SMALL_GUILD)
        }?.let {
            StoreStream.getExperiments().getGuildExperiment("2022-03_boosting_tiers_small_guilds", it.id, true)?.bucket
        }
        val mediumBucket = guild?.takeIf {
            it.hasFeature(GuildFeature.BOOSTING_TIERS_EXPERIMENT_MEDIUM_GUILD)
        }?.let {
            StoreStream.getExperiments().getGuildExperiment("2022-03_boosting_tiers_medium_guilds", it.id, true)?.bucket
        }
        val thresholds = when {
            smallBucket == 1 || smallBucket == 2 -> intArrayOf(2, 3, 4)
            mediumBucket == 1 || mediumBucket == 2 -> intArrayOf(2, 7, 10)
            else -> intArrayOf(2, 7, 14)
        }
        return thresholds.getOrElse(tier - 1) { 0 }
    }
}
