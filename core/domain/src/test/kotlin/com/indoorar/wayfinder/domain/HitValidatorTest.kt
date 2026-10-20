package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.selection.HitCandidate
import com.indoorar.wayfinder.domain.selection.HitRejection
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.selection.HitValidation
import com.indoorar.wayfinder.domain.selection.HitValidator
import com.indoorar.wayfinder.domain.selection.PlaneOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitValidatorTest {
    private val validator = HitValidator()
    private val camera = Vec3(0.0, 1.5, 0.0)

    private fun plane(y: Double = 0.0, dist: Double = 2.0, orientation: PlaneOrientation = PlaneOrientation.HORIZONTAL_UP, inside: Boolean = true) =
        HitCandidate(HitSource.PLANE, Vec3(0.0, y, -dist), Vec3.UNIT_Y, dist, orientation, inside)

    private fun depth(normal: Vec3 = Vec3.UNIT_Y, dist: Double = 2.0) =
        HitCandidate(HitSource.DEPTH, Vec3(0.0, 0.0, -dist), normal, dist)

    private fun rejection(vararg hits: HitCandidate) = (validator.select(hits.toList(), camera) as HitValidation.Rejected).reason

    @Test
    fun acceptsFloorPlaneInsidePolygon() {
        val result = validator.select(listOf(plane()), camera) as HitValidation.Accepted
        assertTrue(result.isLikelyFloor)
    }

    @Test
    fun prefersPlaneOverNearerDepthHit() {
        val p = plane(dist = 3.0)
        val result = validator.select(listOf(depth(dist = 1.0), p), camera) as HitValidation.Accepted
        assertEquals(p, result.hit)
    }

    @Test
    fun fallsBackToUpwardDepthHit() {
        val d = depth()
        assertEquals(d, (validator.select(listOf(d), camera) as HitValidation.Accepted).hit)
    }

    @Test
    fun rejectsInvalidHits() {
        assertEquals(HitRejection.NO_SURFACE, rejection())
        assertEquals(HitRejection.FEATURE_POINT_ONLY, rejection(HitCandidate(HitSource.FEATURE_POINT, Vec3.ZERO, null, 2.0)))
        assertEquals(HitRejection.NOT_HORIZONTAL, rejection(plane(orientation = PlaneOrientation.VERTICAL)))
        assertEquals(HitRejection.OUTSIDE_PLANE, rejection(plane(inside = false)))
        assertEquals(HitRejection.NOT_HORIZONTAL, rejection(depth(normal = Vec3(1.0, 0.3, 0.0))))
        assertEquals(HitRejection.TOO_CLOSE, rejection(plane(dist = 0.1)))
        assertEquals(HitRejection.TOO_FAR, rejection(plane(dist = 9.0)))
        assertEquals(HitRejection.ABOVE_FLOOR_LEVEL, rejection(plane(y = 1.4)))
    }

    @Test
    fun tableHeightSurfaceIsAcceptedButNotLikelyFloor() {
        val result = validator.select(listOf(plane(y = 0.75)), camera) as HitValidation.Accepted
        assertEquals(false, result.isLikelyFloor)
    }

    @Test
    fun reportsRejectionOfNearestCandidate() {
        assertEquals(HitRejection.OUTSIDE_PLANE, rejection(plane(dist = 4.0, orientation = PlaneOrientation.VERTICAL), plane(dist = 1.0, inside = false)))
    }
}
