package com.discord.app

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppPermissionsTest {
    @Test
    fun mediaPermissionsFollowAndroidStorageVersions() {
        assertArrayEquals(emptyArray<String>(), AppPermissions.mediaPermissions(21))
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA),
            AppPermissions.mediaPermissions(23),
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA),
            AppPermissions.mediaPermissions(32),
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.CAMERA,
            ),
            AppPermissions.mediaPermissions(33),
        )
        assertArrayEquals(AppPermissions.mediaPermissions(33), AppPermissions.mediaPermissions(36))
        assertFalse(AppPermissions.mediaPermissions(36).contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    @Test
    fun galleryAndCameraCanBeRequestedIndependently() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            AppPermissions.galleryPermissions(33),
        )
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), AppPermissions.cameraPermissions(33))
    }

    @Test
    fun manifestDeclaresOnlyCurrentMediaPermissions() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .associateBy { it.getAttributeNS(ANDROID_NAMESPACE, "name") }

        assertEquals("32", permissions.getValue(Manifest.permission.READ_EXTERNAL_STORAGE)
            .getAttributeNS(ANDROID_NAMESPACE, "maxSdkVersion"))
        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in permissions)
        assertTrue(Manifest.permission.READ_MEDIA_VIDEO in permissions)
        assertTrue(Manifest.permission.CAMERA in permissions)
        assertEquals("remove", permissions.getValue(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .getAttributeNS(TOOLS_NAMESPACE, "node"))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
    }
}
