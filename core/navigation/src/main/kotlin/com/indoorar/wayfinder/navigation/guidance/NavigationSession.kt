package com.indoorar.wayfinder.navigation.guidance

import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.route.Route
import com.indoorar.wayfinder.navigation.route.RouteFailure
import com.indoorar.wayfinder.navigation.route.RoutePlan
import com.indoorar.wayfinder.navigation.route.RoutePlanner
import com.indoorar.wayfinder.navigation.route.RoutingPolicy

data class NavigationPolicy(
    /** Straight-line distance to the destination for arrival. */
    val arrivalRadiusMeters: Double = 1.0,
    /** Remaining *route* distance must also be within radius + slack (prevents "arriving" through a wall). */
    val arrivalRouteSlackMeters: Double = 1.0,
    /**
     * Arrival requires the localization error estimate to be at most this. Must not exceed the
     * arrival radius, otherwise "arrived" could be reported up to radius + error away.
     */
    val maxArrivalErrorMeters: Double = 0.5,
    /** Arrival conditions must hold continuously for this long. */
    val arrivalDwellNanos: Long = 1_000_000_000L,
    /** User is off route when lateral offset > base + current localization error … */
    val deviationBaseMeters: Double = 2.0,
    /** … continuously for this long. */
    val deviationConfirmNanos: Long = 3_000_000_000L,
    /** Minimum time between route (re)planning attempts that failed. */
    val replanIntervalNanos: Long = 2_000_000_000L,
    val routing: RoutingPolicy = RoutingPolicy(),
    val progress: ProgressPolicy = ProgressPolicy(),
) {
    init {
        require(arrivalRadiusMeters > 0) { "arrivalRadiusMeters must be positive" }
        require(maxArrivalErrorMeters in 0.0..arrivalRadiusMeters) {
            "maxArrivalErrorMeters ($maxArrivalErrorMeters) must be within 0..arrivalRadiusMeters ($arrivalRadiusMeters)"
        }
    }
}

sealed interface NavigationFailure {
    data object StaleDestination : NavigationFailure
    data object NoGraph : NavigationFailure
    /** The graph was measured in a different venue frame version than the current one. */
    data object GraphFrameMismatch : NavigationFailure
    data class NoRoute(val reason: RouteFailure) : NavigationFailure
}

enum class WaitReason {
    /** The venue frame is not reliably established in this AR session (scan the marker). */
    LOCALIZATION,
    /** The user is not near any mapped walkable path. */
    START_OFF_GRAPH,
    RESUMING,
}

sealed interface NavigationState {
    data object Idle : NavigationState

    data class Preparing(val destination: Destination, val reason: WaitReason) : NavigationState

    data class Navigating(
        val destination: Destination,
        val route: Route,
        val progress: RouteProgress,
        val rerouteCount: Int,
    ) : NavigationState

    /** Lateral offset exceeded the threshold. [rerouteFailure] is set if replanning from here failed. */
    data class OffRoute(
        val destination: Destination,
        val route: Route,
        val progress: RouteProgress,
        val sinceNanos: Long,
        val rerouteFailure: RouteFailure?,
    ) : NavigationState

    /** Guidance suspended: positions cannot be trusted. Never reports arrival or deviation. */
    data class LocalizationLost(
        val destination: Destination,
        val route: Route,
        val lastProgress: RouteProgress?,
        val status: LocalizationStatus,
    ) : NavigationState

    data class Paused(val destination: Destination, val route: Route?) : NavigationState

    data class Arrived(val destination: Destination) : NavigationState

    data class Failed(val destination: Destination, val failure: NavigationFailure) : NavigationState
}

/**
 * Navigation state machine. Pure logic: feed it localization + the user's position in AR session
 * coordinates each frame (or at a few Hz); it converts through the venue alignment, plans and
 * re-plans over the graph, tracks progress, and detects deviation and arrival.
 *
 * Not thread-safe; drive it from one thread.
 */
class NavigationSession(
    private val graph: RouteGraph?,
    private val venue: Venue,
    private val policy: NavigationPolicy = NavigationPolicy(),
) {
    private val planner = graph?.let { RoutePlanner(it, policy.routing) }

    var state: NavigationState = NavigationState.Idle
        private set

    private var route: Route? = null
    private var rerouteCount = 0
    private var lastAlong: Double? = null
    private var lastProgress: RouteProgress? = null
    private var arrivalSince: Long? = null
    private var offRouteSince: Long? = null
    private var lastPlanAttempt: Long? = null

    fun start(destination: Destination): NavigationState {
        resetTracking()
        route = null
        rerouteCount = 0
        lastPlanAttempt = null
        state = when {
            !destination.isValidFor(venue) -> NavigationState.Failed(destination, NavigationFailure.StaleDestination)
            graph == null || graph.isEmpty -> NavigationState.Failed(destination, NavigationFailure.NoGraph)
            !graph.isCompatibleWith(venue) -> NavigationState.Failed(destination, NavigationFailure.GraphFrameMismatch)
            else -> NavigationState.Preparing(destination, WaitReason.LOCALIZATION)
        }
        return state
    }

    fun pause(): NavigationState {
        val destination = activeDestination() ?: return state
        resetTracking()
        state = NavigationState.Paused(destination, route)
        return state
    }

    fun resume(): NavigationState {
        val paused = state as? NavigationState.Paused ?: return state
        state = NavigationState.Preparing(paused.destination, WaitReason.RESUMING)
        return state
    }

    fun stop(): NavigationState {
        resetTracking()
        route = null
        state = NavigationState.Idle
        return state
    }

    /**
     * @param userPositionInSession camera position in AR session coordinates (null if not tracking).
     */
    fun update(timestampNanos: Long, localization: LocalizationStatus, userPositionInSession: Vec3?): NavigationState {
        val destination = when (val s = state) {
            is NavigationState.Preparing -> s.destination
            is NavigationState.Navigating -> s.destination
            is NavigationState.OffRoute -> s.destination
            is NavigationState.LocalizationLost -> s.destination
            else -> return state // Idle, Paused, Arrived, Failed: nothing to do.
        }

        val localized = (localization as? LocalizationStatus.Localized)?.takeIf { it.isReliable }
        if (localized == null || userPositionInSession == null) {
            resetTracking()
            val current = route
            state = if (current == null) {
                NavigationState.Preparing(destination, WaitReason.LOCALIZATION)
            } else {
                NavigationState.LocalizationLost(destination, current, lastProgress, localization)
            }
            return state
        }
        val user = localized.alignment.toVenue(userPositionInSession)

        val current = route ?: run {
            if (!planAllowed(timestampNanos)) return state
            when (val plan = planFrom(user, destination, timestampNanos)) {
                is RoutePlan.Found -> plan.route.also { route = it }
                is RoutePlan.NotFound -> {
                    state = if (plan.reason == RouteFailure.START_OFF_GRAPH) {
                        NavigationState.Preparing(destination, WaitReason.START_OFF_GRAPH)
                    } else {
                        NavigationState.Failed(destination, NavigationFailure.NoRoute(plan.reason))
                    }
                    return state
                }
            }
        }

        val progress = RouteProgressCalculator.compute(current, user, lastAlong, policy.progress)
        lastAlong = progress.distanceAlong
        lastProgress = progress

        // Arrival: close in a straight line AND along the route, with trustworthy localization, sustained.
        val arrivalConditions = progress.straightLineToDestination <= policy.arrivalRadiusMeters &&
            progress.remainingDistance <= policy.arrivalRadiusMeters + policy.arrivalRouteSlackMeters &&
            localized.estimatedErrorMeters <= policy.maxArrivalErrorMeters
        if (arrivalConditions) {
            val since = arrivalSince ?: timestampNanos.also { arrivalSince = it }
            if (timestampNanos - since >= policy.arrivalDwellNanos) {
                resetTracking()
                route = null
                state = NavigationState.Arrived(destination)
                return state
            }
        } else {
            arrivalSince = null
        }

        // Deviation: tolerance grows with the localization error so noise is not reported as deviation.
        val threshold = policy.deviationBaseMeters + localized.estimatedErrorMeters
        if (progress.lateralOffset <= threshold) {
            offRouteSince = null
            state = NavigationState.Navigating(destination, current, progress, rerouteCount)
            return state
        }
        val since = offRouteSince ?: timestampNanos.also { offRouteSince = it }
        var failure = (state as? NavigationState.OffRoute)?.rerouteFailure
        if (timestampNanos - since >= policy.deviationConfirmNanos && planAllowed(timestampNanos)) {
            when (val plan = planFrom(user, destination, timestampNanos)) {
                is RoutePlan.Found -> {
                    route = plan.route
                    rerouteCount++
                    offRouteSince = null
                    lastAlong = null
                    val fresh = RouteProgressCalculator.compute(plan.route, user, null, policy.progress)
                    lastAlong = fresh.distanceAlong
                    lastProgress = fresh
                    state = NavigationState.Navigating(destination, plan.route, fresh, rerouteCount)
                    return state
                }
                is RoutePlan.NotFound -> failure = plan.reason
            }
        }
        state = NavigationState.OffRoute(destination, current, progress, since, failure)
        return state
    }

    private fun planAllowed(t: Long): Boolean {
        val last = lastPlanAttempt ?: return true
        return t - last >= policy.replanIntervalNanos
    }

    private fun planFrom(user: Vec3, destination: Destination, t: Long): RoutePlan {
        lastPlanAttempt = t
        // Single-floor MVP: the user is assumed to be on the destination's floor.
        return planner!!.plan(user, destination.floorId, destination.positionInVenue, destination.floorId)
    }

    private fun activeDestination(): Destination? = when (val s = state) {
        is NavigationState.Preparing -> s.destination
        is NavigationState.Navigating -> s.destination
        is NavigationState.OffRoute -> s.destination
        is NavigationState.LocalizationLost -> s.destination
        else -> null
    }

    private fun resetTracking() {
        arrivalSince = null
        offRouteSince = null
        lastAlong = null
    }
}
