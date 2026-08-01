package com.discord.stores

import co.discord.media_engine.VideoInputDeviceDescription
import co.discord.media_engine.VideoInputDeviceFacing
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class StoreMediaEngineTest {
    @Test
    fun cameraSelectionReleasesIndexBeforeSelectingGuidAndEnablingVideo() {
        val controller = FakeController()
        val store = StoreMediaEngine(controller)

        store.selectVideoInputDevice("front")

        assertEquals(listOf("index:-1", "guid:front", "video:true"), controller.events)
        assertEquals("front", store.getSelectedVideoInputDeviceBlocking()?.guid)
    }

    private class FakeController : StoreMediaEngine.MediaEngineController {
        val events = mutableListOf<String>()
        private val devices = listOf(
            VideoInputDeviceDescription("Front", "front", VideoInputDeviceFacing.Front),
        )

        override fun getVideoInputDevices(callback: (List<VideoInputDeviceDescription>) -> Unit) = callback(devices)
        override fun setVideoInputDevice(index: Int) { events += "index:$index" }
        override fun setVideoInputDevice(deviceGuid: String) { events += "guid:$deviceGuid" }
        override fun setVideoInputEnabled(enabled: Boolean) { events += "video:$enabled" }
        override fun setAudioInputEnabled(enabled: Boolean) = Unit
        override fun setPttActive(active: Boolean) = Unit
        override fun rankRtcRegions(
            regions: List<ModelRtcLatencyRegion>,
            callback: (List<String>) -> Unit,
        ) = callback(regions.map(ModelRtcLatencyRegion::region))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializePersister() {
            Persister.init(Files.createTempDirectory("store-media-engine-test").toFile())
        }
    }
}
