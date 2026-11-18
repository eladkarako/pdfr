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
                R.string.print_document,
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

            // Share, Print, Info, Open, Save, Menu must stay in this order.
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
            assertFalse(enabled(R.string.print_document))
            assertFalse(enabled(R.string.document_info))
            assertFalse(enabled(R.string.save_as))
            // Open and the menu stay usable with no document.
            assertTrue(enabled(R.string.open_another))
            assertTrue(enabled(R.string.more_options))
        }
    }

    @Test
    fun tabReachesTheOverflowMenuAndOpensIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var root = waitForAppWindow()
            val menuLabel = context.getString(R.string.more_options)

            fun focusedMenu(): Boolean =
                findNode(root) { it.isFocused }?.contentDescription?.toString() == menuLabel

            var reached = focusedMenu()
            for (attempt in 1..8) {
                if (reached) break
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                root = waitForAppWindow()
                reached = focusedMenu()
            }
            assertTrue("Tab never reached '$menuLabel'", reached)

            // KEYCODE_DPAD_CENTER is the select button on a TV remote. Compose
            // activates a focused button on the key-up, so send the pair with a
            // short gap; a single sendKeyDownUpSync can be swallowed while the
            // popup window is still gaining focus.
            val exitLabel = context.getString(R.string.exit)
            var opened: AccessibilityNodeInfo? = null
            repeat(5) {
                if (opened == null) {
                    instrumentation.sendKeySync(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    Thread.sleep(80)
                    instrumentation.sendKeySync(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    opened = waitForNode(attempts = 30) { it.text?.toString() == exitLabel }
                }
            }

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
                R.string.print_document,
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
