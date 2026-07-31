package com.discord.widgets.guilds.create

import android.os.Bundle
import android.os.Parcel
import androidx.core.os.BundleCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuildCreationParcelableTest {
    @Test
    fun optionsSurviveFragmentArgumentRecreation() {
        val options = options()
        val fragment = TestWidgetGuildCreate().apply {
            arguments = recreate(Bundle().apply {
                putParcelable(WidgetGuildCreate.EXTRA_OPTIONS, options)
            })
        }

        assertEquals(options, fragment.readOptions())
    }

    @Test
    fun creationIntentArgsSurviveBundleRecreation() {
        val args = CreationIntentArgs(CreateGuildTrigger.IN_APP, options())
        val restored = recreate(Bundle().apply { putParcelable("args", args) })

        assertEquals(
            args,
            BundleCompat.getParcelable(restored, "args", CreationIntentArgs::class.java),
        )
    }

    private fun options() = WidgetGuildCreate.Options(
        analyticsLocation = "Guild List",
        template = StockGuildTemplate.CONTENT_CREATOR,
        showChannelPrompt = true,
        customTitle = "Create a server",
        closeWithResult = true,
    )

    private fun recreate(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private class TestWidgetGuildCreate : WidgetGuildCreate() {
        fun readOptions() = options
    }
}
