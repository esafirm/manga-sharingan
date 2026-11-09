package com.esafirm.sharingan.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    private val screen = Size(1000f, 2000f)

    private fun ZoomState.assertFinite() {
        assertFalse("scale=$scale", scale.isNaN() || scale.isInfinite())
        assertFalse("offset=$offset", offset.x.isNaN() || offset.y.isNaN() || offset.x.isInfinite() || offset.y.isInfinite())
    }

    /** Regression: lifting the finger after panning while zoomed turned the page black forever. */
    @Test fun liftedFingerHasNoCentroidAndMustNotCorruptState() {
        val z = ZoomState()
        z.transform(Offset(800f, 400f), Offset.Zero, 2f, screen, lockY = false) // pinch in
        val before = z.offset
        // The "finger up" event: Compose reports Offset.Unspecified (NaN) for the centroid.
        z.transform(Offset.Unspecified, Offset.Zero, 1f, screen, lockY = false)
        z.assertFinite()
        assertEquals(before, z.offset)
        assertEquals(2f, z.scale, 0.0001f)
    }

    @Test fun nonFiniteInputIsIgnoredOrReset() {
        val z = ZoomState()
        z.transform(Offset(500f, 1000f), Offset.Zero, 2f, screen, lockY = false)
        z.transform(Offset(500f, 1000f), Offset(Float.NaN, 5f), 1f, screen, lockY = false)
        z.transform(Offset(500f, 1000f), Offset.Zero, Float.NaN, screen, lockY = false)
        z.assertFinite()
    }

    @Test fun pinchKeepsTheTouchedPointUnderTheFinger() {
        val z = ZoomState()
        val finger = Offset(800f, 400f)
        z.transform(finger, Offset.Zero, 2f, screen, lockY = false)
        // Content point under the finger before: itself. After: center + s*(c - center) + t must equal finger.
        val center = Offset(500f, 1000f)
        val screenPos = center + (finger - center) * z.scale + z.offset
        assertEquals(finger.x, screenPos.x, 0.01f)
        assertEquals(finger.y, screenPos.y, 0.01f)
    }

    @Test fun panIsClampedSoTheImageNeverLeavesTheScreen() {
        val z = ZoomState()
        z.transform(Offset(500f, 1000f), Offset.Zero, 2f, screen, lockY = false)
        z.transform(Offset(500f, 1000f), Offset(99999f, -99999f), 1f, screen, lockY = false)
        assertEquals(500f, z.offset.x, 0.01f) // size.width * (2 - 1) / 2
        assertEquals(-1000f, z.offset.y, 0.01f)
    }

    @Test fun zoomingBackOutResetsTheOffset() {
        val z = ZoomState()
        z.transform(Offset(800f, 400f), Offset.Zero, 3f, screen, lockY = false)
        z.transform(Offset(500f, 1000f), Offset.Zero, 0.1f, screen, lockY = false)
        assertEquals(1f, z.scale, 0.0001f)
        assertEquals(Offset.Zero, z.offset)
    }

    @Test fun lockYIgnoresVerticalPan() {
        val z = ZoomState()
        z.transform(Offset(500f, 1000f), Offset.Zero, 2f, screen, lockY = false)
        val y = z.offset.y
        z.transform(Offset(500f, 1000f), Offset(40f, 300f), 1f, screen, lockY = true)
        assertEquals(y, z.offset.y, 0.0001f)
        assertEquals(40f, z.offset.x, 0.01f)
    }

    @Test fun scaleIsCappedAtMax() {
        val z = ZoomState(maxScale = 4f)
        repeat(10) { z.transform(Offset(500f, 1000f), Offset.Zero, 2f, screen, lockY = false) }
        assertTrue(z.scale <= 4f)
        z.assertFinite()
    }
}
