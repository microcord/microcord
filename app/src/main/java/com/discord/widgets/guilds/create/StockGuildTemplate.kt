package com.discord.widgets.guilds.create

import android.content.res.Resources
import androidx.annotation.StringRes
import com.discord.R
import com.discord.restapi.RestAPIParams

enum class StockGuildTemplate {
    FRIEND_GROUP,
    STUDY_GROUP,
    GAMING_GROUP,
    CONTENT_CREATOR,
    CLUB,
    LOCAL_COMMUNITY,
    HUB_SCHOOL_CLUB,
    HUB_STUDY_GROUP,
    CLASS,
    SOCIAL,
    MAJOR,
    DORM,
    CREATE;

    fun getChannels(resources: Resources): List<RestAPIParams.CreateGuildChannel> = with(resources) {
        when (this@StockGuildTemplate) {
            FRIEND_GROUP -> category(text, 100, TEXT, system(general), normal(game), normal(music)) +
                category(voice, 200, VOICE, normal(lounge), normal(streamRoom))
            STUDY_GROUP -> category(information, 100, TEXT, normal(welcome), normal(notes)) +
                category(text, 200, TEXT, system(general), normal(homework), normal(session), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(studyRoom(1)), normal(studyRoom(2)))
            GAMING_GROUP -> category(text, 100, TEXT, system(general), normal(clips)) +
                category(voice, 200, VOICE, normal(lobby), normal(gaming))
            CONTENT_CREATOR -> category(information, 100, TEXT, normal(welcome), normal(announcements)) +
                category(text, 200, TEXT, system(general), normal(events), normal(ideas)) +
                category(voice, 300, VOICE, normal(lounge), normal(communityHangout), normal(streamRoom))
            CLUB -> category(information, 100, TEXT, normal(welcome), normal(announcements)) +
                category(text, 200, TEXT, system(general), normal(meetingPlans)) +
                category(voice, 300, VOICE, normal(lounge), normal(meetingRoom))
            LOCAL_COMMUNITY -> category(information, 100, TEXT, normal(welcome), normal(announcements), normal(resourceChannel)) +
                category(text, 200, TEXT, system(general), normal(meetingPlans), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(meetingRoom))
            HUB_SCHOOL_CLUB -> category(information, 100, TEXT, normal(welcome), normal(announcements), normal(resourceChannel)) +
                category(text, 200, TEXT, system(general), normal(introductions), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(meetingRoom1), normal(meetingRoom2))
            HUB_STUDY_GROUP -> category(info, 100, TEXT, normal(notes), normal(help)) +
                category(chat, 200, TEXT, normal(introductions), system(general), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(studyRoom(1)), normal(studyRoom(2)))
            CLASS -> category(info, 100, TEXT, normal(assignments), normal(help), normal(resourceChannel)) +
                category(chat, 200, TEXT, normal(introductions), system(general), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(studyRoom(1)), normal(studyRoom(2)))
            SOCIAL -> category(chat, 200, TEXT, normal(introductions), system(general), normal(offTopic), normal(meetups)) +
                category(voice, 300, VOICE, normal(couches), normal(movieRoom), normal(unnumberedStudyRoom))
            MAJOR -> category(info, 100, TEXT, normal(resourceChannel), normal(help)) +
                category(chat, 200, TEXT, normal(introductions), system(general), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(studyRoom(1)), normal(studyRoom(2)))
            DORM -> category(info, 100, TEXT, normal(dormNews)) +
                category(chat, 200, TEXT, normal(introductions), system(general), normal(offTopic)) +
                category(voice, 300, VOICE, normal(lounge), normal(movieRoom), normal(couches))
            CREATE -> emptyList()
        }
    }

    fun getSystemChannelId(): Long = SYSTEM_CHANNEL_ID

    private companion object {
        const val TEXT = 0
        const val VOICE = 2
        const val CATEGORY = 4
        const val SYSTEM_CHANNEL_ID = 11L

        fun Resources.value(@StringRes id: Int) = getString(id)
        val Resources.text get() = value(R.string.guild_template_name_category_text)
        val Resources.voice get() = value(R.string.guild_template_name_category_voice)
        val Resources.information get() = value(R.string.guild_template_name_category_information)
        val Resources.info get() = value(R.string.guild_template_name_category_info)
        val Resources.chat get() = value(R.string.guild_template_name_category_chat)
        val Resources.general get() = value(R.string.guild_template_name_general)
        val Resources.game get() = value(R.string.guild_template_name_game)
        val Resources.music get() = value(R.string.guild_template_name_music)
        val Resources.lounge get() = value(R.string.guild_template_name_voice_lounge)
        val Resources.streamRoom get() = value(R.string.guild_template_name_voice_stream_room)
        val Resources.welcome get() = value(R.string.guild_template_name_welcome_and_rules)
        val Resources.notes get() = value(R.string.guild_template_name_notes_resources)
        val Resources.homework get() = value(R.string.guild_template_name_homework_help)
        val Resources.session get() = value(R.string.guild_template_name_session_planning)
        val Resources.offTopic get() = value(R.string.guild_template_name_off_topic)
        val Resources.clips get() = value(R.string.guild_template_name_clips_and_highlights)
        val Resources.lobby get() = value(R.string.guild_template_name_voice_lobby)
        val Resources.gaming get() = value(R.string.guild_template_name_voice_gaming)
        val Resources.announcements get() = value(R.string.guild_template_name_announcements)
        val Resources.events get() = value(R.string.guild_template_name_events)
        val Resources.ideas get() = value(R.string.guild_template_name_ideas_and_feedback)
        val Resources.communityHangout get() = value(R.string.guild_template_name_voice_community_hangout)
        val Resources.meetingPlans get() = value(R.string.guild_template_name_meeting_plans)
        val Resources.meetingRoom get() = value(R.string.guild_template_name_voice_meeting_room)
        val Resources.resourceChannel get() = value(R.string.guild_template_name_resources)
        val Resources.introductions get() = value(R.string.guild_template_name_introductions)
        val Resources.meetingRoom1 get() = value(R.string.guild_template_name_voice_meeting_room_1)
        val Resources.meetingRoom2 get() = value(R.string.guild_template_name_voice_meeting_room_2)
        val Resources.help get() = value(R.string.guild_template_name_help_questions)
        val Resources.assignments get() = value(R.string.guild_template_name_assignments)
        val Resources.meetups get() = value(R.string.guild_template_name_meetups)
        val Resources.couches get() = value(R.string.guild_template_name_voice_couches)
        val Resources.movieRoom get() = value(R.string.guild_template_name_voice_movie_room)
        val Resources.unnumberedStudyRoom get() = value(R.string.guild_template_name_voice_unnumbered_study_room)
        val Resources.dormNews get() = value(R.string.guild_template_name_dorm_news)
        fun Resources.studyRoom(number: Int) = value(R.string.guild_template_name_voice_study_room)
            .replace("{number}", number.toString())

        fun normal(name: String) = ChannelTemplate.NormalChannel(name)
        fun system(name: String) = ChannelTemplate.SystemChannel(name)

        fun category(
            name: String,
            id: Long,
            channelType: Int,
            vararg channels: ChannelTemplate,
        ): List<RestAPIParams.CreateGuildChannel> = listOf(
            RestAPIParams.CreateGuildChannel(CATEGORY, id, name, null, null, null),
        ) + channels.map { channel ->
            RestAPIParams.CreateGuildChannel(channelType, channel.id, channel.name, id, null, null)
        }
    }
}
