package com.discord.views

import android.content.Context
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.discord.R

class ServerFolderView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs, 0) {
    val folderImage: ImageView
    val guildViews: List<GuildView>
    private val guildGrid: GridLayout
    var folderId: Long? = null
    var isOpen = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_server_folder, this)
        folderImage = requireNotNull(findViewById(R.id.folder_view_folder_image))
        guildViews = listOf(
            requireNotNull(findViewById(R.id.guild_view_1)),
            requireNotNull(findViewById(R.id.guild_view_2)),
            requireNotNull(findViewById(R.id.guild_view_3)),
            requireNotNull(findViewById(R.id.guild_view_4)),
        )
        guildGrid = requireNotNull(findViewById(R.id.guild_views))
        orientation = VERTICAL
    }

    fun showCurrentState() {
        folderImage.visibility = if (isOpen) View.VISIBLE else View.GONE
        guildGrid.visibility = if (isOpen) View.GONE else View.VISIBLE
    }

    /**
     * Keeps the folder's own image in both states, for folders whose icon identifies them. A
     * collapsed preview of the guilds inside would make the pending stack look like one of the
     * user's own folders.
     */
    fun showFolderImageOnly() {
        folderImage.visibility = View.VISIBLE
        guildGrid.visibility = View.GONE
    }

    fun animateState(animationId: Int) {
        val animation = AnimationUtils.loadAnimation(context, animationId).apply {
            if (animationsDisabled()) duration = 0
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit
                override fun onAnimationRepeat(animation: Animation?) = Unit
                override fun onAnimationEnd(animation: Animation?) = showCurrentState()
            })
        }
        folderImage.visibility = View.VISIBLE
        guildGrid.visibility = View.VISIBLE
        folderImage.startAnimation(animation)
    }

    fun a() = showCurrentState()
    fun b(animationId: Int) = animateState(animationId)

    private fun animationsDisabled() = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}
