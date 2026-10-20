package com.indoorar.wayfinder.ui.navigation

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.arcore.ArFailure
import com.indoorar.wayfinder.arcore.ArFrameSnapshot
import com.indoorar.wayfinder.arcore.ArSessionController
import com.indoorar.wayfinder.arcore.GuidanceFrame
import com.indoorar.wayfinder.arcore.NavigationLayer
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavigationUiState(
    val destination: Destination? = null,
    val snapshot: ArFrameSnapshot = ArFrameSnapshot(),
    val guidance: GuidanceFrame = GuidanceFrame(),
    val failure: ArFailure? = null,
    val showArrival: Boolean = false,
    val choosingNext: Boolean = false,
    val alternatives: List<Destination> = emptyList(),
    val loadError: String? = null,
)

private data class Ui(
    val destination: Destination? = null,
    val showArrival: Boolean = false,
    val choosingNext: Boolean = false,
    val alternatives: List<Destination> = emptyList(),
    val loadError: String? = null,
)

class NavigationViewModel(
    application: Application,
    private val container: AppContainer,
    destinationId: DestinationId,
) : ViewModel() {
    private val navigation = NavigationLayer(container.navigationPolicy)
    val controller = ArSessionController(application, container.venueConfig.marker).apply { addLayer(navigation) }
    private val arrivalGuard = ArrivalDialogGuard()
    private val ui = MutableStateFlow(Ui())

    val state: StateFlow<NavigationUiState> = combine(controller.snapshot, navigation.frame, controller.failure, ui) { snapshot, frame, failure, u ->
        NavigationUiState(u.destination, snapshot, frame, failure, u.showArrival, u.choosingNext, u.alternatives, u.loadError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState())

    init {
        viewModelScope.launch { startNavigation(destinationId) }
        viewModelScope.launch {
            navigation.frame.collect { frame ->
                if (arrivalGuard.shouldShow(frame.state, frame.runId)) ui.update { it.copy(showArrival = true) }
            }
        }
        viewModelScope.launch {
            controller.snapshot.collect { container.lastLocalization.value = it.localization }
        }
    }

    private suspend fun startNavigation(id: DestinationId) {
        val destination = container.destinationRepository.getDestination(id)
        if (destination == null) {
            ui.update { it.copy(loadError = "Destination no longer exists.") }
            return
        }
        val venue = container.venueRepository.getActiveVenue()
        // The AR session and its marker alignment are kept: switching destinations needs no rescan.
        navigation.start(venue, container.graphStatus(venue).graphOrNull, destination)
        ui.update { Ui(destination = destination) }
    }

    fun pause() = navigation.pause()
    fun resume() = navigation.resume()

    fun finish() {
        navigation.stop()
        ui.update { it.copy(showArrival = false) }
    }

    fun chooseAnotherDestination() {
        viewModelScope.launch {
            val venue = container.venueRepository.getActiveVenue()
            val current = ui.value.destination?.id
            val options = container.destinationRepository.observeDestinations(venue.id).first()
                .filter { it.isValidFor(venue) && it.id != current }
            ui.update { it.copy(showArrival = false, choosingNext = true, alternatives = options) }
        }
    }

    fun dismissChooser() = ui.update { it.copy(choosingNext = false) }

    fun navigateTo(destination: Destination) {
        ui.update { it.copy(choosingNext = false) }
        viewModelScope.launch { startNavigation(destination.id) }
    }

    override fun onCleared() {
        navigation.stop()
        controller.close()
    }
}
