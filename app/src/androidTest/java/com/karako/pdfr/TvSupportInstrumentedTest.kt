package com.karako.pdfr

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the manifest pieces Android TV and right-to-left languages rely on.
 *
 * These run on a phone as well as on a TV: they only read what the package
 * manager publishes, so the TV AVD created by `tools\create-tv-avd.bat` can
 * run the same suite without a special build variant.
 */
@RunWith(AndroidJUnit4::class)
class TvSupportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: PackageManager = context.packageManager

    @Test
    fun tvLauncherEntryIsDeclared() {
        val intent = manager.getLeanbackLaunchIntentForPackage(context.packageName)

        assertNotNull("the app must declare a LEANBACK_LAUNCHER activity", intent)
        assertEquals(Intent.ACTION_MAIN, intent!!.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_LEANBACK_LAUNCHER) == true)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun phoneLauncherEntryIsStillDeclared() {
        // The same activity must stay reachable from a phone's home screen.
        val intent = manager.getLaunchIntentForPackage(context.packageName)

        assertNotNull(intent)
        assertEquals(Intent.ACTION_MAIN, intent!!.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
    }

    @Test
    fun bannerMatchesTheTvGuideline() {
        val info = manager.getApplicationInfo(context.packageName, 0)
        assertTrue("android:banner is missing", info.banner != 0)

        val banner = manager.getApplicationBanner(info)
        assertNotNull("android:banner does not resolve to a drawable", banner)
        val bitmap = banner!!.toBitmap()

        // TV launchers expect a 16:9 banner; 320x180 is the documented size.
        assertEquals(320, bitmap.width)
        assertEquals(180, bitmap.height)
    }

    @Test
    fun theApplicationSupportsRtlLayouts() {
        val info = manager.getApplicationInfo(context.packageName, 0)

        assertTrue(
            "android:supportsRtl must stay true for the Hebrew UI",
            info.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0,
        )
    }
}
