package com.indoorar.wayfinder.arcore

import com.indoorar.wayfinder.arcore.render.MeshFactory
import com.indoorar.wayfinder.arcore.render.MeshRenderer
import com.indoorar.wayfinder.arcore.render.translationMatrix
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationLocation
import com.indoorar.wayfinder.domain.destination.DestinationLocator
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.venue.Venue

/**
 * Shows already-saved destinations as small markers, but only once the venue frame has been
 * re-established in this session (via [DestinationLocator]). Uses no anchors: positions are derived
 * every frame from the marker alignment, so they follow ARCore's map refinements of the marker.
 */
class SavedDestinationsLayer : ArLayer {
    @Volatile private var venue: Venue? = null
    @Volatile private var destinations: List<Destination> = emptyList()
    private var located: List<DestinationLocation.Located> = emptyList()

    private val ring = MeshFactory.annulus(0.12f, 0.16f)
    private val post = MeshFactory.box(0.02f, 0.5f, 0.02f, cy = 0.25f)
    private val model = FloatArray(16)

    /** Called from the UI thread whenever the stored data changes. */
    fun update(venue: Venue, destinations: List<Destination>) {
        this.venue = venue
        this.destinations = destinations
    }

    override fun onFrame(context: ArFrameContext) {
        val v = venue ?: return
        located = destinations
            .map { DestinationLocator.locate(it, v, context.localization) }
            .filterIsInstance<DestinationLocation.Located>()
    }

    override fun draw(renderer: MeshRenderer, context: ArFrameContext) {
        for (d in located) {
            translationMatrix(d.positionInSession + Vec3(0.0, 0.003, 0.0), model)
            renderer.draw(ring, model, COLOR)
            renderer.draw(post, model, COLOR)
        }
    }

    override fun release() {
        located = emptyList()
    }

    private companion object {
        val COLOR = floatArrayOf(0.95f, 0.75f, 0.2f, 0.85f)
    }
}
