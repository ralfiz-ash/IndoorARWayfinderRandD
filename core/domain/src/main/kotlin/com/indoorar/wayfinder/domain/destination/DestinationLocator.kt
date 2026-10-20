package com.indoorar.wayfinder.domain.destination

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Venue

/** Where a saved destination is in the *current* AR session, if that can be trusted. */
sealed interface DestinationLocation {
    data class Located(
        val destination: Destination,
        val positionInSession: Vec3,
        /** Capture error plus current localization error. */
        val estimatedErrorMeters: Double,
    ) : DestinationLocation

    /** The venue frame has not been (re)established in this AR session: the marker must be scanned. */
    data class RequiresRelocalization(val destination: Destination, val status: LocalizationStatus) : DestinationLocation

    /** Saved in an older venue frame version (marker moved); must be recaptured. */
    data class StaleFrame(val destination: Destination, val savedVersion: Int, val currentVersion: Int) : DestinationLocation
}

/**
 * The only sanctioned way to turn a saved venue-frame position into AR session coordinates.
 * Saved coordinates are never used until the current session has a reliable marker-based alignment;
 * AR session coordinates from earlier sessions are never reused.
 */
object DestinationLocator {
    fun locate(destination: Destination, venue: Venue, localization: LocalizationStatus): DestinationLocation {
        if (!destination.isValidFor(venue)) {
            return DestinationLocation.StaleFrame(destination, destination.frameVersion, venue.frameVersion)
        }
        val localized = localization as? LocalizationStatus.Localized
        if (localized == null || !localized.isReliable) {
            return DestinationLocation.RequiresRelocalization(destination, localization)
        }
        return DestinationLocation.Located(
            destination = destination,
            positionInSession = localized.alignment.toSession(destination.positionInVenue),
            estimatedErrorMeters = destination.estimatedErrorMeters + localized.estimatedErrorMeters,
        )
    }
}
