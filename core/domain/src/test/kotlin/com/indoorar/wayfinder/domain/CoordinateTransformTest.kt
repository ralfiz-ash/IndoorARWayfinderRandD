package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.wrapAngle
import com.indoorar.wayfinder.domain.venue.AlignmentResult
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.domain.venue.VenueFrameEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CoordinateTransformTest {

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double = 1e-9) =
        assertTrue("expected $expected but was $actual", expected.isApproximately(actual, tol))

    @Test
    fun quaternionYawRotatesZTowardsX() {
        assertVec(Vec3.UNIT_X, Quaternion.fromYaw(PI / 2).rotate(Vec3.UNIT_Z))
    }

    @Test
    fun rigidTransformInverseRoundTrips() {
        val t = RigidTransform(Vec3(1.0, 2.0, -3.0), Quaternion.fromAxisAngle(Vec3(1.0, 1.0, 0.5), 0.8))
        val p = Vec3(0.3, -4.0, 9.0)
        assertVec(p, t.inverse().apply(t.apply(p)))
        assertVec(p, (t.inverse() compose t).apply(p))
    }

    @Test
    fun alignmentRoundTripsBetweenSessionAndVenue() {
        val alignment = VenueAlignment(Vec3(2.0, 1.4, -5.0), 1.1)
        val venuePoint = Vec3(3.0, -1.4, 7.5)
        assertVec(venuePoint, alignment.toVenue(alignment.toSession(venuePoint)))
        assertVec(alignment.originInSession, alignment.toSession(Vec3.ZERO))
    }

    @Test
    fun sameVenuePointMapsToSamePhysicalLocationAcrossSessions() {
        // Two sessions with different arbitrary origins observe the same physical marker.
        val session1 = VenueAlignment(Vec3(1.0, 0.0, 2.0), 0.3)
        val session2 = VenueAlignment(Vec3(-4.0, 0.5, 0.0), -2.0)
        val physicalInSession1 = Vec3(5.0, -1.0, 4.0)
        val venuePoint = session1.toVenue(physicalInSession1)
        // Physical displacement from the marker, measured in a marker-aligned frame, is identical.
        val restoredInSession2 = session2.toSession(venuePoint)
        assertEquals(
            physicalInSession1.distanceTo(session1.originInSession),
            restoredInSession2.distanceTo(session2.originInSession),
            1e-9,
        )
        assertVec(venuePoint, session2.toVenue(restoredInSession2))
    }

    @Test
    fun wallMarkerFacingSessionPlusXGivesVenueZAlongPlusX() {
        // Marker image normal (+Y local) rotated to point along session +X.
        val pose = RigidTransform(Vec3(0.0, 1.5, 0.0), Quaternion.fromAxisAngle(Vec3.UNIT_Z, -PI / 2))
        val result = VenueFrameEstimator.estimate(pose, MarkerMounting.WALL) as AlignmentResult.Valid
        assertEquals(PI / 2, result.alignment.yawRadians, 1e-9)
        // One metre in front of the marker (venue +Z) is one metre along session +X.
        assertVec(Vec3(1.0, 1.5, 0.0), result.alignment.toSession(Vec3(0.0, 0.0, 1.0)))
    }

    @Test
    fun wallMarkerPitchNoiseDoesNotTiltVenueFrame() {
        val facingZ = Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2) // normal -> +Z
        val noisy = Quaternion.fromAxisAngle(Vec3.UNIT_X, 0.1) * facingZ
        val result = VenueFrameEstimator.estimate(RigidTransform(Vec3.ZERO, noisy), MarkerMounting.WALL)
        val alignment = (result as AlignmentResult.Valid).alignment
        assertEquals(0.0, alignment.yawRadians, 1e-9)
        assertEquals(0.0, alignment.toSession(Vec3(0.0, 0.0, 2.0)).y, 1e-9)
    }

    @Test
    fun floorMarkerUsesImageTopEdgeAsForward() {
        // Identity: image normal = +Y (lying flat), image top edge (−Z local) points to session −Z.
        val result = VenueFrameEstimator.estimate(RigidTransform.IDENTITY, MarkerMounting.FLOOR)
        val alignment = (result as AlignmentResult.Valid).alignment
        assertVec(Vec3(0.0, 0.0, -1.0), alignment.toSession(Vec3.UNIT_Z))
    }

    @Test
    fun mountingMismatchIsReported() {
        assertTrue(
            VenueFrameEstimator.estimate(RigidTransform.IDENTITY, MarkerMounting.WALL)
                is AlignmentResult.OrientationMismatch,
        )
        val wall = RigidTransform(Vec3.ZERO, Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2))
        assertTrue(VenueFrameEstimator.estimate(wall, MarkerMounting.FLOOR) is AlignmentResult.OrientationMismatch)
    }

    @Test
    fun yawConversionBetweenFrames() {
        val alignment = VenueAlignment(Vec3.ZERO, 0.5)
        assertEquals(0.25, alignment.yawToSession(alignment.yawToVenue(0.25)), 1e-12)
    }

    @Test
    fun angleWrapping() {
        assertEquals(-PI / 2, wrapAngle(3 * PI / 2), 1e-12)
        assertEquals(PI, wrapAngle(-PI), 1e-12)
        assertEquals(0.2, angleDifference(PI - 0.1, -PI + 0.1), 1e-12)
    }
}

class CoordinateTransformExtraTest {
    @Test
    fun composeAppliesRightOperandFirst() {
        val a = RigidTransform(Vec3(1.0, 0.0, 0.0), Quaternion.fromYaw(PI / 2))
        val b = RigidTransform(Vec3(0.0, 0.0, 2.0), Quaternion.IDENTITY)
        val p = Vec3(0.0, 1.0, 0.0)
        assertTrue((a compose b).apply(p).isApproximately(a.apply(b.apply(p)), 1e-12))
    }

    @Test
    fun venueYawRoundTripsThroughQuaternion() {
        for (yaw in listOf(-3.0, -1.0, 0.0, 0.5, 2.9)) {
            val a = VenueAlignment(Vec3(0.3, 0.1, -0.2), yaw)
            val forward = a.sessionFromVenue.applyToDirection(Vec3.UNIT_Z)
            assertEquals(yaw, com.indoorar.wayfinder.domain.geometry.yawOf(forward), 1e-9)
        }
    }

    @Test
    fun floorMarkerIgnoresRotationAboutItsNormalOnlyThroughYaw() {
        // Floor marker rotated 90° about its normal: forward rotates by the same yaw.
        val pose = RigidTransform(Vec3(1.0, 0.0, 1.0), Quaternion.fromYaw(PI / 2))
        val a = (VenueFrameEstimator.estimate(pose, MarkerMounting.FLOOR) as AlignmentResult.Valid).alignment
        // Image top edge (local −Z) rotated by +90° yaw points to session −X.
        assertTrue(a.toSession(Vec3.UNIT_Z).isApproximately(Vec3(0.0, 0.0, 1.0), 1e-9))
    }
}
