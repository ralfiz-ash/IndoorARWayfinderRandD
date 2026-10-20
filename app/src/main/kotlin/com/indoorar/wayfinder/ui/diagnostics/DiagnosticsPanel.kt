package com.indoorar.wayfinder.ui.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import java.util.Locale

/** Lines shown by the diagnostics overlay; pure for testing. */
object DiagnosticsText {
    private fun f(v: Double?, digits: Int = 2) = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "–"

    fun lines(status: LocalizationStatus, d: LocalizationDiagnostics): List<String> {
        val localized = status as? LocalizationStatus.Localized
        return listOf(
            "TRACKING STABILITY",
            "  status ${status.javaClass.simpleName}  marker ${d.markerQuality ?: "–"}",
            "  marker dist ${f(d.distanceToMarkerMeters)} m  angle ${f(d.viewingAngleDegrees, 0)}°  reject ${d.fixRejection ?: "–"}",
            "  fix frames ${d.stableFrames}/${d.requiredStableFrames}  spread ${f(d.fixSpreadMeters?.times(100), 1)} cm / ${f(d.fixSpreadDegrees, 1)}°",
            "  interruptions ${d.interruptions}  map corrections ${d.mapCorrections}",
            "  frame corrections ${d.frameCorrections}  last ${f(d.lastCorrectionMeters)} m / ${f(d.lastCorrectionDegrees, 1)}°",
            "  pending disagreement ${f(d.pendingDisagreementMeters)} m",
            "ABSOLUTE ERROR ESTIMATE (model, not measured)",
            "  total ±${f(localized?.estimatedErrorMeters)} m  ${localized?.confidence ?: ""}",
            "  fix ${f(d.errorFix)} + heading ${f(d.errorHeadingLever)} (σ ${f(d.yawSigmaDegrees, 1)}° × ${f(d.leverArmMeters, 1)} m)" +
                " + drift ${f(d.errorDrift)} + penalties ${f(d.errorPenalties)}",
            "  walked ${f(d.walkedSinceFixMeters, 1)} m since fix, ${f(d.secondsSinceFix, 0)} s ago",
        )
    }
}

@Composable
fun DiagnosticsPanel(status: LocalizationStatus, diagnostics: LocalizationDiagnostics, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.72f)) {
        Column(Modifier.padding(8.dp)) {
            DiagnosticsText.lines(status, diagnostics).forEach {
                Text(it, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}
