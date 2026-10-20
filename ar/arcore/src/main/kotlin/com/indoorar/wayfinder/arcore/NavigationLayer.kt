package com.indoorar.wayfinder.arcore

import android.os.SystemClock
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.indoorar.wayfinder.arcore.render.MeshFactory
import com.indoorar.wayfinder.arcore.render.MeshRenderer
import com.indoorar.wayfinder.arcore.render.toModelMatrix
import com.indoorar.wayfinder.arcore.render.translationMatrix
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.yawOf
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.guidance.GuidanceGeometry
import com.indoorar.wayfinder.navigation.guidance.GuidanceLayout
import com.indoorar.wayfinder.navigation.guidance.GuidanceSmoother
import com.indoorar.wayfinder.navigation.guidance.NavigationPolicy
import com.indoorar.wayfinder.navigation.guidance.NavigationSession
import com.indoorar.wayfinder.navigation.guidance.NavigationState
import com.indoorar.wayfinder.navigation.guidance.RouteProgress
import com.indoorar.wayfinder.navigation.guidance.RoutePose
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.Route
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pixel position on the AR surface. */
data class ScreenPoint(val x: Float, val y: Float)

/** What the UI needs each frame. Published at ~10 Hz, and immediately on state-type changes. */
data class GuidanceFrame(
    val state: NavigationState = NavigationState.Idle,
    /** World-aligned guidance is being rendered (reliable localization and an active route). */
    val worldGuidanceVisible: Boolean = false,
    /** The floating 3D arrow is inside the camera view. */
    val arrowOnScreen: Boolean = false,
    /** Screen position of the destination beacon top, for the distance label; null if off-screen. */
    val beaconScreen: ScreenPoint? = null,
    /** Angle from the camera's view direction to the arrow, positive = left. Used only by the fallback. */
    val relativeBearingRadians: Double? = null,
    /** Increments on every start(); lets the UI tell navigation runs apart (duplicate dialog guard). */
    val runId: Int = 0,
)

/**
 * Runs the navigation engine against each AR frame and renders world-aligned guidance:
 * a floating arrow ahead on the route, floor chevrons, a next-turn marker and a destination beacon.
 *
 * Guidance is drawn only while the session reports Navigating/OffRoute, which already requires
 * reliable localization; in every other state nothing directional is drawn (never frozen in a
 * possibly wrong place). The engine itself lives in :core:navigation and has no rendering code.
 */
class NavigationLayer(
    private val policy: NavigationPolicy = NavigationPolicy(),
    private val layout: GuidanceLayout = GuidanceLayout(),
) : ArLayer {

    private sealed interface Command {
        data class Start(val venue: Venue, val graph: RouteGraph?, val destination: Destination) : Command
        data object Pause : Command
        data object Resume : Command
        data object Stop : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private var session: NavigationSession? = null
    private var runId = 0
    private val arrowSmoother = GuidanceSmoother()
    private var state: NavigationState = NavigationState.Idle

    // Render model for this frame (session coordinates), null = draw nothing.
    private class RenderModel(
        val arrow: RigidTransform,
        val chevrons: List<RigidTransform>,
        val turnMarker: RigidTransform?,
        val beaconBase: Vec3,
        val arrowCenter: Vec3,
        val cameraPosition: Vec3,
        val cameraYawVenue: Double,
        val arrowVenue: Vec3,
        val userVenue: Vec3,
    )
    private var model: RenderModel? = null

    private val _frame = MutableStateFlow(GuidanceFrame())
    val frame: StateFlow<GuidanceFrame> = _frame.asStateFlow()
    private var lastPublishMs = 0L

    private val arrowMesh = MeshFactory.arrow()
    private val chevronMesh = MeshFactory.chevron()
    private val turnMesh = MeshFactory.arrow()
    private val beaconRing = MeshFactory.annulus(0.25f, 0.32f)
    private val beaconColumn = MeshFactory.cylinder(0.12f, BEACON_HEIGHT)
    private val matrix = FloatArray(16)

    fun start(venue: Venue, graph: RouteGraph?, destination: Destination) { commands += Command.Start(venue, graph, destination) }
    fun pause() { commands += Command.Pause }
    fun resume() { commands += Command.Resume }
    fun stop() { commands += Command.Stop }

    override fun onFrame(context: ArFrameContext) {
        drainCommands()
        val s = session ?: return
        val cameraPose = context.cameraPose
        state = s.update(context.frame.timestamp, context.localization, cameraPose?.translation)
        model = buildModel(context, cameraPose)
        if (model == null) {
            arrowSmoother.reset()
            // draw() is skipped while the camera is not tracking, so publish the hidden state here.
            latest = GuidanceFrame(state = state, runId = runId)
            publish(force = state::class != _frame.value.state::class)
        }
    }

    private fun drainCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.Start -> {
                    session = NavigationSession(c.graph, c.venue, policy).also { state = it.start(c.destination) }
                    runId++
                    arrowSmoother.reset()
                    publish(force = true)
                }
                Command.Pause -> session?.let { state = it.pause(); publish(force = true) }
                Command.Resume -> session?.let { state = it.resume(); publish(force = true) }
                Command.Stop -> {
                    session?.stop()
                    session = null
                    state = NavigationState.Idle
                    model = null
                    publish(force = true)
                }
            }
        }
    }

    private fun buildModel(context: ArFrameContext, cameraPose: RigidTransform?): RenderModel? {
        val (route, progress) = when (val st = state) {
            is NavigationState.Navigating -> st.route to st.progress
            is NavigationState.OffRoute -> st.route to st.progress
            else -> return null
        }
        val localized = context.localization as? LocalizationStatus.Localized ?: return null
        if (!localized.isReliable || cameraPose == null) return null
        val alignment = localized.alignment
        val camera = cameraPose.translation
        val floorY = floorHeightInVenue(context, alignment, camera, route)

        val target = GuidanceGeometry.arrowPose(route, progress.distanceAlong, layout)
        val smoothed = arrowSmoother.update(target, context.frame.timestamp)
        val arrowVenue = smoothed.position.copy(y = floorY + ARROW_HEIGHT)
        val arrow = poseInSession(alignment, RoutePose(arrowVenue, smoothed.yaw))

        val chevrons = GuidanceGeometry.chevrons(route, progress.distanceAlong, layout).map {
            poseInSession(alignment, RoutePose(it.position.copy(y = floorY + 0.01), it.yaw))
        }
        val turnMarker = nextTurnPose(route, progress)?.let {
            poseInSession(alignment, RoutePose(it.position.copy(y = floorY + 0.03), it.yaw))
        }
        val forward = cameraPose.applyToDirection(Vec3(0.0, 0.0, -1.0)).horizontal()
        val cameraYawVenue = alignment.yawToVenue(if (forward.horizontalLength > 1e-3) yawOf(forward) else 0.0)
        val userVenue = alignment.toVenue(camera)
        return RenderModel(
            arrow = arrow,
            chevrons = chevrons,
            turnMarker = turnMarker,
            beaconBase = alignment.toSession(route.destination.copy(y = floorY)),
            arrowCenter = arrow.translation,
            cameraPosition = camera,
            cameraYawVenue = cameraYawVenue,
            arrowVenue = arrowVenue,
            userVenue = userVenue,
        )
    }

    /** Pose of the turn marker: at the next turn, pointing along the outgoing direction. */
    private fun nextTurnPose(route: Route, progress: RouteProgress): RoutePose? {
        val m = progress.nextManeuver
        if (m.type != ManeuverType.TURN_LEFT && m.type != ManeuverType.TURN_RIGHT) return null
        if (progress.distanceToNextManeuver > TURN_MARKER_RANGE) return null
        val out = route.pointAt(m.atDistance + 1.0) - m.position
        if (out.horizontalLength < 1e-3) return null
        return RoutePose(m.position, yawOf(out))
    }

    /**
     * Floor height in the venue frame: the lowest tracked upward plane at a plausible distance below
     * the camera; otherwise the destination's height (destinations are captured on the floor).
     */
    private fun floorHeightInVenue(context: ArFrameContext, alignment: VenueAlignment, camera: Vec3, route: Route): Double {
        var best: Double? = null
        for (plane in context.session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val drop = camera.y - plane.centerPose.ty()
            if (drop in 0.8..2.2 && (best == null || plane.centerPose.ty() < best)) best = plane.centerPose.ty().toDouble()
        }
        return best?.let { alignment.toVenue(Vec3(camera.x, it, camera.z)).y } ?: route.destination.y
    }

    private fun poseInSession(alignment: VenueAlignment, pose: RoutePose) = RigidTransform(
        alignment.toSession(pose.position),
        Quaternion.fromYaw(alignment.yawToSession(pose.yaw)),
    )

    override fun draw(renderer: MeshRenderer, context: ArFrameContext) {
        val m = model ?: return
        var arrowOnScreen = false
        var beaconScreen: ScreenPoint? = null
        run {
            m.chevrons.forEach { renderer.draw(chevronMesh, it.toModelMatrix(matrix), CHEVRON) }
            m.turnMarker?.let { renderer.draw(turnMesh, it.toModelMatrix(matrix, scale = 1.4f), TURN) }
            renderer.draw(arrowMesh, m.arrow.toModelMatrix(matrix, scale = 1.2f), ARROW)
            translationMatrix(m.beaconBase + Vec3(0.0, 0.01, 0.0), matrix)
            renderer.draw(beaconRing, matrix, BEACON)
            renderer.draw(beaconColumn, matrix, BEACON_COLUMN)

            val w = context.viewportWidth
            val h = context.viewportHeight
            renderer.project(m.arrowCenter.x.toFloat(), m.arrowCenter.y.toFloat(), m.arrowCenter.z.toFloat(), w, h)?.let {
                arrowOnScreen = it[0] in 0f..w.toFloat() && it[1] in 0f..h.toFloat()
            }
            val top = m.beaconBase + Vec3(0.0, BEACON_HEIGHT.toDouble(), 0.0)
            renderer.project(top.x.toFloat(), top.y.toFloat(), top.z.toFloat(), w, h)?.let {
                if (it[0] in 0f..w.toFloat() && it[1] in 0f..h.toFloat()) beaconScreen = ScreenPoint(it[0], it[1])
            }
        }
        latest = GuidanceFrame(
            state = state,
            worldGuidanceVisible = true,
            arrowOnScreen = arrowOnScreen,
            beaconScreen = beaconScreen,
            relativeBearingRadians = GuidanceGeometry.relativeBearing(m.cameraYawVenue, m.userVenue, m.arrowVenue),
            runId = runId,
        )
        publish(force = latest.state::class != _frame.value.state::class)
    }

    private var latest = GuidanceFrame()

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val candidate = if (session == null) GuidanceFrame(state = state, runId = runId) else latest.copy(state = state, runId = runId)
        if (force || now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
            lastPublishMs = now
            _frame.value = candidate
        }
    }

    override fun release() {
        commands.clear()
        session?.stop()
        session = null
        model = null
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 100L
        /** Floating arrow height above the floor: below eye level, inside a forward-held phone's view. */
        const val ARROW_HEIGHT = 0.9
        const val BEACON_HEIGHT = 2.2f
        const val TURN_MARKER_RANGE = 12.0
        val ARROW = floatArrayOf(0.15f, 0.55f, 1f, 1f)
        val CHEVRON = floatArrayOf(0.2f, 0.7f, 1f, 0.75f)
        val TURN = floatArrayOf(1f, 0.6f, 0.1f, 0.95f)
        val BEACON = floatArrayOf(0.2f, 0.9f, 0.45f, 0.9f)
        val BEACON_COLUMN = floatArrayOf(0.2f, 0.9f, 0.45f, 0.3f)
    }
}
