package com.discord.utilities.images

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MGImagesChangeDetectorTest {

    private val viewA = Any()
    private val viewB = Any()

    @Test
    fun alwaysUpdateDetectorNeverSuppresses() {
        val detector = MGImages.AlwaysUpdateChangeDetector
        assertTrue(detector.track(viewA, "url"))
        assertTrue(detector.track(viewA, "url"))
        assertTrue(detector.track(viewA, null))
    }

    @Test
    fun distinctDetectorSuppressesRebindsWithTheSameValue() {
        val detector = MGImages.DistinctChangeDetector()

        assertTrue(detector.track(viewA, "url-1"))
        assertFalse(detector.track(viewA, "url-1"))
        assertTrue(detector.track(viewA, "url-2"))
        assertFalse(detector.track(viewA, "url-2"))
        assertTrue(detector.track(viewA, "url-1"))
    }

    @Test
    fun distinctDetectorTracksEachKeyIndependently() {
        val detector = MGImages.DistinctChangeDetector()

        assertTrue(detector.track(viewA, "url"))
        assertTrue(detector.track(viewB, "url"))
        assertFalse(detector.track(viewA, "url"))
        assertFalse(detector.track(viewB, "url"))
    }

    @Test
    fun distinctDetectorHandlesNullValues() {
        val detector = MGImages.DistinctChangeDetector()

        assertTrue(detector.track(viewA, null))
        assertFalse(detector.track(viewA, null))
        assertTrue(detector.track(viewA, "url"))
        assertTrue(detector.track(viewA, null))
    }

    @Test
    fun distinctDetectorComparesUrlListsByValue() {
        val detector = MGImages.DistinctChangeDetector()

        assertTrue(detector.track(viewA, listOf("a", "b")))
        assertFalse(detector.track(viewA, listOf("a", "b")))
        assertTrue(detector.track(viewA, listOf("b", "a")))
        assertTrue(detector.track(viewA, emptyList<String>()))
    }

    @Test
    fun distinctDetectorRebuildsWhenAnimationPolicyChanges() {
        val detector = MGImages.DistinctChangeDetector()
        val urls = listOf("data:image/gif;base64,AAAA")

        assertTrue(detector.track(viewA, urls to false))
        assertFalse(detector.track(viewA, urls to false))
        assertTrue(detector.track(viewA, urls to true))
    }
}
