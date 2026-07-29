package com.discord.widgets.channels

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnPreDraw
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.views.PileView
import com.discord.views.user.mediaProxySize

class UserAkaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    var guildMembers: List<GuildMember> = emptyList()
    var pileItems: List<PileView.Item> = emptyList()
    val nicknameStringBuilder = StringBuilder()

    private val akaText: TextView
    private val avatarsPileView: PileView
    private val nicknames: TextView
    private val spaceForMeasuring: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.user_aka_view, this)
        akaText = requireNotNull(findViewById(R.id.aka_text))
        avatarsPileView = requireNotNull(findViewById(R.id.avatars_pile_view))
        nicknames = requireNotNull(findViewById(R.id.nicknames))
        spaceForMeasuring = requireNotNull(findViewById(R.id.space_for_measuring))
    }

    fun configure(guildMembers: List<GuildMember>) {
        this.guildMembers = guildMembers
        val avatarSize = resources.getDimension(R.dimen.aka_avatar_width).toInt()
        pileItems = guildMembers.filter(GuildMember::hasAvatar).mapNotNull { member ->
            IconUtils.getForGuildMember(member, avatarSize)?.let { url ->
                PileView.Item({ url }, null)
            }
        }
        avatarsPileView.setItems(pileItems)

        doOnPreDraw {
            val leadingMargin = akaText.measuredWidth + avatarsPileView.measuredWidth +
                if (pileItems.isEmpty()) spaceForMeasuring.measuredWidth else dpToPixels(8)
            val text = SpannableString(akaNicknames(guildMembers).joinToString(", ")).apply {
                setSpan(
                    LeadingMarginSpan.Standard(leadingMargin, 0),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            nicknames.text = text
            nicknames.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

}

internal fun akaNicknames(guildMembers: List<GuildMember>): List<String> =
    guildMembers.mapNotNull { it.nick?.takeUnless(String::isBlank) }

internal fun guildMemberAvatarUrl(member: GuildMember, size: Int, extension: String): String? {
    val hash = member.avatarHash ?: return null
    if (hash.startsWith("data:")) return hash
    return "https://cdn.discordapp.com/guilds/${member.guildId}/users/${member.userId}/avatars/" +
        "$hash.$extension?size=${mediaProxySize(size)}"
}
