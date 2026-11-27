package com.karako.pdfr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same toolbar through every input a phone or a TV offers.
 *
 * The D-pad and keyboard cases are what a TV remote sends: focus has to move
 * between the enabled keys and the centre button has to activate one. The
 * touch and mouse cases cover a phone and a mouse: a tap clicks, hovering only
 * paints the focus rectangle, and a mouse button clicks. The checks read the
 * real accessibility tree and real screenshots, so they fail if the visual cue
 * or the announced label disappears.
 *
 * Every case runs on a phone AVD and on the TV AVD: nothing here depends on
 * the device claiming a leanback feature, only on the input events the system
 * delivers.
 */
@RunWith(AndroidJUnit4::class)
class InputInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val openLabel get() = context.getString(R.string.open_another)
    private val menuLabel get() = context.getString(R.string.more_options)
    private val exitLabel get() = context.getString(R.string.exit)
    private val emptyTitle get() = context.getString(R.string.empty_title)

    @After
    fun tearDown() {
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
    }

    @Test
    fun toolbarActionsAreAnnouncedAsNamedButtons() {
        withReader { _ ->
            val labels = listOf(
                context.getString(R.string.share_document),
                context.getString(R.string.document_info),
                openLabel,
                context.getString(R.string.save_as),
                menuLabel,
            )
            labels.forEach { label ->
                val node = findNode(waitForAppWindow()) {
                    it.contentDescription?.toString() == label
                }
                assertNotNull("'$label' is missing from the accessibility tree", node)
                assertEquals(
                    "'$label' must be announced as a button",
                    "android.widget.Button",
                    node!!.className?.toString(),
                )
                assertTrue("'$label' must stay clickable", node.isClickable)
            }
        }
    }

    @Test
    fun disabledDocumentActionsAreStillAnnouncedButNotClickable() {
        withReader { _ ->
            listOf(
                context.getString(R.string.share_document),
                context.getString(R.string.document_info),
                context.getString(R.string.save_as),
            ).forEach { label ->
                val node = findNode(waitForAppWindow()) {
                    it.contentDescription?.toString() == label
                }
                assertNotNull("'$label' disappeared with no document", node)
                assertFalse("'$label' must be disabled with no document", node!!.isEnabled)
                assertTrue(
                    "'$label' must stay important for accessibility",
                    node.isImportantForAccessibility,
                )
            }

            listOf(openLabel, menuLabel).forEach { label ->
                val node = findNode(waitForAppWindow()) {
                    it.contentDescription?.toString() == label
                }
                assertNotNull(label, node)
                assertTrue("'$label' must stay enabled with no document", node!!.isEnabled)
                assertTrue("'$label' must be focusable", node.isFocusable)
            }
        }
    }

    @Test
    fun remoteDpadSkipsDisabledKeysAndReachesTheMenu() {
        withReader { scenario ->
            focusOpen(scenario)
            assertEquals(openLabel, focusedLabel())

            // Save as sits between Open and Menu but is disabled with no
            // document, so one Right must land on Menu, not on a dead key.
            sendDpad(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(
                "Right from '$openLabel' did not reach '$menuLabel'",
                waitForFocus(menuLabel),
            )
        }
    }

    @Test
    fun remoteCenterOpensTheMenu() {
        withReader { scenario ->
            focusOpen(scenario)
            sendDpad(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(waitForFocus(menuLabel))

            // KEYCODE_DPAD_CENTER is the select button on a TV remote.
            sendKeyPair(scenario, KeyEvent.KEYCODE_DPAD_CENTER)
            assertNotNull(
                "the centre button did not open the overflow menu",
                waitForNode { it.text?.toString() == exitLabel },
            )
        }
    }

    @Test
    fun keyboardEnterOpensTheMenu() {
        withReader { scenario ->
            focusOpen(scenario)
            sendDpad(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(waitForFocus(menuLabel))

            sendKeyPair(scenario, KeyEvent.KEYCODE_ENTER)
            assertNotNull(
                "Enter did not open the overflow menu",
                waitForNode { it.text?.toString() == exitLabel },
            )
        }
    }

    @Test
    fun keyboardBackClosesTheMenuAndKeepsTheReader() {
        withReader { scenario ->
            focusOpen(scenario)
            sendDpad(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(waitForFocus(menuLabel))
            sendKeyPair(scenario, KeyEvent.KEYCODE_ENTER)
            assertNotNull(waitForNode { it.text?.toString() == exitLabel })

            // The popup owns window focus while it is open, so waiting for the
            // activity to regain it would always time out. BACK must go to the
            // focused popup window; the activity check comes after it closes.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(
                "Back did not close the overflow menu",
                waitForNodeGone { it.text?.toString() == exitLabel },
            )
            assertNotNull(
                "Back closed the reader instead of the menu",
                waitForNode { it.text?.toString() == emptyTitle },
            )
        }
    }

    @Test
    fun touchTapOpensTheMenu() {
        withReader { _ ->
            val menu = findNode(waitForAppWindow()) {
                it.contentDescription?.toString() == menuLabel
            }
            assertNotNull(menu)
            tapNode(menu!!, InputDevice.SOURCE_TOUCHSCREEN)
            assertNotNull(
                "a touch tap did not open the overflow menu",
                waitForNode { it.text?.toString() == exitLabel },
            )
        }
    }

    @Test
    fun mouseClickOpensTheMenu() {
        withReader { _ ->
            val menu = findNode(waitForAppWindow()) {
                it.contentDescription?.toString() == menuLabel
            }
            assertNotNull(menu)
            val bounds = boundsOf(menu!!)
            hover(bounds.exactCenterX(), bounds.exactCenterY())
            tapNode(menu, InputDevice.SOURCE_MOUSE)
            assertNotNull(
                "a mouse click did not open the overflow menu",
                waitForNode { it.text?.toString() == exitLabel },
            )
        }
    }

    @Test
    fun mouseHoverPaintsTheFocusRingWithoutClicking() {
        withReader { _ ->
            val menu = findNode(waitForAppWindow()) {
                it.contentDescription?.toString() == menuLabel
            }
            assertNotNull(menu)
            val bounds = boundsOf(menu!!)

            val before = screenshot()
            hover(bounds.exactCenterX(), bounds.exactCenterY())
            Thread.sleep(500)
            val after = screenshot()

            val beforeInk = ringInk(before, bounds)
            val afterInk = ringInk(after, bounds)
            assertTrue(
                "hovering '$menuLabel' did not paint the dotted focus ring " +
                    "(ink $beforeInk -> $afterInk)",
                afterInk > beforeInk + 40,
            )
            assertNull(
                "hover must not click the button",
                waitForNode(attempts = 5) { it.text?.toString() == exitLabel },
            )
        }
    }

    @Test
    fun mouseHoverKeepsTheKeyboardFocusWhereItWas() {
        withReader { scenario ->
            focusOpen(scenario)
            val menu = findNode(waitForAppWindow()) {
                it.contentDescription?.toString() == menuLabel
            }
            assertNotNull(menu)
            val bounds = boundsOf(menu!!)

            hover(bounds.exactCenterX(), bounds.exactCenterY())
            Thread.sleep(300)
            assertEquals(
                "hovering the mouse moved the keyboard focus",
                openLabel,
                focusedLabel(),
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun withReader(body: (ActivityScenario<MainActivity>) -> Unit) {
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForWindowFocus(scenario)
            Thread.sleep(300)
            waitForAppWindow()
            instrumentation.waitForIdleSync()
            body(scenario)
        }
    }

    private fun focusOpen(scenario: ActivityScenario<MainActivity>) {
        val open = waitForNode(attempts = 50) {
            it.contentDescription?.toString() == openLabel && it.isEnabled
        }
        assertNotNull("'$openLabel' never became enabled", open)
        open!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(
            "'$openLabel' did not take keyboard focus",
            waitForFocus(openLabel),
        )
        waitForWindowFocus(scenario)
    }

    private fun sendDpad(scenario: ActivityScenario<MainActivity>, keyCode: Int) {
        waitForWindowFocus(scenario)
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
    }

    private fun sendKeyPair(scenario: ActivityScenario<MainActivity>, keyCode: Int) {
        waitForWindowFocus(scenario)
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        Thread.sleep(80)
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
    }

    private fun waitForFocus(label: String, attempts: Int = 40): Boolean {
        repeat(attempts) {
            if (focusedLabel() == label) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun focusedLabel(): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName != context.packageName) return null
        return findNode(root) { it.isFocused }?.contentDescription?.toString()
    }

    private fun waitForAppWindow(): AccessibilityNodeInfo {
        repeat(50) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName == context.packageName) return root
            Thread.sleep(100)
        }
        error("the PDFr window did not become active")
    }

    private fun waitForNode(
        attempts: Int = 50,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        repeat(attempts) {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let { roots += it }
            runCatching { instrumentation.uiAutomation.windows }
                .getOrNull()
                ?.mapNotNull { it.root }
                ?.let { roots += it }

            roots.forEach { root ->
                findNode(root, predicate)?.let { return it }
            }
            Thread.sleep(100)
        }
        return null
    }

    private fun waitForNodeGone(
        attempts: Int = 40,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        repeat(attempts) {
            if (waitForNode(attempts = 1, predicate = predicate) == null) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val match = findNode(child, predicate) ?: continue
            return match
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private fun tapNode(node: AccessibilityNodeInfo, source: Int) {
        val bounds = boundsOf(node)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val downTime = SystemClock.uptimeMillis()
        sendPointer(source, MotionEvent.ACTION_DOWN, downTime, downTime, x, y, pressed = true)
        Thread.sleep(60)
        sendPointer(
            source,
            MotionEvent.ACTION_UP,
            downTime,
            SystemClock.uptimeMillis(),
            x,
            y,
            pressed = false,
        )
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
    }

    private fun hover(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        sendPointer(
            InputDevice.SOURCE_MOUSE,
            MotionEvent.ACTION_HOVER_ENTER,
            now,
            now,
            x,
            y,
            pressed = false,
        )
        sendPointer(
            InputDevice.SOURCE_MOUSE,
            MotionEvent.ACTION_HOVER_MOVE,
            now,
            SystemClock.uptimeMillis(),
            x,
            y,
            pressed = false,
        )
        instrumentation.waitForIdleSync()
    }

    private fun sendPointer(
        source: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
        pressed: Boolean,
    ) {
        val event = if (source == InputDevice.SOURCE_MOUSE) {
            // The property setters for buttonState/actionButton are not in the
            // public Kotlin stubs, so build the mouse event through the full
            // obtain() overload that takes them explicitly.
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = if (pressed) 1f else 0f
                    size = 1f
                },
            )
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                properties,
                coords,
                0,
                if (pressed) MotionEvent.BUTTON_PRIMARY else 0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0,
            )
        } else {
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                this.source = source
            }
        }
        try {
            instrumentation.uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    private fun screenshot(): Bitmap {
        repeat(10) {
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) {
                // Screenshots can be HARDWARE bitmaps, whose pixels cannot be
                // read; copy once so every check sees a plain ARGB buffer.
                return if (shot.config == Bitmap.Config.HARDWARE) {
                    shot.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    shot
                }
            }
            Thread.sleep(100)
        }
        error("the screen could not be captured")
    }

    /**
     * Counts dark pixels in the narrow band where [classicButton] paints the
     * dotted focus rectangle. The band starts outside the one-pixel bevel and
     * ends before the centred icon, so only the ring can change it.
     */
    private fun ringInk(bitmap: Bitmap, bounds: Rect): Int {
        val density = context.resources.displayMetrics.density
        val low = (7 * density).toInt()
        val high = (12 * density).toInt()
        var count = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val edge = minOf(
                    x - bounds.left,
                    bounds.right - 1 - x,
                    y - bounds.top,
                    bounds.bottom - 1 - y,
                )
                if (edge < low || edge > high) continue
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) + Color.green(color) + Color.blue(color) < 300) {
                    count += 1
                }
            }
        }
        return count
    }

    private fun waitForWindowFocus(scenario: ActivityScenario<MainActivity>) {
        repeat(100) {
            if (hasWindowFocus(scenario)) return
            Thread.sleep(100)
        }
        error("the PDFr activity did not gain window focus")
    }

    private fun hasWindowFocus(scenario: ActivityScenario<MainActivity>): Boolean {
        var focused = false
        scenario.onActivity { focused = it.hasWindowFocus() }
        return focused
    }
}
