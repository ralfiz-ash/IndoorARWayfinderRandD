package com.indoorar.wayfinder

import com.indoorar.wayfinder.arcore.ArFrameSnapshot
import com.indoorar.wayfinder.arcore.SelectedPoint
import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.FixRejection
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.ui.UiText
import com.indoorar.wayfinder.ui.diagnostics.AccuracySampler
import com.indoorar.wayfinder.ui.diagnostics.AccuracyTarget
import com.indoorar.wayfinder.ui.diagnostics.DiagnosticsText
import com.indoorar.wayfinder.ui.diagnostics.SampleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class AuditUiTest {
    private val alignment = VenueAlignment(Vec3(2.0, 0.0, -1.0), PI / 2)
    private val localized = LocalizationStatus.Localized(alignment, 0.2, LocalizationConfidence.HIGH, false)

    @Test
    fun accuracySampleUsesVenueFrameAndApproachHeading() {
        // Session point 3 m along session +X from the origin = venue +Z 3 m (yaw +90°).
        val point = SelectedPoint(Vec3(5.0, -1.3, -1.0), HitSource.PLANE, true, headingInSession = PI / 2, anchorTracking = true,
            localization = localized, diagnostics = LocalizationDiagnostics(walkedSinceFixMeters = 7.0, secondsSinceFix = 12.0))
        val target = AccuracyTarget("T1", AccuracyCheckKind.ABSOLUTE, Vec3(0.2, -1.3, 3.0))
        val sample = (AccuracySampler.build(point, target, DefaultVenue.venue, "s", 1L) as SampleResult.Ok).sample
        assertTrue(sample.measured.isApproximately(Vec3(0.0, -1.3, 3.0), 1e-9))
        assertEquals(0.2, sample.horizontalError, 1e-9)
        assertEquals(0.0, sample.approachHeadingDegrees!!, 1e-9) // facing venue +Z
        assertEquals(3.0, sample.distanceFromMarkerMeters, 1e-9)
        assertEquals(7.0, sample.walkedSinceFixMeters, 0.0)
        assertTrue(sample.withinEstimate)
    }

    @Test
    fun accuracySampleRefusedWithoutReliableLocalization() {
        val point = SelectedPoint(Vec3.ZERO, HitSource.PLANE, true, null, true, LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE))
        assertEquals(SampleResult.NotLocalized, AccuracySampler.build(point, AccuracyTarget("x", AccuracyCheckKind.REPEATABILITY, Vec3.ZERO), DefaultVenue.venue, "s", 1))
        val low = point.copy(localization = localized.copy(confidence = LocalizationConfidence.LOW))
        assertEquals(SampleResult.NotLocalized, AccuracySampler.build(low, AccuracyTarget("x", AccuracyCheckKind.REPEATABILITY, Vec3.ZERO), DefaultVenue.venue, "s", 1))
    }

    @Test
    fun recoveryInstructionsAreSpecific() {
        val base = ArFrameSnapshot(camera = CameraTracking.Tracking, localization = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER))
        fun instr(d: LocalizationDiagnostics, loc: LocalizationStatus = base.localization) =
            UiText.selectionInstruction(base.copy(localization = loc, diagnostics = d), null, DefaultVenue.venue.marker)
        assertTrue(instr(LocalizationDiagnostics(fixRejection = FixRejection.TOO_OBLIQUE, viewingAngleDegrees = 72.0)).contains("72°"))
        assertTrue(instr(LocalizationDiagnostics(fixRejection = FixRejection.TOO_FAR, distanceToMarkerMeters = 4.2)).contains("within 3 m"))
        assertTrue(instr(LocalizationDiagnostics(fixRejection = FixRejection.STABILIZING, stableFrames = 3, requiredStableFrames = 8)).contains("3/8"))
        assertTrue(instr(LocalizationDiagnostics(fixRejection = FixRejection.UNSTABLE)).contains("jittery"))
        // Recovery while previously lost.
        assertTrue(instr(LocalizationDiagnostics(fixRejection = FixRejection.STABILIZING, stableFrames = 1, requiredStableFrames = 8), LocalizationStatus.Lost(LostReason.TRACKING_STOPPED)).contains("Hold steady"))
        // A disagreement overrides normal guidance even when localized.
        assertTrue(instr(LocalizationDiagnostics(pendingDisagreementMeters = 0.42), localized).contains("disagrees"))
    }

    @Test
    fun diagnosticsSeparateStabilityFromAccuracyModel() {
        val lines = DiagnosticsText.lines(localized, LocalizationDiagnostics(errorFix = 0.08, errorHeadingLever = 0.17, yawSigmaDegrees = 1.0, leverArmMeters = 10.0))
        assertTrue(lines.any { it == "TRACKING STABILITY" })
        assertTrue(lines.any { it.startsWith("ABSOLUTE ERROR ESTIMATE (model, not measured)") })
        assertTrue(lines.any { it.contains("heading 0.17") && it.contains("× 10.0 m") })
    }
}
