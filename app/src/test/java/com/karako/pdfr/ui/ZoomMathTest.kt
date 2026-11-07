package com.karako.pdfr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the zoom arithmetic behind the live pinch: continuous values,
 * clamping, anchored scrolling and the reflow/bitmap scale hand-off.
 */
class ZoomMathTest {

    private val eps = 0.0001f

    @Test
    fun zoomStaysContinuousInsideTheRange() {
        // Any intermediate value is kept, not snapped to the next stop.
        assertEquals(1.2345f, ZoomMath.clamp(1.2345f), eps)
        assertEquals(3.25f, ZoomMath.clamp(3.25f), eps)
        assertEquals(5.999f, ZoomMath.clamp(5.999f), eps)
    }

    @Test
    fun zoomClampsAtTheEnds() {
        assertEquals(1f, ZoomMath.clamp(0.5f), eps)
        assertEquals(6f, ZoomMath.clamp(6.5f), eps)
        assertEquals(1f, ZoomMath.clamp(1f), eps)
        assertEquals(6f, ZoomMath.clamp(6f), eps)
    }

    @Test
    fun pinchZoomFollowsFingerDistance() {
        assertEquals(2f, ZoomMath.pinchZoom(1f, 100f, 200f), eps)
        assertEquals(2.055f, ZoomMath.pinchZoom(1.5f, 100f, 137f), eps)
        // A gesture that did not move leaves the zoom untouched.
        assertEquals(1.5f, ZoomMath.pinchZoom(1.5f, 100f, 100f), eps)
    }

    @Test
    fun pinchZoomClampsInsteadOfSnapping() {
        assertEquals(6f, ZoomMath.pinchZoom(4f, 100f, 400f), eps)
        assertEquals(1f, ZoomMath.pinchZoom(1.2f, 200f, 50f), eps)
        assertEquals(4f, ZoomMath.pinchZoom(5f, 100f, 200f, minZoom = 1f, maxZoom = 4f), eps)
    }

    @Test
    fun pinchZoomIgnoresInvalidDistances() {
        assertEquals(1.5f, ZoomMath.pinchZoom(1.5f, 0f, 200f), eps)
        assertEquals(1.5f, ZoomMath.pinchZoom(1.5f, 100f, 0f), eps)
        assertEquals(1.5f, ZoomMath.pinchZoom(1.5f, Float.NaN, 200f), eps)
        assertEquals(1.5f, ZoomMath.pinchZoom(1.5f, 100f, Float.NaN), eps)
    }

    @Test
    fun doubleTapCyclesThroughStops() {
        assertEquals(2f, ZoomMath.nextDoubleTapZoom(1f), eps)
        assertEquals(2f, ZoomMath.nextDoubleTapZoom(1.99f), eps)
        assertEquals(4f, ZoomMath.nextDoubleTapZoom(2f), eps)
        assertEquals(4f, ZoomMath.nextDoubleTapZoom(3.99f), eps)
        assertEquals(6f, ZoomMath.nextDoubleTapZoom(4f), eps)
        assertEquals(6f, ZoomMath.nextDoubleTapZoom(5.99f), eps)
        assertEquals(1f, ZoomMath.nextDoubleTapZoom(6f), eps)
        assertEquals(1f, ZoomMath.nextDoubleTapZoom(7f), eps)
    }

    @Test
    fun reflowPageScaleStretchesOnlyTheOldLayout() {
        // A bitmap from the 100% layout is doubled at 200%.
        assertEquals(2f, ZoomMath.reflowPageScale(2f, 1f), eps)
        // A bitmap that already matches the live zoom is left alone.
        assertEquals(1f, ZoomMath.reflowPageScale(2f, 2f), eps)
        assertEquals(1.5f, ZoomMath.reflowPageScale(1.8f, 1.2f), eps)
        // It never shrinks a page that is already at or above the live zoom.
        assertEquals(1f, ZoomMath.reflowPageScale(1f, 2f), eps)
    }

    @Test
    fun reflowPageScaleGuardsMissingLayout() {
        assertEquals(2f, ZoomMath.reflowPageScale(2f, 0f), eps)
        assertEquals(2f, ZoomMath.reflowPageScale(2f, -1f), eps)
        assertEquals(2f, ZoomMath.reflowPageScale(2f, Float.NaN), eps)
        // A missing live zoom falls back to the rendered one (no change).
        assertEquals(1f, ZoomMath.reflowPageScale(Float.NaN, 2f), eps)
    }

    @Test
    fun reflowRackScaleUsesTheOldestVisibleLayout() {
        // Live 300% while some pages are still at the 100% layout.
        assertEquals(3f, ZoomMath.reflowRackScale(3f, 3f, 1f), eps)
        // Every visible page has already been reflowed at 300%.
        assertEquals(1f, ZoomMath.reflowRackScale(3f, 3f, 3f), eps)
        // The current layout is 200%, but an older 100% page is still shown.
        assertEquals(3f, ZoomMath.reflowRackScale(3f, 2f, 1f), eps)
        assertEquals(1f, ZoomMath.reflowRackScale(1.5f, 1.5f, 1.5f), eps)
    }

    @Test
    fun reflowRackScaleGuardsMissingLayout() {
        assertEquals(3f, ZoomMath.reflowRackScale(3f, 0f, 0f), eps)
        assertEquals(3f, ZoomMath.reflowRackScale(3f, 2f, Float.NaN), eps)
        assertEquals(1f, ZoomMath.reflowRackScale(Float.NaN, 2f, 2f), eps)
    }

    @Test
    fun anchoredScrollKeepsTheTouchedPoint() {
        // Zooming 2x around x=540 starts with no scroll and must stay on it.
        assertEquals(540, ZoomMath.anchoredScroll(0, 540f, 540f, 2f))
        // Zooming out from a scrolled position keeps the same point too.
        assertEquals(130, ZoomMath.anchoredScroll(800, 540f, 540f, 0.5f))
        // While the zoom is unchanged the scroll is unchanged.
        assertEquals(1234, ZoomMath.anchoredScroll(1234, 540f, 540f, 1f))
    }

    @Test
    fun anchoredScrollFollowsAMovingCentroid() {
        // Moving both fingers 150px right while doubling the zoom pans along.
        assertEquals(350, ZoomMath.anchoredScroll(0, 500f, 650f, 2f))
    }

    @Test
    fun anchoredScrollNeverGoesNegative() {
        assertEquals(0, ZoomMath.anchoredScroll(0, 100f, 900f, 1.5f))
        assertEquals(0, ZoomMath.anchoredScroll(0, Float.NaN, 900f, 1.5f))
        assertEquals(0, ZoomMath.anchoredScroll(0, 500f, 500f, Float.NaN))
    }

    @Test
    fun anchorDeltaKeepsThePageFractionUnderTheFingers() {
        // Page top at 1000, 2000px tall, quarter point held at y=1500.
        assertEquals(0f, ZoomMath.anchorDelta(1000, 2000, 0.25f, 1500f), eps)
        // The same page grows to 4000px: scroll so the quarter point stays.
        assertEquals(500f, ZoomMath.anchorDelta(1000, 4000, 0.25f, 1500f), eps)
    }

    @Test
    fun anchorDeltaTracksAMovingCentroid() {
        // Moving the centroid down 200px scrolls the list back by 200px.
        assertEquals(-200f, ZoomMath.anchorDelta(1000, 2000, 0.25f, 1700f), eps)
    }

    @Test
    fun anchorDeltaIgnoresInvalidInput() {
        assertEquals(0f, ZoomMath.anchorDelta(1000, 2000, 0.25f, Float.NaN), eps)
        assertEquals(0f, ZoomMath.anchorDelta(1000, 2000, Float.NaN, 1500f), eps)
    }
}
