package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.application.Application
import com.discord.api.game.Game
import com.discord.api.user.UserProfile
import com.discord.api.user.UserApplicationDynamicField
import com.discord.api.user.UserApplicationIdentity
import com.discord.api.user.UserApplicationPrimaryData
import com.discord.databinding.ViewUserProfileWidgetCardBinding
import com.discord.databinding.ViewUserProfileWidgetsBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.VitoImageView
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexWrap
import java.util.Locale

class UserProfileWidgetsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ViewUserProfileWidgetsBinding.inflate(LayoutInflater.from(context), this, true)
    private var cardColor = ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary)
    private var strongTextColor = ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary)
    private var mutedTextColor = ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary)
    private var borderColor = ColorCompat.getThemedColor(context, R.attr.colorBackgroundAccent)

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    fun bind(
        widgets: List<UserProfile.ProfileWidget>,
        applications: Map<Long, Application>,
        identities: Map<Long, UserApplicationIdentity>,
        games: Map<Long, Game>,
    ) {
        binding.root.removeAllViews()
        widgets.filter(::isDisplayable).forEach { widget -> addWidget(widget, applications, identities, games) }
        visibility = if (binding.root.childCount == 0) GONE else VISIBLE
    }

    fun setProfileColors(
        @ColorInt card: Int,
        @ColorInt text: Int,
        @ColorInt mutedText: Int,
        @ColorInt border: Int,
    ) {
        cardColor = card
        strongTextColor = text
        mutedTextColor = mutedText
        borderColor = border
    }

    private fun addWidget(
        widget: UserProfile.ProfileWidget,
        applications: Map<Long, Application>,
        identities: Map<Long, UserApplicationIdentity>,
        games: Map<Long, Game>,
    ) {
        val card = ViewUserProfileWidgetCardBinding.inflate(
            LayoutInflater.from(context),
            binding.root,
            false,
        )
        card.root.setCardBackgroundColor(cardColor)
        card.root.strokeColor = borderColor
        card.profileWidgetTitle.setTextColor(strongTextColor)
        val data = widget.data
        val application = data.applicationId?.let(applications::get)
        card.profileWidgetTitle.text = title(data.type, application)
        card.profileWidgetIcon.bind(application?.iconUrl())
        when (data.type) {
            UserProfile.WIDGET_FAVORITE_GAMES ->
                data.games.orEmpty().firstOrNull()?.let {
                    card.profileWidgetContent.addView(detailedGame(it, games[it.gameId]))
                }
            UserProfile.WIDGET_CURRENT_GAMES ->
                data.games.orEmpty().distinctBy(UserProfile.ProfileWidgetGame::gameId)
                    .forEachIndexed { index, game ->
                        card.profileWidgetContent.addView(
                            compactGame(game, games[game.gameId]),
                            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                                if (index > 0) topMargin = dp(12)
                            },
                        )
                    }
            UserProfile.WIDGET_WANT_TO_PLAY_GAMES,
            UserProfile.WIDGET_PLAYED_GAMES ->
                addGameGrid(card.profileWidgetContent, data.games.orEmpty(), games)
            UserProfile.WIDGET_APPLICATION -> application?.let {
                card.profileWidgetContent.addView(applicationWidget(it, identities[it.id]))
            }
        }
        if (card.profileWidgetContent.childCount > 0) binding.root.addView(card.root)
    }

    private fun detailedGame(game: UserProfile.ProfileWidgetGame, record: Game?) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cover(record, 92, 122, crop = false))
            addView(gameDetails(game, record, includeComment = true), LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            showGameNameOnClick(record?.name)
        }

    private fun compactGame(game: UserProfile.ProfileWidgetGame, record: Game?) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            addView(cover(record, 56, 72, crop = false))
            addView(gameDetails(game, record, includeComment = false), LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            showGameNameOnClick(record?.name)
        }

    private fun gameDetails(
        game: UserProfile.ProfileWidgetGame,
        record: Game?,
        includeComment: Boolean,
    ) = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(text(record?.name ?: context.getString(R.string.profile_widget_unknown_game), true))
        if (includeComment && !game.comment.isNullOrBlank()) {
            addView(text("“${game.comment.trim()}”", false).apply { setPadding(0, dp(6), 0, 0) })
        }
        if (!game.tags.isNullOrEmpty()) addView(tags(game.tags.orEmpty()))
    }

    private fun addGameGrid(
        container: LinearLayout,
        games: List<UserProfile.ProfileWidgetGame>,
        records: Map<Long, Game>,
    ) {
        games.distinctBy(UserProfile.ProfileWidgetGame::gameId).chunked(3).forEachIndexed { rowIndex, rowGames ->
            container.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                if (rowIndex > 0) setPadding(0, dp(12), 0, 0)
                rowGames.forEachIndexed { index, game ->
                    val record = records[game.gameId]
                    addView(cover(record, 0, 112, crop = true).apply {
                        showGameNameOnClick(record?.name)
                    }, LayoutParams(0, dp(112), 1f).apply {
                        if (index > 0) marginStart = dp(12)
                    })
                }
                repeat(3 - rowGames.size) { addView(View(context), LayoutParams(0, 0, 1f)) }
            })
        }
    }

    private fun applicationWidget(application: Application, identity: UserApplicationIdentity?) = LinearLayout(context).apply {
        orientation = VERTICAL
        val profile = identity?.displayProfile()
        val primary = profile?.data?.primary
        val dynamic = profile?.data?.dynamic.orEmpty()
        val hero = primary?.featuredPlayedCharacterImage?.let { it.proxyUrl ?: it.url }
            ?: dynamic.filterIsInstance<UserApplicationDynamicField.Image>()
                .firstNotNullOfOrNull { it.value.proxyUrl ?: it.value.url }
            ?: application.artwork()?.url
        if (hero != null) addView(VitoImageView(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, dp(156))
            hierarchy.setActualImageScaleType(com.facebook.drawee.drawable.ScalingUtils.ScaleType.CENTER_CROP)
            MGImages.setRoundingParams(this, cornerRadius = dp(8).toFloat(), circle = false)
            setImageURI(hero)
        })
        profile?.username?.takeIf(String::isNotBlank)?.let { username ->
            addView(text(username, true).apply { setPadding(0, dp(12), 0, 0) })
        }
        val stats = primary.stats() + dynamic.mapNotNull { field ->
            when (field) {
                is UserApplicationDynamicField.Text -> field.name to field.value
                is UserApplicationDynamicField.Number -> field.name to field.value.displayNumber()
                is UserApplicationDynamicField.Image,
                is UserApplicationDynamicField.Unknown -> null
            }
        }
        if (stats.isNotEmpty()) addView(statGrid(stats))
        if (profile == null) application.description?.takeIf(String::isNotBlank)?.let { description ->
            addView(text(description, false).apply { setPadding(0, dp(12), 0, 0); maxLines = 4 })
        }
    }

    private fun statGrid(stats: List<Pair<String, String>>) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(12), 0, 0)
        stats.chunked(2).forEachIndexed { index, row ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                if (index > 0) setPadding(0, dp(12), 0, 0)
                row.forEach { (label, value) ->
                    addView(LinearLayout(context).apply {
                        orientation = VERTICAL
                        addView(text(value, true))
                        addView(text(label.label(), false).apply { setPadding(0, dp(2), 0, 0) })
                    }, LayoutParams(0, WRAP_CONTENT, 1f))
                }
                if (row.size == 1) addView(View(context), LayoutParams(0, 0, 1f))
            })
        }
    }

    private fun UserApplicationPrimaryData?.stats(): List<Pair<String, String>> {
        val data = this ?: return emptyList()
        return listOfNotNull(
            data.season?.let { "season" to it },
            data.rankName?.let { "rank_name" to it },
            data.highestRank?.let { "highest_rank" to it },
            data.featuredPlayedCharacter?.let { "featured_character" to it },
            data.playtimeHours?.let { "playtime_hours" to it.displayNumber() },
            data.totalWins?.let { "total_wins" to it.toString() },
            data.totalGames?.let { "total_games" to it.toString() },
            data.totalKills?.let { "total_kills" to it.toString() },
            data.totalAssists?.let { "total_assists" to it.toString() },
            data.totalDeaths?.let { "total_deaths" to it.toString() },
            data.serverName?.let { "server_name" to it },
            data.userId?.let { "user_id" to it },
            data.unionLevel?.let { "union_level" to it },
            data.totalResonators?.let { "total_resonators" to it.toString() },
            data.totalAchievements?.let { "total_achievements" to it.toString() },
            data.totalEchoes?.let { "total_echoes" to it.toString() },
            data.loginDays?.let { "login_days" to it.toString() },
            data.dataBankLevel?.let { "data_bank_level" to it },
        )
    }

    private fun Double.displayNumber() = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private fun cover(record: Game?, widthDp: Int, heightDp: Int, crop: Boolean) = VitoImageView(context).apply {
        layoutParams = LayoutParams(if (widthDp == 0) 0 else dp(widthDp), dp(heightDp))
        val artwork = record?.artwork()
        hierarchy.setActualImageScaleType(if (crop && artwork?.isIcon != true) {
            com.facebook.drawee.drawable.ScalingUtils.ScaleType.CENTER_CROP
        } else {
            com.facebook.drawee.drawable.ScalingUtils.ScaleType.FIT_CENTER
        })
        if (artwork?.isIcon == true) setPadding(dp(10), dp(10), dp(10), dp(10))
        MGImages.setRoundingParams(this, cornerRadius = dp(8).toFloat(), circle = false)
        setImageURI(artwork?.url)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundAccent))
        }
        contentDescription = record?.name
    }

    private fun tags(values: List<String>) = FlexboxLayout(context).apply {
        setPadding(0, dp(8), 0, 0)
        flexWrap = FlexWrap.WRAP
        var expanded = false
        fun render() {
            removeAllViews()
            visibleWidgetTags(values, expanded = expanded).forEach { value ->
                val foreground = mutedTextColor
                addView(text(value.label(), false).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(ColorUtils.setAlphaComponent(foreground, 20))
                        setStroke(dp(1), ColorUtils.setAlphaComponent(foreground, 64))
                    }
                    if (value.startsWith('+')) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            expanded = true
                            render()
                        }
                    }
                }, FlexboxLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(6), dp(6))
                })
            }
        }
        render()
    }

    private fun View.showGameNameOnClick(name: String?) {
        name ?: return
        isClickable = true
        isFocusable = true
        setOnClickListener { android.widget.Toast.makeText(context, name, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun text(value: String, strong: Boolean) = TextView(context).apply {
        text = value
        setTextColor(if (strong) strongTextColor else mutedTextColor)
        textSize = if (strong) 16f else 14f
        if (strong) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun title(type: String, application: Application?) = when (type) {
        UserProfile.WIDGET_FAVORITE_GAMES -> context.getString(R.string.profile_widget_favorite_game)
        UserProfile.WIDGET_CURRENT_GAMES -> context.getString(R.string.profile_widget_current_games)
        UserProfile.WIDGET_WANT_TO_PLAY_GAMES -> context.getString(R.string.profile_widget_want_to_play)
        UserProfile.WIDGET_PLAYED_GAMES -> context.getString(R.string.profile_widget_played_games)
        UserProfile.WIDGET_APPLICATION -> application?.name ?: context.getString(R.string.profile_widget_application)
        else -> type.label()
    }

    private fun isDisplayable(widget: UserProfile.ProfileWidget) = when (widget.data.type) {
        UserProfile.WIDGET_APPLICATION -> widget.data.applicationId != null
        UserProfile.WIDGET_FAVORITE_GAMES,
        UserProfile.WIDGET_CURRENT_GAMES,
        UserProfile.WIDGET_WANT_TO_PLAY_GAMES,
        UserProfile.WIDGET_PLAYED_GAMES -> widget.data.games.orEmpty().isNotEmpty()
        else -> false
    }

    private fun VitoImageView.bind(url: String?) {
        visibility = if (url == null) GONE else VISIBLE
        setImageURI(url)
    }

    private fun Application.iconUrl() = icon?.let { IconUtils.getApplicationIcon(id, it, 64) }
    private fun Game.artwork(): Artwork? = cover()?.let { media ->
        when (media.type) {
            Game.TYPE_HASH -> Artwork(
                IconUtils.getApplicationIcon(id, media.value, 512) + "&keep_aspect_ratio=true",
                false,
            )
            Game.TYPE_URL -> Artwork(media.value, false)
            else -> null
        }
    }
    private fun Application.artwork(): Artwork? = when {
        !coverImage.isNullOrBlank() -> IconUtils.getApplicationIcon(id, coverImage, 512).let { Artwork(it, false) }
        !splash.isNullOrBlank() -> IconUtils.getAssetImage(id, splash, 512)?.let { Artwork(it, false) }
        !icon.isNullOrBlank() -> IconUtils.getApplicationIcon(id, icon, 512)?.let { Artwork(it, true) }
        else -> null
    }

    private fun String.label() = split('_').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class Artwork(val url: String, val isIcon: Boolean)
}

internal fun visibleWidgetTags(tags: List<String>, expanded: Boolean = false, limit: Int = 3): List<String> =
    if (expanded) tags else tags.take(limit) + listOfNotNull((tags.size - limit).takeIf { it > 0 }?.let { "+$it" })
