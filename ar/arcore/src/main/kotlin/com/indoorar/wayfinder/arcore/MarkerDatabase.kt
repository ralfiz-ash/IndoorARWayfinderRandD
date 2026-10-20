package com.indoorar.wayfinder.arcore

import android.content.Context
import android.graphics.BitmapFactory
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Session
import com.indoorar.wayfinder.domain.venue.MarkerSpec

object MarkerDatabase {
    const val ASSET_DIR = "markers"

    /**
     * Builds the augmented image database for the venue marker. ARCore scores the image here and
     * throws `ImageInsufficientQualityException` if it is not trackable.
     */
    fun create(context: Context, session: Session, marker: MarkerSpec): AugmentedImageDatabase {
        val bitmap = context.assets.open("$ASSET_DIR/${marker.imageName}.png").use(BitmapFactory::decodeStream)
            ?: error("Marker asset ${marker.imageName}.png could not be decoded")
        return AugmentedImageDatabase(session).apply {
            addImage(marker.imageName, bitmap, marker.physicalWidthMeters.toFloat())
        }
    }
}
