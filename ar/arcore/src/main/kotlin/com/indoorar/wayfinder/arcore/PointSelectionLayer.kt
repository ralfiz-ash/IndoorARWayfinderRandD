package com.indoorar.wayfinder.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.indoorar.wayfinder.arcore.render.MeshFactory
import com.indoorar.wayfinder.arcore.render.MeshRenderer
import com.indoorar.wayfinder.arcore.render.translationMatrix
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.yawOf
import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.selection.HitRejection
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.selection.HitValidation
import com.indoorar.wayfinder.domain.selection.HitValidator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The currently previewed point, in session coordinates (only meaningful within this session). */
data class SelectedPoint(
    val positionInSession: Vec3,
    val source: HitSource,
    val isLikelyFloor: Boolean,
    /** Session-frame yaw of the camera's viewing direction when the point was tapped. */
    val headingInSession: Double?,
    /** False while ARCore is not tracking the preview anchor. */
    val anchorTracking: Boolean,
    /**
     * Localization from the same AR frame as [positionInSession], so that the session→venue
     * conversion at save time uses a consistent pair.
     */
    val localization: LocalizationStatus,
    /** Camera position (session) and localization diagnostics from the same frame, for accuracy logging. */
    val cameraPositionInSession: Vec3? = null,
    val diagnostics: LocalizationDiagnostics? = null,
)

sealed interface TapResult {
    data class Accepted(val point: SelectedPoint) : TapResult
    data class Rejected(val reason: HitRejection) : TapResult
    /** The venue frame is not reliably established; the point could not be expressed in venue coordinates. */
    data object NotLocalized : TapResult
}

data class ReticleState(val visible: Boolean = false, val valid: Boolean = false, val rejection: HitRejection? = null)

/**
 * Point selection: centre reticle feedback, tap hit-testing with validation, and a single preview
 * anchor. Only one anchor exists at any time; it is detached on retry, clear or release.
 */
class PointSelectionLayer(private val validator: HitValidator = HitValidator()) : ArLayer {
    private sealed interface Command {
        data class Tap(val x: Float, val y: Float) : Command
        data object Clear : Command
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private var anchor: Anchor? = null
    private var anchorMeta: SelectedPoint? = null
    private var reticlePosition: Vec3? = null
    private var frameCounter = 0

    private val _reticle = MutableStateFlow(ReticleState())
    val reticle: StateFlow<ReticleState> = _reticle.asStateFlow()

    private val _selected = MutableStateFlow<SelectedPoint?>(null)
    val selected: StateFlow<SelectedPoint?> = _selected.asStateFlow()

    private val _tapResults = MutableSharedFlow<TapResult>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val tapResults: SharedFlow<TapResult> = _tapResults.asSharedFlow()

    private val reticleRing = MeshFactory.annulus(0.07f, 0.09f)
    private val reticleDot = MeshFactory.disc(0.015f)
    private val pin = MeshFactory.invertedCone(0.06f, 0.22f)
    private val pinBase = MeshFactory.annulus(0.10f, 0.14f)
    private val model = FloatArray(16)

    /** Screen coordinates in pixels relative to the AR surface view. */
    fun tap(x: Float, y: Float) { commands += Command.Tap(x, y) }

    fun clearSelection() { commands += Command.Clear }

    override fun onFrame(context: ArFrameContext) {
        while (true) {
            when (val command = commands.poll() ?: break) {
                is Command.Tap -> handleTap(context, command.x, command.y)
                Command.Clear -> detachAnchor()
            }
        }
        updateReticle(context)
        anchor?.let { a ->
            val meta = anchorMeta ?: return@let
            val tracking = a.trackingState == TrackingState.TRACKING
            val updated = meta.copy(
                positionInSession = if (tracking) a.pose.translationVec() else meta.positionInSession,
                anchorTracking = tracking,
                localization = context.localization,
                cameraPositionInSession = context.cameraPose?.translation ?: meta.cameraPositionInSession,
                diagnostics = context.diagnostics,
            )
            anchorMeta = updated
            if (updated != _selected.value) _selected.value = updated
        }
    }

    private fun handleTap(context: ArFrameContext, x: Float, y: Float) {
        val cameraPose = context.cameraPose
        if (cameraPose == null || !context.localization.isReliable) {
            _tapResults.tryEmit(TapResult.NotLocalized)
            return
        }
        val hits = context.frame.hitTest(x, y)
        val candidates = hits.map { it.toCandidate() }
        when (val validation = validator.select(candidates, cameraPose.translation)) {
            is HitValidation.Rejected -> _tapResults.tryEmit(TapResult.Rejected(validation.reason))
            is HitValidation.Accepted -> {
                val hit = hits[candidates.indexOf(validation.hit)]
                detachAnchor()
                anchor = hit.createAnchor()
                val forward = cameraPose.applyToDirection(Vec3(0.0, 0.0, -1.0)).horizontal()
                val point = SelectedPoint(
                    positionInSession = validation.hit.positionInSession,
                    source = validation.hit.source,
                    isLikelyFloor = validation.isLikelyFloor,
                    headingInSession = if (forward.length > 1e-3) yawOf(forward) else null,
                    anchorTracking = true,
                    localization = context.localization,
                    cameraPositionInSession = cameraPose.translation,
                    diagnostics = context.diagnostics,
                )
                anchorMeta = point
                _selected.value = point
                _tapResults.tryEmit(TapResult.Accepted(point))
            }
        }
    }

    private fun updateReticle(context: ArFrameContext) {
        val cameraPose = context.cameraPose
        if (cameraPose == null) {
            reticlePosition = null
            _reticle.value = ReticleState()
            return
        }
        // Centre hit test every other frame is plenty for reticle feedback.
        if (frameCounter++ % 2 != 0) return
        val candidates = context.frame
            .hitTest(context.viewportWidth / 2f, context.viewportHeight / 2f)
            .map { it.toCandidate() }
        val validation = validator.select(candidates, cameraPose.translation)
        val state = when (validation) {
            is HitValidation.Accepted -> {
                reticlePosition = validation.hit.positionInSession
                ReticleState(visible = true, valid = context.localization.isReliable)
            }
            is HitValidation.Rejected -> {
                reticlePosition = candidates.firstOrNull { it.source == HitSource.PLANE || it.source == HitSource.DEPTH }
                    ?.positionInSession
                ReticleState(visible = reticlePosition != null, valid = false, rejection = validation.reason)
            }
        }
        if (state != _reticle.value) _reticle.value = state
    }

    override fun draw(renderer: MeshRenderer, context: ArFrameContext) {
        reticlePosition?.let { p ->
            val color = if (_reticle.value.valid) VALID else INVALID
            translationMatrix(p + Vec3(0.0, 0.003, 0.0), model)
            renderer.draw(reticleRing, model, color)
            renderer.draw(reticleDot, model, color)
        }
        val selected = anchorMeta ?: return
        translationMatrix(selected.positionInSession + Vec3(0.0, 0.004, 0.0), model)
        val color = if (selected.anchorTracking) PREVIEW else PREVIEW_STALE
        renderer.draw(pinBase, model, color)
        renderer.draw(pin, model, color)
    }

    private fun detachAnchor() {
        anchor?.detach()
        anchor = null
        anchorMeta = null
        _selected.value = null
    }

    override fun release() {
        commands.clear()
        detachAnchor()
    }

    private companion object {
        val VALID = floatArrayOf(0.2f, 0.85f, 0.45f, 0.9f)
        val INVALID = floatArrayOf(1f, 0.55f, 0.15f, 0.8f)
        val PREVIEW = floatArrayOf(0.25f, 0.55f, 1f, 1f)
        val PREVIEW_STALE = floatArrayOf(0.6f, 0.6f, 0.6f, 0.7f)
    }
}
