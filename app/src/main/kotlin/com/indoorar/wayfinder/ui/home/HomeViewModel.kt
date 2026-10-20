package com.indoorar.wayfinder.ui.home

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationError
import com.indoorar.wayfinder.domain.destination.DestinationResult
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Venue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DestinationItem(val destination: Destination, val valid: Boolean)

data class HomeUiState(
    val loading: Boolean = true,
    val venue: Venue? = null,
    val destinations: List<DestinationItem> = emptyList(),
    val lastLocalization: LocalizationStatus? = null,
    val arAvailability: String = "Checking…",
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: Application, private val container: AppContainer) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageEvents = messages.receiveAsFlow()

    private val venueFlow = container.venueRepository.observeActiveVenue()

    val state: StateFlow<HomeUiState> = combine(
        venueFlow,
        venueFlow.flatMapLatest { venue ->
            container.destinationRepository.observeDestinations(venue.id)
                .map { list -> list.map { DestinationItem(it, it.isValidFor(venue)) } }
        },
        container.lastLocalization,
        arAvailabilityFlow(),
    ) { venue, destinations, localization, availability ->
        HomeUiState(false, venue, destinations, localization, availability)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** ARCore's availability check is asynchronous; poll until it settles. */
    private fun arAvailabilityFlow() = flow {
        var availability = ArCoreApk.getInstance().checkAvailability(app)
        emit(availabilityText(availability))
        repeat(50) {
            if (!availability.isTransient) return@flow
            delay(200)
            availability = ArCoreApk.getInstance().checkAvailability(app)
            emit(availabilityText(availability))
        }
    }

    private fun availabilityText(availability: ArCoreApk.Availability): String = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "ARCore supported and installed"
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "ARCore supported; update required"
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "ARCore supported; will be installed on first AR use"
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "This device does not support ARCore"
        ArCoreApk.Availability.UNKNOWN_CHECKING, ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        ArCoreApk.Availability.UNKNOWN_ERROR -> "ARCore availability unknown"
    }

    fun rename(destination: Destination, newName: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                container.destinationService.rename(destination.id, newName)
            } catch (e: SQLiteConstraintException) {
                DestinationResult.Failure(DestinationError.DuplicateName)
            }
            when (val r = result) {
                is DestinationResult.Success -> onResult(null)
                is DestinationResult.Failure -> onResult(
                    when (r.error) {
                        DestinationError.BlankName -> "Enter a name"
                        is DestinationError.NameTooLong -> "Too long"
                        DestinationError.DuplicateName -> "Name already used"
                        else -> "Could not rename"
                    },
                )
            }
        }
    }

    fun delete(destination: Destination) {
        viewModelScope.launch {
            container.destinationService.delete(destination.id)
            messages.send("Deleted \"${destination.name}\"")
        }
    }

    fun navigateTo(destination: Destination) {
        // Milestone 2: route graph + AR navigation. Be explicit rather than pretending.
        messages.trySend("Navigation to \"${destination.name}\" is not implemented yet (milestone 2).")
    }

    fun markerMoved() {
        viewModelScope.launch {
            val venue = state.value.venue ?: return@launch
            val updated = container.venueRepository.incrementFrameVersion(venue.id)
            messages.send("Venue frame is now v${updated.frameVersion}. Older destinations need to be recaptured.")
        }
    }
}
