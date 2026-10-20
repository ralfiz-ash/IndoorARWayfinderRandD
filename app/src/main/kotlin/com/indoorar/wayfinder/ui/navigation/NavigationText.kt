package com.indoorar.wayfinder.ui.navigation

import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.navigation.guidance.NavigationFailure
import com.indoorar.wayfinder.navigation.guidance.NavigationState
import com.indoorar.wayfinder.navigation.guidance.RouteProgress
import com.indoorar.wayfinder.navigation.guidance.WaitReason
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.ui.UiText
import kotlin.math.roundToInt

/** Instruction shown in the guidance card. [glyph] is a plain symbol, not a navigation arrow in the scene. */
data class Instruction(val glyph: String, val primary: String, val secondary: String?)

/** Warning shown when guidance is hidden or unreliable. */
data class GuidanceWarning(val text: String, val severe: Boolean)

object NavigationText {
    fun distance(meters: Double): String = when {
        meters < 1.0 -> "under 1 m"
        meters < 10 -> "${(meters * 2).roundToInt() / 2.0} m".replace(".0 m", " m")
        else -> "${meters.roundToInt()} m"
    }

    fun instruction(progress: RouteProgress, destinationName: String): Instruction {
        val next = progress.nextManeuver
        val d = distance(progress.distanceToNextManeuver)
        val remaining = "${distance(progress.remainingDistance)} to $destinationName"
        return when (next.type) {
            ManeuverType.TURN_LEFT -> Instruction(
                "↰", if (progress.distanceToNextManeuver < 2) "Turn ${if (next.sharp) "sharp " else ""}left now"
                else "Turn ${if (next.sharp) "sharp " else ""}left in $d", remaining,
            )
            ManeuverType.TURN_RIGHT -> Instruction(
                "↱", if (progress.distanceToNextManeuver < 2) "Turn ${if (next.sharp) "sharp " else ""}right now"
                else "Turn ${if (next.sharp) "sharp " else ""}right in $d", remaining,
            )
            ManeuverType.ARRIVE -> Instruction("⚑", "$destinationName in $d", null)
            ManeuverType.STRAIGHT -> Instruction("↑", "Continue straight for $d", remaining)
        }
    }

    /** Why directional guidance is hidden, or null when guidance is fine. */
    fun warning(
        state: NavigationState,
        localization: LocalizationStatus,
        diagnostics: LocalizationDiagnostics = LocalizationDiagnostics(),
    ): GuidanceWarning? = when (state) {
        is NavigationState.Preparing -> when (state.reason) {
            WaitReason.LOCALIZATION, WaitReason.RESUMING -> GuidanceWarning(
                UiText.markerRecovery(diagnostics) ?: if (localization is LocalizationStatus.Localized && !localization.isReliable) {
                    "Position too uncertain. Point the camera at the venue marker to recalibrate."
                } else {
                    "Point the camera at the venue marker to start guidance."
                },
                severe = false,
            )
            WaitReason.START_OFF_GRAPH -> GuidanceWarning(UiText.routeFailure(com.indoorar.wayfinder.navigation.route.RouteFailure.START_OFF_GRAPH), false)
        }
        is NavigationState.LocalizationLost -> GuidanceWarning(
            when (val s = state.status) {
                is LocalizationStatus.Interrupted -> "Guidance paused: ${UiText.issue(s.issue)}."
                is LocalizationStatus.Lost -> "Guidance hidden: ${UiText.lost(s.reason)}. Scan the venue marker again."
                is LocalizationStatus.Localized -> UiText.markerRecovery(diagnostics)?.let { "Guidance hidden. $it" }
                    ?: "Guidance hidden: position too uncertain (±${UiText.meters(s.estimatedErrorMeters)}). Scan the venue marker again."
                else -> "Guidance hidden: scan the venue marker."
            },
            severe = true,
        )
        is NavigationState.OffRoute -> GuidanceWarning(
            state.rerouteFailure?.let { "Off route. ${UiText.routeFailure(it)}" } ?: "Off route: finding a new route…",
            severe = state.rerouteFailure != null,
        )
        else -> null
    }

    fun failure(failure: NavigationFailure): String = when (failure) {
        NavigationFailure.StaleDestination -> "This destination was saved before the marker was moved. Recapture it."
        NavigationFailure.NoGraph -> UiText.routeFailure(com.indoorar.wayfinder.navigation.route.RouteFailure.NO_GRAPH)
        NavigationFailure.GraphFrameMismatch -> "The route graph was measured for a different marker placement. Re-measure it."
        is NavigationFailure.NoRoute -> UiText.routeFailure(failure.reason)
    }
}

/**
 * Shows the arrival dialog at most once per navigation run, even though the Arrived state is
 * re-published repeatedly and the screen may recompose or be recreated.
 */
class ArrivalDialogGuard {
    private var shownForRun: Int? = null

    /** @return true exactly once for each run that reaches Arrived. */
    fun shouldShow(state: NavigationState, runId: Int): Boolean {
        if (state !is NavigationState.Arrived || shownForRun == runId) return false
        shownForRun = runId
        return true
    }
}
