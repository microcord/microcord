package com.discord

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraKitDependencyContractTest {
    @Test
    fun flexInputCameraRuntimeIsPackaged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertNotNull(Class.forName("com.camerakit.CameraKitView\$ErrorListener", false, context.classLoader))
        assertNotEquals(0, context.resources.getIdentifier("camera_facing", "attr", context.packageName))
    }
}
