package com.indoorar.wayfinder.arcore

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.Display
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.indoorar.wayfinder.arcore.render.BackgroundRenderer
import com.indoorar.wayfinder.arcore.render.MeshFactory
import com.indoorar.wayfinder.arcore.render.MeshRenderer
import com.indoorar.wayfinder.arcore.render.toModelMatrix
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.LocalizationDiagnostics
import com.indoorar.wayfinder.domain.localization.LocalizationPolicy
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LocalizationTracker
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingInput
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ArFailure {
    data object ArCoreNotInstalled : ArFailure
    data object DeviceNotSupported : ArFailure
    data object ArCoreUpdateRequired : ArFailure
    data object AppUpdateRequired : ArFailure
    data object CameraUnavailable : ArFailure
    data class MarkerImageRejected(val message: String) : ArFailure
    data class Unexpected(val message: String) : ArFailure
}

/** UI-facing snapshot, published at most [PUBLISH_INTERVAL_MS] apart. */
data class ArFrameSnapshot(
    val camera: CameraTracking = CameraTracking.Stopped,
    val localization: LocalizationStatus = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER),
    val floorPlaneDetected: Boolean = false,
    val depthEnabled: Boolean = false,
    val diagnostics: LocalizationDiagnostics = LocalizationDiagnostics(),
)

/**
 * Owns the ARCore [Session], the GL render loop and venue localization. Feature behaviour is
 * plugged in through [ArLayer]s. Call [resume]/[pause] from the host lifecycle and [close] once.
 */
class ArSessionController(
    context: Context,
    private val marker: MarkerSpec,
    policy: LocalizationPolicy = LocalizationPolicy(),
) : GLSurfaceView.Renderer {

    private val appContext = context.applicationContext
    private val tracker = LocalizationTracker(marker, policy)
    private val layers = CopyOnWriteArrayList<ArLayer>()
    private val background = BackgroundRenderer()
    private val meshRenderer = MeshRenderer()
    private val markerOutline = MeshFactory.box(1f, 0.002f, 1f)
    private val axisMeshes = listOf(
        MeshFactory.box(0.15f, 0.01f, 0.01f, cx = 0.075f) to floatArrayOf(0.95f, 0.25f, 0.25f, 1f),
        MeshFactory.box(0.01f, 0.15f, 0.01f, cy = 0.075f) to floatArrayOf(0.25f, 0.9f, 0.35f, 1f),
        MeshFactory.box(0.01f, 0.01f, 0.15f, cz = 0.075f) to floatArrayOf(0.3f, 0.5f, 1f, 1f),
    )
    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)

    @Volatile private var session: Session? = null
    @Volatile private var viewportChanged = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var installRequested = false
    private var pausedAtMs = 0L
    private val pendingResumeGapNanos = AtomicLong(-1)
    private var lastPublishMs = 0L
    private var depthEnabled = false

    private val _snapshot = MutableStateFlow(ArFrameSnapshot())
    val snapshot: StateFlow<ArFrameSnapshot> = _snapshot.asStateFlow()

    private val _failure = MutableStateFlow<ArFailure?>(null)
    val failure: StateFlow<ArFailure?> = _failure.asStateFlow()

    fun addLayer(layer: ArLayer) { layers += layer }

    fun createSurfaceView(context: Context): GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArSessionController)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    /**
     * Creates (first time) and resumes the session. Requires the camera permission.
     * @return false if ARCore installation was requested or the session could not start.
     */
    fun resume(activity: Activity): Boolean {
        if (_failure.value != null) return false
        val current = session ?: createSession(activity) ?: return false
        try {
            current.resume()
        } catch (e: CameraNotAvailableException) {
            fail(ArFailure.CameraUnavailable, e)
            return false
        }
        if (pausedAtMs > 0) {
            pendingResumeGapNanos.set((SystemClock.elapsedRealtime() - pausedAtMs) * 1_000_000)
        }
        return true
    }

    /** Call after the surface view has been paused. */
    fun pause() {
        session?.let {
            it.pause()
            pausedAtMs = SystemClock.elapsedRealtime()
        }
    }

    /** Releases layers' anchors and the session. Call after [pause] once the screen is gone. */
    fun close() {
        layers.forEach { it.release() }
        layers.clear()
        session?.close()
        session = null
    }

    private fun createSession(activity: Activity): Session? {
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return null
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val newSession = Session(appContext)
            configure(newSession)
            tracker.resetForNewSession()
            session = newSession
            return newSession
        } catch (e: UnavailableArcoreNotInstalledException) {
            fail(ArFailure.ArCoreNotInstalled, e)
        } catch (e: UnavailableUserDeclinedInstallationException) {
            fail(ArFailure.ArCoreNotInstalled, e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fail(ArFailure.DeviceNotSupported, e)
        } catch (e: UnavailableApkTooOldException) {
            fail(ArFailure.ArCoreUpdateRequired, e)
        } catch (e: UnavailableSdkTooOldException) {
            fail(ArFailure.AppUpdateRequired, e)
        } catch (e: ImageInsufficientQualityException) {
            fail(ArFailure.MarkerImageRejected("ARCore rejected the marker image as not trackable"), e)
        } catch (e: Exception) {
            fail(ArFailure.Unexpected(e.message ?: e.javaClass.simpleName), e)
        }
        return null
    }

    private fun configure(session: Session) {
        val config = Config(session).apply {
            augmentedImageDatabase = MarkerDatabase.create(appContext, session, marker)
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            // Fixed-focus cameras struggle to resolve a marker at arm's length.
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
        }
        depthEnabled = config.depthMode == Config.DepthMode.AUTOMATIC
        session.configure(config)
    }

    private fun fail(failure: ArFailure, e: Exception) {
        Log.e(TAG, "AR failure: $failure", e)
        _failure.value = failure
    }

    // ---- GLSurfaceView.Renderer (GL thread) ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        meshRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return
        if (viewportChanged) {
            session.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        session.setCameraTextureName(background.textureId)
        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            fail(ArFailure.CameraUnavailable, e)
            return
        } catch (e: Exception) {
            // SessionPausedException etc. can race with lifecycle changes; skip this frame.
            Log.w(TAG, "session.update failed", e)
            return
        }
        background.draw(frame)

        val camera = frame.camera
        val cameraTracking = camera.toCameraTracking()
        val tracking = cameraTracking == CameraTracking.Tracking
        val markerObservation = session.getAllTrackables(AugmentedImage::class.java)
            .firstOrNull { it.name == marker.imageName }
            ?.toObservation()

        pendingResumeGapNanos.getAndSet(-1).takeIf { it >= 0 }?.let(tracker::onSessionResumed)
        val localization = tracker.update(
            TrackingInput(
                timestampNanos = frame.timestamp,
                camera = cameraTracking,
                cameraPosition = if (tracking) camera.pose.translationVec() else null,
                marker = markerObservation,
            ),
        )

        val context = ArFrameContext(
            session, frame, localization,
            cameraPose = if (tracking) camera.displayOrientedPose.toRigidTransform() else null,
            viewportWidth, viewportHeight,
            tracker.diagnostics,
        )
        layers.forEach { it.onFrame(context) }

        if (tracking) {
            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
            meshRenderer.setViewProjection(view, projection)
            drawVenueOrigin(localization, markerObservation?.poseInSession)
            layers.forEach { it.draw(meshRenderer, context) }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
            lastPublishMs = now
            _snapshot.value = ArFrameSnapshot(
                camera = cameraTracking,
                localization = localization,
                floorPlaneDetected = session.getAllTrackables(Plane::class.java).any {
                    it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                },
                depthEnabled = depthEnabled,
                diagnostics = tracker.diagnostics,
            )
        }
        if (now - lastLogMs >= LOG_INTERVAL_MS) {
            lastLogMs = now
            logDiagnostics(cameraTracking, localization, if (tracking) camera.pose.translationVec() else null)
        }
    }

    private var lastLogMs = 0L

    /**
     * One line per second under tag [DIAG_TAG] for walk-through audits:
     * `adb logcat -s IndoorAR-Loc`. Camera position is converted to the venue frame when localized.
     */
    private fun logDiagnostics(camera: CameraTracking, localization: LocalizationStatus, cameraPosition: com.indoorar.wayfinder.domain.geometry.Vec3?) {
        val d = tracker.diagnostics
        val venuePos = (localization as? LocalizationStatus.Localized)?.let { l -> cameraPosition?.let(l.alignment::toVenue) }
        Log.i(
            DIAG_TAG,
            String.format(
                java.util.Locale.US,
                "cam=%s loc=%s err=%s venue=%s marker=%s dist=%s angle=%s reject=%s stable=%d/%d " +
                    "fix=%.2f lever=%.2f drift=%.2f pen=%.2f walked=%.1f sinceFix=%s pending=%s corr=%s(%d) mapCorr=%d intr=%d",
                camera.javaClass.simpleName, localization.javaClass.simpleName,
                (localization as? LocalizationStatus.Localized)?.let { "%.2f".format(java.util.Locale.US, it.estimatedErrorMeters) } ?: "-",
                venuePos?.let { "(%.2f,%.2f)".format(java.util.Locale.US, it.x, it.z) } ?: "-",
                d.markerQuality ?: "-", d.distanceToMarkerMeters?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-",
                d.viewingAngleDegrees?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-", d.fixRejection ?: "-",
                d.stableFrames, d.requiredStableFrames, d.errorFix, d.errorHeadingLever, d.errorDrift, d.errorPenalties,
                d.walkedSinceFixMeters, d.secondsSinceFix?.let { "%.0fs".format(java.util.Locale.US, it) } ?: "-",
                d.pendingDisagreementMeters?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-",
                d.lastCorrectionMeters?.let { "%.2fm/%.1f°".format(java.util.Locale.US, it, d.lastCorrectionDegrees ?: 0.0) } ?: "-",
                d.frameCorrections, d.mapCorrections, d.interruptions,
            ),
        )
    }

    /** Visual confirmation of localization: marker outline plus venue axes (X red, Y green, Z blue). */
    private fun drawVenueOrigin(localization: LocalizationStatus, markerPose: RigidTransform?) {
        val localized = localization as? LocalizationStatus.Localized ?: return
        if (markerPose != null && localized.markerInView) {
            markerPose.toModelMatrix(model, scale = marker.physicalWidthMeters.toFloat())
            meshRenderer.draw(markerOutline, model, floatArrayOf(0.2f, 0.9f, 0.4f, 0.35f))
        }
        localized.alignment.sessionFromVenue.toModelMatrix(model)
        axisMeshes.forEach { (mesh, color) -> meshRenderer.draw(mesh, model, color) }
    }

    // An Application context has no display of its own, so query the default display directly.
    private fun displayRotation(): Int =
        appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val TAG = "ArSessionController"
        const val PUBLISH_INTERVAL_MS = 100L
        const val LOG_INTERVAL_MS = 1_000L
        const val DIAG_TAG = "IndoorAR-Loc"
    }
}
