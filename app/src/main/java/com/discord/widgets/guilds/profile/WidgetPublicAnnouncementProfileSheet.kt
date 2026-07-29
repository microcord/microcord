package com.discord.widgets.guilds.profile

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.view.SimpleDraweeView

class WidgetPublicAnnouncementProfileSheet : AppBottomSheet() {
    override fun getContentViewResId(): Int = R.layout.widget_announcement_guild_profile_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        IconUtils.setIcon(
            view.findViewById<SimpleDraweeView>(R.id.announcement_guild_avatar),
            "asset://asset/images/system_user_avatar.png",
            R.dimen.avatar_size_xxlarge,
        )
    }

    companion object {
        fun show(fragmentManager: FragmentManager) =
            WidgetPublicAnnouncementProfileSheet().show(
                fragmentManager,
                WidgetPublicAnnouncementProfileSheet::class.java.name,
            )
    }
}
