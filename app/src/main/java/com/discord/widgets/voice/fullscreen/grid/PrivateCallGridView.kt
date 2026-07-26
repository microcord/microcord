package com.discord.widgets.voice.fullscreen.grid

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.discord.R
import com.discord.stores.StoreVoiceParticipants
import com.discord.views.VoiceUserView

class PrivateCallGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TableLayout(context, attrs) {
    private var callUsers: List<StoreVoiceParticipants.VoiceUser> = emptyList()
    private val callUserViews = linkedMapOf<Long, VoiceUserView>()

    fun configure(callUsers: List<StoreVoiceParticipants.VoiceUser>) {
        val orderChanged = this.callUsers.map { it.user.id } != callUsers.map { it.user.id }
        if (orderChanged) rebuild(callUsers) else visibleUsers(callUsers).forEach(::updateUserView)
        this.callUsers = callUsers
    }

    private fun rebuild(users: List<StoreVoiceParticipants.VoiceUser>) {
        removeAllViews()
        visibleUsers(users).chunked(columnCount()).forEachIndexed { rowIndex, rowUsers ->
            val row = TableRow(context).apply { gravity = Gravity.CENTER }
            rowUsers.forEach { row.addView(updateUserView(it)) }
            overflowView(users, rowIndex)?.let(row::addView)
            if (rowIndex == 0 && users.size == 2) {
                row.addView(
                    LayoutInflater.from(context).inflate(R.layout.view_private_call_grid_spacer, row, false),
                    1,
                )
            }
            addView(row)
        }
    }

    private fun updateUserView(user: StoreVoiceParticipants.VoiceUser): VoiceUserView =
        callUserViews.getOrPut(user.user.id) {
            VoiceUserView(context, null).apply {
                layoutParams = TableRow.LayoutParams().apply {
                    val margin = 2.dp
                    setMargins(margin, margin, margin, margin)
                }
                setAnimateAvatarWhenRinging(true)
                setFadeWhenDisconnected(true)
                setAvatarSize(92.dp)
                setRingMargin(6.dp)
            }
        }.also {
            // `avatar_size_unrestricted` is 0dp: the tile has already been sized to 92dp above, so
            // the avatar must not re-clamp itself to a dimen of its own.
            it.setVoiceUser(user, R.dimen.avatar_size_unrestricted)
            it.isSelected = false
        }

    private fun overflowView(users: List<StoreVoiceParticipants.VoiceUser>, rowIndex: Int): View? {
        if (users.size <= maxShownParticipants() || rowIndex != maxShownParticipants() / columnCount() - 1) {
            return null
        }
        return (LayoutInflater.from(context)
            .inflate(R.layout.view_private_call_grid_overflow, this, false) as TextView).apply {
            layoutParams = TableRow.LayoutParams(92.dp, 92.dp).apply {
                val margin = 8.dp
                setMargins(margin, margin, margin, margin)
            }
            text = "+${users.size - maxShownParticipants()}"
            contentDescription = text
        }
    }

    private fun visibleUsers(users: List<StoreVoiceParticipants.VoiceUser>) =
        if (users.size > maxShownParticipants()) users.take(maxShownParticipants() - 1) else users

    private fun columnCount() = columnCount(isLandscape)
    private fun maxShownParticipants() = maxShownParticipants(isLandscape)
    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    internal companion object {
        fun columnCount(landscape: Boolean) = if (landscape) 6 else 3
        fun maxShownParticipants(landscape: Boolean) = if (landscape) 6 else 9
        fun visibleParticipantCount(total: Int, landscape: Boolean) =
            if (total > maxShownParticipants(landscape)) maxShownParticipants(landscape) - 1 else total
    }
}
