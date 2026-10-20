package com.indoorar.wayfinder.arcore

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a physical ARCore device. Verifies that ARCore accepts the bundled marker image
 * (it throws ImageInsufficientQualityException for images with too few features).
 */
@RunWith(AndroidJUnit4::class)
class MarkerDatabaseDeviceTest {
    // ARCore refuses to create a Session without the camera permission.
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun arCoreAcceptsBundledMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // checkAvailability is asynchronous: the first calls may return UNKNOWN_CHECKING.
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        val deadline = System.currentTimeMillis() + 10_000
        while (availability.isTransient && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            availability = ArCoreApk.getInstance().checkAvailability(context)
        }
        assumeTrue("ARCore unsupported on this device", availability != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE)
        assertEquals(ArCoreApk.Availability.SUPPORTED_INSTALLED, availability)
        val session = Session(context)
        try {
            val db = MarkerDatabase.create(context, session, MarkerSpec("venue_marker", 0.20, MarkerMounting.WALL))
            assertEquals(1, db.numImages)
        } finally {
            session.close()
        }
    }
}
