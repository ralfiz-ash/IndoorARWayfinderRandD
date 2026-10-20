package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LocalizationTracker
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.MarkerObservation
import com.indoorar.wayfinder.domain.localization.MarkerTrackingQuality
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingInput
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class LocalizationTrackerTest {
    private val spec = MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL)
    private val markerPose = RigidTransform(Vec3(0.0, 1.5, -2.0), Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2))
    private val tracker = LocalizationTracker(spec)
    private var t = 0L

    private fun frame(
        camera: CameraTracking = CameraTracking.Tracking,
        cameraPos: Vec3? = Vec3(0.0, 1.5, 0.0),
        quality: MarkerTrackingQuality? = MarkerTrackingQuality.FULL,
        name: String = spec.imageName,
        pose: RigidTransform = markerPose,
        dtNanos: Long = 33_000_000L,
    ): LocalizationStatus {
        t += dtNanos
        val marker = quality?.let { MarkerObservation(name, pose, it) }
        return tracker.update(TrackingInput(t, camera, cameraPos.takeIf { camera == CameraTracking.Tracking }, marker))
    }

    @Test
    fun startsNotLocalizedUntilMarkerSeen() {
        assertEquals(LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER), frame(quality = null))
        assertEquals(LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER), frame(name = "other"))
    }

    @Test
    fun lastKnownPoseAloneNeverEstablishesTheFrame() {
        assertTrue(frame(quality = MarkerTrackingQuality.LAST_KNOWN) is LocalizationStatus.NotLocalized)
    }

    @Test
    fun fullMarkerFixLocalizesWithHighConfidence() {
        val s = frame() as LocalizationStatus.Localized
        assertEquals(LocalizationConfidence.HIGH, s.confidence)
        assertTrue(s.isReliable)
        assertTrue(s.markerInView)
        assertEquals(0.05 + 0.02 * 2.0, s.estimatedErrorMeters, 1e-9)
    }

    @Test
    fun markerTooFarAwayIsNotAFix() {
        assertTrue(frame(cameraPos = Vec3(0.0, 1.5, 6.0)) is LocalizationStatus.NotLocalized)
    }

    @Test
    fun wrongMountingIsReported() {
        assertEquals(
            LocalizationStatus.NotLocalized(NotLocalizedReason.MARKER_ORIENTATION_MISMATCH),
            frame(pose = RigidTransform(Vec3(0.0, 0.0, -1.0), Quaternion.IDENTITY)),
        )
    }

    @Test
    fun errorGrowsWithDistanceWalkedAndConfidenceDegrades() {
        frame()
        var status: LocalizationStatus = frame(quality = MarkerTrackingQuality.LAST_KNOWN)
        // Walk 20 m in 0.5 m steps with the marker out of view: +0.4 m drift.
        for (i in 1..40) status = frame(quality = MarkerTrackingQuality.LAST_KNOWN, cameraPos = Vec3(0.0, 1.5, i * 0.5))
        val localized = status as LocalizationStatus.Localized
        assertEquals(0.09 + 0.4, localized.estimatedErrorMeters, 1e-6)
        assertEquals(LocalizationConfidence.MEDIUM, localized.confidence)
        assertFalse(localized.markerInView)
    }

    @Test
    fun excessiveDriftRequiresRescan() {
        frame()
        var status: LocalizationStatus = frame()
        for (i in 1..200) {
            status = frame(quality = MarkerTrackingQuality.LAST_KNOWN, cameraPos = Vec3(0.0, 1.5, i * 0.5))
            if (status is LocalizationStatus.Lost) break
        }
        assertEquals(LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE), status)
        // Stays lost (rather than silently "not localized") until a fresh fix.
        assertEquals(LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE), frame(quality = MarkerTrackingQuality.LAST_KNOWN))
        assertTrue(frame() is LocalizationStatus.Localized)
    }

    @Test
    fun shortInterruptionRecoversWithPenalty() {
        val before = frame() as LocalizationStatus.Localized
        val interrupted = frame(camera = CameraTracking.Limited(TrackingIssue.EXCESSIVE_MOTION), quality = MarkerTrackingQuality.LAST_KNOWN)
        assertTrue(interrupted is LocalizationStatus.Interrupted)
        assertFalse(interrupted.isReliable)
        val after = frame(quality = MarkerTrackingQuality.LAST_KNOWN) as LocalizationStatus.Localized
        assertEquals(before.estimatedErrorMeters + 0.15, after.estimatedErrorMeters, 1e-9)
    }

    @Test
    fun longInterruptionLosesLocalization() {
        frame()
        frame(camera = CameraTracking.Limited(TrackingIssue.INSUFFICIENT_LIGHT))
        val s = frame(camera = CameraTracking.Limited(TrackingIssue.INSUFFICIENT_LIGHT), dtNanos = 6_000_000_000L)
        assertEquals(LocalizationStatus.Lost(LostReason.INTERRUPTION_TOO_LONG), s)
        // Recovery requires a fresh marker fix.
        assertEquals(LocalizationStatus.Lost(LostReason.INTERRUPTION_TOO_LONG), frame(quality = MarkerTrackingQuality.LAST_KNOWN))
        assertTrue(frame() is LocalizationStatus.Localized)
    }

    @Test
    fun stoppedTrackingAndLostMarkerTrackable() {
        frame()
        assertEquals(LocalizationStatus.Lost(LostReason.TRACKING_STOPPED), frame(camera = CameraTracking.Stopped))
        frame()
        assertEquals(LocalizationStatus.Lost(LostReason.MARKER_TRACKING_LOST), frame(quality = null))
    }

    @Test
    fun relocalizationJumpAddsPenalty() {
        val before = frame() as LocalizationStatus.Localized
        val after = frame(cameraPos = Vec3(3.0, 1.5, 0.0), quality = MarkerTrackingQuality.LAST_KNOWN) as LocalizationStatus.Localized
        assertEquals(before.estimatedErrorMeters + 0.15, after.estimatedErrorMeters, 1e-9)
    }

    @Test
    fun newSessionResetsToNotLocalized() {
        frame()
        tracker.resetForNewSession()
        assertEquals(LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER), tracker.status)
        assertTrue(frame(quality = MarkerTrackingQuality.LAST_KNOWN) is LocalizationStatus.NotLocalized)
    }

    @Test
    fun smootherBlendsSmallJitterAndResetsOnLargeJumps() {
        val first = (frame() as LocalizationStatus.Localized).alignment
        val jittered = RigidTransform(markerPose.translation + Vec3(0.02, 0.0, 0.0), markerPose.rotation)
        val blended = (frame(pose = jittered) as LocalizationStatus.Localized).alignment
        assertTrue(blended.originInSession.x > first.originInSession.x)
        assertTrue(blended.originInSession.x < 0.02)
        val moved = RigidTransform(markerPose.translation + Vec3(1.0, 0.0, 0.0), markerPose.rotation)
        val reset = (frame(pose = moved) as LocalizationStatus.Localized).alignment
        assertEquals(1.0, reset.originInSession.x, 1e-9)
    }
}

class LocalizationTrackerPauseTest {
    private val spec = MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL)
    private val pose = RigidTransform(Vec3(0.0, 1.5, -1.0), Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2))
    private val tracker = LocalizationTracker(spec)

    private fun frame(t: Long, quality: MarkerTrackingQuality = MarkerTrackingQuality.LAST_KNOWN) = tracker.update(
        TrackingInput(t, CameraTracking.Tracking, Vec3(0.0, 1.5, 0.0), MarkerObservation(spec.imageName, pose, quality)),
    )

    @Test
    fun shortAppPauseKeepsAlignmentWithPenalty() {
        val before = frame(1, MarkerTrackingQuality.FULL) as LocalizationStatus.Localized
        tracker.onSessionResumed(pausedForNanos = 2_000_000_000L)
        val after = frame(2) as LocalizationStatus.Localized
        assertEquals(before.estimatedErrorMeters + 0.15, after.estimatedErrorMeters, 1e-9)
    }

    @Test
    fun longAppPauseRequiresRescan() {
        frame(1, MarkerTrackingQuality.FULL)
        tracker.onSessionResumed(pausedForNanos = 60_000_000_000L)
        assertEquals(LocalizationStatus.Lost(LostReason.INTERRUPTION_TOO_LONG), tracker.status)
        assertEquals(LocalizationStatus.Lost(LostReason.INTERRUPTION_TOO_LONG), frame(2))
        assertTrue(frame(3, MarkerTrackingQuality.FULL) is LocalizationStatus.Localized)
    }
}
