package com.karako.pdfr.data

import kotlin.math.roundToInt

/**
 * Length units offered by the page-size dialog.
 *
 * PDF page boxes are always stored in points (1/72 inch). The other units are
 * conveniences for the user: millimetres and inches are the physical sizes
 * people know, `px` is the CSS pixel (96 per inch, as on the web) and `em` is
 * one line of 12 pt text, matching the reader's default text size.
 */
enum class PageSizeUnit(val suffix: String, val pointsPerUnit: Float) {
    MILLIMETERS("mm", 72f / 25.4f),
    CENTIMETERS("cm", 72f / 2.54f),
    INCHES("in", 72f),
    POINTS("pt", 1f),
    PIXELS("px", 72f / 96f),
    EM("em", 12f),
    ;

    fun toPoints(value: Float): Float = value * pointsPerUnit

    fun fromPoints(points: Float): Float = points / pointsPerUnit
}

/**
 * ISO 216 A, B and C paper sizes in millimetres.
 *
 * The C series is the envelope series; it is included because it is the other
 * ISO size most documents are compared against.
 */
enum class IsoPaperSize(val widthMm: Float, val heightMm: Float) {
    A0(841f, 1189f),
    A1(594f, 841f),
    A2(420f, 594f),
    A3(297f, 420f),
    A4(210f, 297f),
    A5(148f, 210f),
    A6(105f, 148f),
    A7(74f, 105f),
    A8(52f, 74f),
    A9(37f, 52f),
    A10(26f, 37f),
    B0(1000f, 1414f),
    B1(707f, 1000f),
    B2(500f, 707f),
    B3(353f, 500f),
    B4(250f, 353f),
    B5(176f, 250f),
    B6(125f, 176f),
    B7(88f, 125f),
    B8(62f, 88f),
    B9(44f, 62f),
    B10(31f, 44f),
    C0(917f, 1297f),
    C1(648f, 917f),
    C2(458f, 648f),
    C3(324f, 458f),
    C4(229f, 324f),
    C5(162f, 229f),
    C6(114f, 162f),
    C7(81f, 114f),
    C8(57f, 81f),
    C9(40f, 57f),
    C10(28f, 40f),
    ;

    val widthPt: Float get() = PageSizeUnit.MILLIMETERS.toPoints(widthMm)

    val heightPt: Float get() = PageSizeUnit.MILLIMETERS.toPoints(heightMm)
}

/**
 * A resolved page-size request: zero-based [pages] and the new page box in
 * points. Kept as a plain class because it carries an [IntArray].
 */
class PageSizeTarget(
    val widthPt: Float,
    val heightPt: Float,
    val pages: IntArray,
)

/** Parsing and formatting helpers for the page-size dialog. */
object PageSize {

    /** PDF viewers reject boxes outside this range, so the dialog does too. */
    const val MIN_POINTS = 1f
    const val MAX_POINTS = 14400f

    /**
     * Parses a positive, finite number. A comma is accepted as the decimal
     * separator because many keyboards produce one.
     */
    fun numberOrNull(text: String): Float? {
        val value = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Converts [text] in [unit] to points. Returns null when the number is
     * missing, not positive, or outside [MIN_POINTS]..[MAX_POINTS].
     */
    fun pointsOrNull(text: String, unit: PageSizeUnit): Float? {
        val value = numberOrNull(text) ?: return null
        val points = unit.toPoints(value)
        return points.takeIf { it >= MIN_POINTS && it <= MAX_POINTS }
    }

    /** Formats [value] with at most two decimals and no trailing zeros. */
    fun format(value: Float): String {
        val rounded = (value * 100f).roundToInt() / 100f
        val whole = rounded.toLong()
        return if (rounded == whole.toFloat()) whole.toString() else rounded.toString()
    }

    /** Formats a size in points for the dialog summary. */
    fun describe(widthPt: Float, heightPt: Float): String =
        "${format(widthPt)} x ${format(heightPt)} pt"
}
