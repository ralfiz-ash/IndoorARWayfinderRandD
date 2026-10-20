package com.indoorar.wayfinder

import com.indoorar.wayfinder.arcore.ArFrameSnapshot
import com.indoorar.wayfinder.arcore.SelectedPoint
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.ui.selection.StageTimer
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.ui.StatusLevel
import com.indoorar.wayfinder.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    private val localized = LocalizationStatus.Localized(VenueAlignment(Vec3.ZERO, 0.0), 0.1, LocalizationConfidence.HIGH, true)
    private val marker = MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL)
    private fun instruction(snapshot: ArFrameSnapshot, selected: SelectedPoint? = null, notLocalized: Double = 0.0, noFloor: Double = 0.0) =
        UiText.selectionInstruction(snapshot, selected, marker, notLocalized, noFloor)

    @Test
    fun guidesThroughMarkerThenFloorThenTap() {
        val tracking = ArFrameSnapshot(camera = CameraTracking.Tracking)
        assertTrue(instruction(tracking).startsWith("Step 1"))
        assertTrue(instruction(tracking.copy(localization = localized)).startsWith("Step 2"))
        assertTrue(instruction(tracking.copy(localization = localized, floorPlaneDetected = true)).startsWith("Step 3"))
        val selected = SelectedPoint(Vec3.ZERO, HitSource.PLANE, true, null, true, localized)
        assertTrue(instruction(tracking.copy(localization = localized, floorPlaneDetected = true), selected).startsWith("Point selected"))
    }

    @Test
    fun trackingAndLocalizationProblemsTakePriority() {
        val limited = ArFrameSnapshot(camera = CameraTracking.Limited(TrackingIssue.INSUFFICIENT_LIGHT), localization = localized)
        assertTrue(instruction(limited).contains("too dark"))
        val lost = ArFrameSnapshot(camera = CameraTracking.Tracking, localization = LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE))
        assertTrue(instruction(lost).contains("marker again"))
        val mismatch = ArFrameSnapshot(
            camera = CameraTracking.Tracking,
            localization = LocalizationStatus.NotLocalized(NotLocalizedReason.MARKER_ORIENTATION_MISMATCH),
        )
        assertTrue(instruction(mismatch).contains("on a wall"))
        val uncertain = ArFrameSnapshot(camera = CameraTracking.Tracking, localization = localized.copy(confidence = LocalizationConfidence.LOW))
        assertTrue(instruction(uncertain).contains("scan it again"))
    }

    @Test
    fun localizationStatusLevels() {
        assertEquals(StatusLevel.GOOD, UiText.localization(localized).level)
        assertEquals(StatusLevel.BAD, UiText.localization(LocalizationStatus.Lost(LostReason.TRACKING_STOPPED)).level)
        assertEquals(StatusLevel.NEUTRAL, UiText.localization(null).level)
    }

    @Test
    fun explainsMissingMarkerAndUndetectedFloorAfterAWhile() {
        val tracking = ArFrameSnapshot(camera = CameraTracking.Tracking)
        assertTrue(instruction(tracking, notLocalized = 5.0).startsWith("Step 1"))
        assertTrue(instruction(tracking, notLocalized = 20.0).startsWith("Marker not found yet"))
        assertTrue(instruction(tracking, notLocalized = 20.0).contains("20 cm"))
        val noFloor = tracking.copy(localization = localized)
        assertTrue(instruction(noFloor, noFloor = 5.0).startsWith("Step 2"))
        assertTrue(instruction(noFloor, noFloor = 30.0).startsWith("Floor not detected yet"))
        assertTrue(instruction(noFloor, noFloor = 30.0).contains("no depth support"))
        assertTrue(instruction(noFloor.copy(depthEnabled = true), noFloor = 30.0).endsWith("textured areas."))
    }

    @Test
    fun stageTimerMeasuresTimeInEachStage() {
        var now = 0L
        val timer = StageTimer { now }
        val notLocalized = ArFrameSnapshot(camera = CameraTracking.Tracking)
        timer.update(notLocalized)
        now = 4_000
        assertEquals(4.0 to 0.0, timer.update(notLocalized))
        val noFloor = notLocalized.copy(localization = localized)
        assertEquals(0.0 to 0.0, timer.update(noFloor))
        now = 10_000
        assertEquals(0.0 to 6.0, timer.update(noFloor))
        assertEquals(0.0 to 0.0, timer.update(noFloor.copy(floorPlaneDetected = true)))
        // Losing localization restarts the marker timer.
        now = 11_000
        assertEquals(0.0 to 0.0, timer.update(notLocalized))
    }
}
