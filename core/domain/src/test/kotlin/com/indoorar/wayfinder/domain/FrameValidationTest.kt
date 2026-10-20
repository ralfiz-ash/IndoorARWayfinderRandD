package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.FixRejection
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationPolicy
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LocalizationTracker
import com.indoorar.wayfinder.domain.localization.MarkerObservation
import com.indoorar.wayfinder.domain.localization.MarkerTrackingQuality
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingInput
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Coordinate-frame validation with the default (production) policy. */
class FrameValidationTest {
    private val spec = MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL)
    private val policy = LocalizationPolicy()
    private val tracker = LocalizationTracker(spec, policy)
    /** Wall marker at 1.5 m height, facing +Z. */
    private val markerPose = RigidTransform(Vec3(0.0, 1.5, 0.0), Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2))
    private var t = 0L

    private fun frame(
        camera: Vec3 = Vec3(0.0, 1.5, 1.5),
        pose: RigidTransform = markerPose,
        quality: MarkerTrackingQuality? = MarkerTrackingQuality.FULL,
    ): LocalizationStatus {
        t += 33_000_000
        return tracker.update(TrackingInput(t, CameraTracking.Tracking, camera, quality?.let { MarkerObservation(spec.imageName, pose, it) }))
    }

    private fun establish(): LocalizationStatus.Localized {
        repeat(policy.minStableFixFrames - 1) { frame() }
        return frame() as LocalizationStatus.Localized
    }

    @Test
    fun aSingleDetectionIsNotEnoughToEstablishTheFrame() {
        repeat(policy.minStableFixFrames - 1) {
            assertEquals(LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER), frame())
            assertEquals(FixRejection.STABILIZING, tracker.diagnostics.fixRejection)
        }
        assertEquals(policy.minStableFixFrames - 1, tracker.diagnostics.stableFrames)
        val s = frame() as LocalizationStatus.Localized
        assertEquals(LocalizationConfidence.HIGH, s.confidence)
        assertEquals(0.0, tracker.diagnostics.fixSpreadMeters!!, 1e-9)
    }

    @Test
    fun jitteryDetectionsAreRejectedAsUnstable() {
        repeat(30) { i ->
            val offset = if (i % 2 == 0) 0.05 else -0.05
            val s = frame(pose = RigidTransform(markerPose.translation + Vec3(offset, 0.0, 0.0), markerPose.rotation))
            assertTrue(s is LocalizationStatus.NotLocalized)
        }
        assertEquals(FixRejection.UNSTABLE, tracker.diagnostics.fixRejection)
    }

    @Test
    fun obliqueAndDistantViewsAreRejected() {
        // 70° off the marker normal.
        val oblique = Vec3(1.5 * kotlin.math.sin(Math.toRadians(70.0)), 1.5, 1.5 * kotlin.math.cos(Math.toRadians(70.0)))
        repeat(20) { assertTrue(frame(camera = oblique) is LocalizationStatus.NotLocalized) }
        assertEquals(FixRejection.TOO_OBLIQUE, tracker.diagnostics.fixRejection)
        assertEquals(70.0, tracker.diagnostics.viewingAngleDegrees!!, 0.01)
        repeat(20) { assertTrue(frame(camera = Vec3(0.0, 1.5, 3.5)) is LocalizationStatus.NotLocalized) }
        assertEquals(FixRejection.TOO_FAR, tracker.diagnostics.fixRejection)
        // 45° is acceptable.
        val ok = Vec3(1.5 * kotlin.math.sin(Math.toRadians(45.0)), 1.5, 1.5 * kotlin.math.cos(Math.toRadians(45.0)))
        repeat(policy.minStableFixFrames) { frame(camera = ok) }
        assertTrue(tracker.status is LocalizationStatus.Localized)
    }

    @Test
    fun headingUncertaintyGrowsErrorWithDistanceFromMarker() {
        val fixed = establish()
        val near = frame(camera = Vec3(0.0, 1.5, 1.5), quality = MarkerTrackingQuality.LAST_KNOWN) as LocalizationStatus.Localized
        // Teleport-free walk away in 0.5 m steps to 15 m from the marker.
        var far: LocalizationStatus = near
        var z = 1.5
        while (z < 15.0) { z += 0.5; far = frame(camera = Vec3(0.0, 1.5, z), quality = MarkerTrackingQuality.LAST_KNOWN) }
        far as LocalizationStatus.Localized
        val d = tracker.diagnostics
        assertEquals(policy.baseYawSigmaRadians * 15.0, d.errorHeadingLever, 1e-6) // ≈ 0.26 m at 15 m for 1°
        assertEquals(13.5 * policy.driftPerMeterWalked, d.errorDrift, 1e-6)
        assertEquals(d.errorFix + d.errorHeadingLever + d.errorDrift + d.errorPenalties, far.estimatedErrorMeters, 1e-9)
        assertTrue(far.estimatedErrorMeters > fixed.estimatedErrorMeters + 0.4)
        assertFalse(far.isReliable && far.confidence == LocalizationConfidence.HIGH)
    }

    @Test
    fun aSingleOutlierDetectionCannotMoveTheFrame() {
        val before = establish().alignment
        val outlier = RigidTransform(markerPose.translation + Vec3(1.0, 0.0, 0.0), markerPose.rotation)
        val s = frame(pose = outlier) as LocalizationStatus.Localized
        assertEquals(before.originInSession.x, s.alignment.originInSession.x, 1e-9)
        // The disagreement is surfaced and makes the position untrustworthy until resolved.
        assertEquals(1.0, tracker.diagnostics.pendingDisagreementMeters!!, 1e-6)
        assertFalse(s.isReliable)
        // Agreeing views resolve it again without a correction.
        val back = frame() as LocalizationStatus.Localized
        assertNull(tracker.diagnostics.pendingDisagreementMeters)
        assertTrue(back.isReliable)
        assertEquals(0, tracker.diagnostics.frameCorrections)
    }

    @Test
    fun aConsistentlyDifferentMarkerViewIsAcceptedAsAMeasuredCorrection() {
        establish()
        // Simulate drift: the session now sees the marker 0.4 m away from where the frame was.
        val drifted = RigidTransform(markerPose.translation + Vec3(0.4, 0.0, 0.0), markerPose.rotation)
        repeat(policy.minStableFixFrames) { frame(pose = drifted) }
        val s = tracker.status as LocalizationStatus.Localized
        assertEquals(0.4, s.alignment.originInSession.x, 1e-9)
        assertEquals(1, tracker.diagnostics.frameCorrections)
        assertEquals(0.4, tracker.diagnostics.lastCorrectionMeters!!, 1e-9)
        assertNull(tracker.diagnostics.pendingDisagreementMeters)
    }

    @Test
    fun mapCorrectionOfLastKnownPoseAddsPenalty() {
        establish()
        frame(quality = MarkerTrackingQuality.LAST_KNOWN)
        val moved = RigidTransform(markerPose.translation + Vec3(0.5, 0.0, 0.0), markerPose.rotation)
        frame(pose = moved, quality = MarkerTrackingQuality.LAST_KNOWN)
        assertEquals(1, tracker.diagnostics.mapCorrections)
        assertEquals(policy.interruptionPenaltyMeters, tracker.diagnostics.errorPenalties, 1e-9)
    }

    @Test
    fun lastKnownPoseNeverEstablishesAFrame() {
        repeat(30) { assertTrue(frame(quality = MarkerTrackingQuality.LAST_KNOWN) is LocalizationStatus.NotLocalized) }
    }
}
