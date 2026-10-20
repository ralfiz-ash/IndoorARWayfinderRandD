package com.indoorar.wayfinder.domain.destination

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.Venue
import java.util.UUID

sealed interface DestinationError {
    data object BlankName : DestinationError
    data class NameTooLong(val max: Int) : DestinationError
    data object DuplicateName : DestinationError
    data object NotLocalized : DestinationError
    data object LocalizationUnreliable : DestinationError
    data object NotFound : DestinationError
}

sealed interface DestinationResult<out T> {
    data class Success<T>(val value: T) : DestinationResult<T>
    data class Failure(val error: DestinationError) : DestinationResult<Nothing>
}

/** Use cases for creating, renaming and deleting destinations. */
class DestinationService(
    private val repository: DestinationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Saves a point selected in the current AR session. The session position is converted to the
     * venue frame using the current alignment; creation is refused unless localization is reliable.
     */
    suspend fun create(
        name: String,
        pointInSession: Vec3,
        captureHeadingInSession: Double?,
        captureSource: HitSource,
        localization: LocalizationStatus,
        venue: Venue,
        floor: Floor = venue.defaultFloor,
    ): DestinationResult<Destination> {
        val trimmed = when (val v = validateName(name)) {
            is DestinationResult.Failure -> return v
            is DestinationResult.Success -> v.value
        }
        val localized = localization as? LocalizationStatus.Localized
            ?: return DestinationResult.Failure(DestinationError.NotLocalized)
        if (!localized.isReliable) return DestinationResult.Failure(DestinationError.LocalizationUnreliable)
        if (repository.findByName(venue.id, trimmed) != null) {
            return DestinationResult.Failure(DestinationError.DuplicateName)
        }
        val now = clock()
        val destination = Destination(
            id = DestinationId(idFactory()),
            venueId = venue.id,
            floorId = floor.id,
            name = trimmed,
            positionInVenue = localized.alignment.toVenue(pointInSession),
            captureHeadingRadians = captureHeadingInSession?.let(localized.alignment::yawToVenue),
            frameVersion = venue.frameVersion,
            captureSource = captureSource,
            estimatedErrorMeters = localized.estimatedErrorMeters,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.save(destination)
        return DestinationResult.Success(destination)
    }

    suspend fun rename(id: DestinationId, newName: String): DestinationResult<Destination> {
        val trimmed = when (val v = validateName(newName)) {
            is DestinationResult.Failure -> return v
            is DestinationResult.Success -> v.value
        }
        val existing = repository.getDestination(id)
            ?: return DestinationResult.Failure(DestinationError.NotFound)
        val clash = repository.findByName(existing.venueId, trimmed)
        if (clash != null && clash.id != id) return DestinationResult.Failure(DestinationError.DuplicateName)
        val updated = existing.copy(name = trimmed, updatedAtEpochMillis = clock())
        repository.save(updated)
        return DestinationResult.Success(updated)
    }

    suspend fun delete(id: DestinationId): DestinationResult<Unit> {
        if (repository.getDestination(id) == null) return DestinationResult.Failure(DestinationError.NotFound)
        repository.delete(id)
        return DestinationResult.Success(Unit)
    }

    fun validateName(name: String): DestinationResult<String> {
        val trimmed = name.trim().replace(Regex("\\s+"), " ")
        return when {
            trimmed.isEmpty() -> DestinationResult.Failure(DestinationError.BlankName)
            trimmed.length > MAX_NAME_LENGTH -> DestinationResult.Failure(DestinationError.NameTooLong(MAX_NAME_LENGTH))
            else -> DestinationResult.Success(trimmed)
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}
