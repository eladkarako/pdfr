package com.karako.pdfr.ui

import kotlin.math.roundToInt

/**
 * Pure zoom arithmetic, kept out of the composables so it can be unit-tested
 * on the JVM. The reader combines a live pinch zoom with the zoom that the
 * current reflow layout and bitmap were produced at; these helpers make those
 * two values agree without snapping the content to a new size.
 */
internal object ZoomMath {

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 6f

    /** Continuous zoom is clamped to the supported range, never snapped. */
    fun clamp(value: Float): Float = value.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * Zoom implied by a pinch that moved the finger distance from
     * [startDistance] to [distance]. Invalid values leave the zoom alone.
     */
    fun pinchZoom(
        startZoom: Float,
        startDistance: Float,
        distance: Float,
        minZoom: Float = MIN_ZOOM,
        maxZoom: Float = MAX_ZOOM,
    ): Float {
        val fallback = startZoom.coerceIn(minZoom, maxZoom)
        if (!startDistance.isFinite() || startDistance <= 0f) return fallback
        if (!distance.isFinite() || distance <= 0f) return fallback
        val zoom = startZoom * distance / startDistance
        return if (zoom.isFinite()) zoom.coerceIn(minZoom, maxZoom) else fallback
    }

    /** The next stop of the double-tap glide: 100 -> 200 -> 400 -> 600 -> 100. */
    fun nextDoubleTapZoom(current: Float): Float = when {
        current < 2f -> 2f
        current < 4f -> 4f
        current < MAX_ZOOM -> MAX_ZOOM
        else -> MIN_ZOOM
    }

    /**
     * Scale for a reflowable page bitmap that was laid out at
     * [renderedLayoutZoom] but is displayed at [liveZoom]. A page that is
     * already at the live zoom is not magnified twice.
     */
    fun reflowPageScale(liveZoom: Float, renderedLayoutZoom: Float): Float {
        val rendered = renderedLayoutZoom.takeIf { it.isFinite() && it > 0f } ?: MIN_ZOOM
        val live = liveZoom.takeIf { it.isFinite() && it > 0f } ?: rendered
        return (live / rendered).coerceAtLeast(1f)
    }

    /**
     * Width multiplier for the scroll rack while older reflow pages are still
     * being replaced. It uses the smallest zoom any visible page was laid out
     * at, so those pages keep their width until their replacement lands.
     */
    fun reflowRackScale(liveZoom: Float, layoutZoom: Float, widthFloor: Float): Float {
        val floor = minOf(layoutZoom, widthFloor).takeIf { it.isFinite() && it > 0f }
            ?: MIN_ZOOM
        val live = liveZoom.takeIf { it.isFinite() && it > 0f } ?: floor
        return (live / floor).coerceAtLeast(1f)
    }

    /**
     * Horizontal scroll that keeps the content point under the pinch centroid
     * fixed. [startScroll] and [startFocusX] describe the gesture start;
     * [focusX] follows the centroid so moving both fingers pans.
     */
    fun anchoredScroll(
        startScroll: Int,
        startFocusX: Float,
        focusX: Float,
        ratio: Float,
    ): Int {
        val fallback = startScroll.coerceAtLeast(0)
        if (!ratio.isFinite() || !startFocusX.isFinite() || !focusX.isFinite()) return fallback
        val target = (startScroll + startFocusX) * ratio - focusX
        return if (target.isFinite()) target.roundToInt().coerceAtLeast(0) else fallback
    }

    /**
     * Vertical list delta that puts [fraction] of the page back under the
     * moving pinch centroid after the page height changed.
     */
    fun anchorDelta(
        itemOffset: Int,
        itemSize: Int,
        fraction: Float,
        focusY: Float,
    ): Float {
        if (!focusY.isFinite() || !fraction.isFinite()) return 0f
        val wanted = focusY - fraction * itemSize
        val delta = itemOffset - wanted
        return if (delta.isFinite()) delta else 0f
    }
}
