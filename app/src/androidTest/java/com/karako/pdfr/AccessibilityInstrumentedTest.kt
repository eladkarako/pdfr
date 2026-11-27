package com.karako.pdfr

import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karako.pdfr.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads the real accessibility tree of the reader.
 *
 * The toolbar is the one place where a keyboard, a TV remote and a screen
 * reader all have to agree, so the tests check its labels, its touch targets,
 * the fixed left-to-right order and the Tab chain that must still reach the
 * overflow menu when the document actions are disabled.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @After
    fun tearDown() {
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
    }

    @Test
    fun toolbarExposesLabelsAndLargeEnoughTargets() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val root = waitForAppWindow()

            val expected = listOf(
                R.string.share_document,
                R.string.document_info,
                R.string.open_another,
                R.string.save_as,
                R.string.more_options,
            )
            val nodes = expected.map { id ->
                val label = context.getString(id)
                val node = findNode(root) { it.contentDescription?.toString() == label }
                assertNotNull(
                    "the toolbar button '$label' is missing from the accessibility tree",
                    node
                )
                node!!
            }

            val density = context.resources.displayMetrics.density
            val minimum = 48 * density
            nodes.forEach { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                assertTrue(
                    "a toolbar target is smaller than 48dp: $bounds",
                    bounds.width() >= minimum && bounds.height() >= minimum,
                )
            }

            // Share, Info, Open, Save, Menu must stay in this order.
            val lefts = nodes.map { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                bounds.left
            }
            assertEquals(lefts.sorted(), lefts)
        }
    }

    @Test
    fun emptyStateDisablesOnlyTheDocumentActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val root = waitForAppWindow()

            fun enabled(id: Int): Boolean {
                val label = context.getString(id)
                val node = findNode(root) { it.contentDescription?.toString() == label }
                assertNotNull(label, node)
                return node!!.isEnabled
            }

            assertFalse(enabled(R.string.share_document))
            assertFalse(enabled(R.string.document_info))
            assertFalse(enabled(R.string.save_as))
            // Open and the menu stay usable with no document.
            assertTrue(enabled(R.string.open_another))
            assertTrue(enabled(R.string.more_options))
        }
    }

    @Test
    fun tabReachesTheOverflowMenuAndOpensIt() {
        // The class has a Hebrew test; make sure this one starts from the
        // default language even if JUnit runs them in a different order.
        AppSettings.setLanguageTag(context, AppSettings.SYSTEM_LANGUAGE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // A previous scenario can still be finishing, so the active window
            // may be its stale one and injected keys would go nowhere. Wait for
            // this activity to own the input focus before traversing.
            waitForWindowFocus(scenario)
            Thread.sleep(300)
            waitForAppWindow()
            instrumentation.waitForIdleSync()
            val menuLabel = context.getString(R.string.more_options)

            // An overflow popup left open by an earlier test is its own window
            // and would receive the Tab keys, so close it before traversing.
            val exitLabel = context.getString(R.string.exit)
            if (waitForNode(attempts = 3) { it.text?.toString() == exitLabel } != null) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                assertTrue(
                    "a leftover overflow menu did not close",
                    waitForNodeGone { it.text?.toString() == exitLabel },
                )
            }

            // Wait until the empty state has settled and the menu reports
            // enabled, so the first Tab cannot land before the toolbar is
            // interactive.
            assertNotNull(
                "the overflow button never became enabled",
                waitForNode(attempts = 50) {
                    it.contentDescription?.toString() == menuLabel && it.isEnabled
                },
            )

            // Read the focus from a fresh snapshot every time. A snapshot taken
            // right after a Tab can still report the node that is about to lose
            // focus, and pressing OK then lands on the wrong button.
            fun focusedLabel(): String? {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
                if (root.packageName != context.packageName) return null
                return findNode(root) { it.isFocused }?.contentDescription?.toString()
            }

            fun focusedInfo(): String {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "no-root"
                if (root.packageName != context.packageName) return "other:${root.packageName}"
                val found = mutableListOf<String>()
                fun walk(node: AccessibilityNodeInfo) {
                    if (node.isFocused) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        found += "${node.className}/${node.contentDescription}/$rect"
                    }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { walk(it) }
                    }
                }
                walk(root)
                return if (found.isEmpty()) "none" else found.joinToString(" | ")
            }

            // Record what Tab visits so a failure says whether traversal moved
            // at all or merely skipped the menu.
            val visited = mutableListOf<String>()

            // The accessibility tree reports the new focus before the window
            // really receives it, and the node can flicker out for a frame.
            // Pressing Enter in that window activates the previous button (the
            // Open document picker), so require the label to stay put for a
            // moment first.
            fun waitForMenuFocus(timeoutMs: Long = 1500): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMs
                var stableSince = 0L
                while (System.currentTimeMillis() < deadline) {
                    if (focusedLabel() == menuLabel) {
                        if (stableSince == 0L) stableSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - stableSince >= 300) return true
                    } else {
                        stableSince = 0L
                    }
                    Thread.sleep(80)
                }
                return false
            }

            var reached = false
            var opened: AccessibilityNodeInfo? = null
            for (attempt in 0..12) {
                // A finishing scenario can steal focus between iterations.
                waitForWindowFocus(scenario)
                val info = focusedInfo()
                visited += "#$attempt $info focus=${hasWindowFocus(scenario)}"
                val label = focusedLabel()
                if (label == menuLabel && waitForMenuFocus()) {
                    reached = true
                    // KEYCODE_DPAD_CENTER is the select button on a TV remote.
                    waitForWindowFocus(scenario)
                    instrumentation.sendKeySync(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    Thread.sleep(80)
                    instrumentation.sendKeySync(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    opened = waitForNode(attempts = 20) { it.text?.toString() == exitLabel }
                    if (opened != null) break
                }
                waitForWindowFocus(scenario)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                instrumentation.waitForIdleSync()
                Thread.sleep(100)
            }
            assertTrue("Tab never reached '$menuLabel'; visited $visited", reached)
            assertNotNull("the overflow menu did not open from the keyboard", opened)
        }
    }

    @Test
    fun hebrewKeepsTheToolbarOrderAndTranslatesTheMenu() {
        AppSettings.setLanguageTag(context, "he")
        val hebrew = AppSettings.wrap(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            val root = waitForAppWindow()

            val expected = listOf(
                R.string.share_document,
                R.string.document_info,
                R.string.open_another,
                R.string.save_as,
                R.string.more_options,
            )
            val lefts = expected.map { id ->
                val label = hebrew.getString(id)
                assertFalse(
                    "Hebrew label for $id fell back to English",
                    label == context.getString(id)
                )
                val node = findNode(root) { it.contentDescription?.toString() == label }
                assertNotNull("'$label' is missing from the Hebrew toolbar", node)
                val bounds = android.graphics.Rect()
                node!!.getBoundsInScreen(bounds)
                bounds.left
            }

            // The toolbar does not mirror: Share stays left of Menu.
            assertEquals(lefts.sorted(), lefts)

            // The menu itself is translated and mirrored.
            val menuLabel = hebrew.getString(R.string.more_options)
            val menu = findNode(root) { it.contentDescription?.toString() == menuLabel }
            assertNotNull(menu)
            assertTrue(menu!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))

            val exitLabel = hebrew.getString(R.string.exit)
            val popup = waitForNode { it.text?.toString() == exitLabel }
            assertNotNull(
                "the Hebrew overflow menu is missing '$exitLabel'",
                popup,
            )

            // The popup is its own window and outlives the scenario for a
            // moment. Close it here so the next test starts from the toolbar.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(
                "the Hebrew overflow menu did not close",
                waitForNodeGone { it.text?.toString() == exitLabel },
            )
        }
    }

    private fun waitForAppWindow(): AccessibilityNodeInfo {
        repeat(50) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName == context.packageName) return root
            Thread.sleep(100)
        }
        error("the PDFr window did not become active")
    }

    /** Waits until [scenario]'s activity, not a finishing one, has input focus. */
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

    /**
     * Looks for [predicate] in the active window and in every other window the
     * app owns, because a menu popup is its own window and may not be the one
     * `rootInActiveWindow` reports while it is still animating in.
     */
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

    /** Waits until [predicate] matches nothing in any window the app owns. */
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
            val match = findNode(child, predicate)
            if (match != null) return match
        }
        return null
    }
}
