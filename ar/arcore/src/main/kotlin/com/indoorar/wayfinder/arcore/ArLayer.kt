package com.indoorar.wayfinder.arcore

import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.indoorar.wayfinder.arcore.render.MeshRenderer
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationStatus

/** Per-frame data handed to layers on the GL thread. */
class ArFrameContext internal constructor(
    val session: Session,
    val frame: Frame,
    val localization: LocalizationStatus,
    /** Display-oriented camera pose in session coordinates; null while not tracking. */
    val cameraPose: RigidTransform?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val diagnostics: LocalizationDiagnostics,
)

/**
 * A feature-specific piece of AR behaviour (point selection, navigation guidance, …).
 * All methods are called on the GL thread except [release], which is called after rendering stops.
 */
interface ArLayer {
    fun onFrame(context: ArFrameContext)
    fun draw(renderer: MeshRenderer, context: ArFrameContext)
    /** Detach anchors and drop references to ARCore objects. */
    fun release()
}
