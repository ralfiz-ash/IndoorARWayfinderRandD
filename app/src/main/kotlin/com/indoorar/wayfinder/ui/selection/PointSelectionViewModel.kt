package com.indoorar.wayfinder.ui.selection

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.arcore.ArFailure
import com.indoorar.wayfinder.arcore.ArFrameSnapshot
import com.indoorar.wayfinder.arcore.ArSessionController
import com.indoorar.wayfinder.arcore.PointSelectionLayer
import com.indoorar.wayfinder.arcore.ReticleState
import com.indoorar.wayfinder.arcore.SavedDestinationsLayer
import com.indoorar.wayfinder.arcore.SelectedPoint
import com.indoorar.wayfinder.arcore.TapResult
import com.indoorar.wayfinder.domain.destination.DestinationError
import com.indoorar.wayfinder.domain.destination.DestinationResult
import com.indoorar.wayfinder.domain.destination.DestinationService
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.ui.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SelectionUiState(
    val snapshot: ArFrameSnapshot = ArFrameSnapshot(),
    val reticle: ReticleState = ReticleState(),
    val selected: SelectedPoint? = null,
    val failure: ArFailure? = null,
    val name: String = "",
    val nameError: String? = null,
    val saving: Boolean = false,
    /** Transient feedback for the last tap. */
    val hint: String? = null,
    val notLocalizedSeconds: Double = 0.0,
    val noFloorSeconds: Double = 0.0,
    /** Saved destinations of the current frame version. */
    val savedCount: Int = 0,
) {
    val canSave: Boolean
        get() = selected != null && selected.anchorTracking && selected.localization.isReliable && !saving
}

/** Tracks how long the user has been stuck in a guidance stage, from snapshot timestamps. */
internal class StageTimer(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var notLocalizedSince: Long? = null
    private var noFloorSince: Long? = null

    fun update(snapshot: ArFrameSnapshot): Pair<Double, Double> {
        val t = now()
        val localized = snapshot.localization is LocalizationStatus.Localized
        notLocalizedSince = if (localized) null else notLocalizedSince ?: t
        noFloorSince = if (!localized || snapshot.floorPlaneDetected) null else noFloorSince ?: t
        return seconds(notLocalizedSince, t) to seconds(noFloorSince, t)
    }

    private fun seconds(since: Long?, t: Long) = since?.let { (t - it) / 1000.0 } ?: 0.0
}

private data class FormState(val name: String = "", val nameError: String? = null, val saving: Boolean = false, val hint: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class PointSelectionViewModel(
    application: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val service: DestinationService = container.destinationService

    val marker = container.venueConfig.marker
    private val selection = PointSelectionLayer()
    private val savedLayer = SavedDestinationsLayer()
    val controller = ArSessionController(application, marker).apply {
        addLayer(savedLayer)
        addLayer(selection)
    }
    private val stageTimer = StageTimer()
    private val savedCount = MutableStateFlow(0)

    private val form = MutableStateFlow(FormState())
    private val saved = Channel<String>(Channel.BUFFERED)
    val savedEvents = saved.receiveAsFlow()

    val state: StateFlow<SelectionUiState> = combine(
        combine(controller.snapshot, selection.reticle, selection.selected, ::Triple),
        controller.failure,
        form,
        savedCount,
    ) { (snapshot, reticle, selected), failure, f, count ->
        val (notLocalized, noFloor) = stageTimer.update(snapshot)
        SelectionUiState(snapshot, reticle, selected, failure, f.name, f.nameError, f.saving, f.hint, notLocalized, noFloor, count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionUiState())

    init {
        viewModelScope.launch {
            selection.tapResults.collect { result ->
                val hint = when (result) {
                    is TapResult.Accepted -> null
                    is TapResult.Rejected -> UiText.rejection(result.reason)
                    TapResult.NotLocalized -> "Scan the venue marker first, so the point can be saved relative to it."
                }
                form.update { it.copy(hint = hint) }
            }
        }
        viewModelScope.launch {
            controller.snapshot.collect { container.lastLocalization.value = it.localization }
        }
        viewModelScope.launch {
            container.venueRepository.observeActiveVenue()
                .flatMapLatest { venue ->
                    container.destinationRepository.observeDestinations(venue.id).map { venue to it }
                }
                .collect { (venue, destinations) ->
                    val current = destinations.filter { it.isValidFor(venue) }
                    savedLayer.update(venue, current)
                    savedCount.value = current.size
                }
        }
    }

    fun onTap(x: Float, y: Float) {
        if (state.value.selected == null) selection.tap(x, y)
    }

    fun onNameChange(name: String) = form.update { it.copy(name = name, nameError = null) }

    fun retry() {
        selection.clearSelection()
        form.update { FormState(name = it.name) }
    }

    fun dismissHint() = form.update { it.copy(hint = null) }

    fun save() {
        val current = state.value
        val point = current.selected ?: return
        if (!current.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val venue = container.venueRepository.getActiveVenue()
            // Session position and localization come from the same AR frame; the service converts
            // to the venue frame and refuses to save unless that localization is reliable.
            val result = try {
                service.create(
                    name = current.name,
                    pointInSession = point.positionInSession,
                    captureHeadingInSession = point.headingInSession,
                    captureSource = point.source,
                    localization = point.localization,
                    venue = venue,
                )
            } catch (e: SQLiteConstraintException) {
                // Lost a race with another save of the same name; the database rejected it.
                DestinationResult.Failure(DestinationError.DuplicateName)
            }
            when (result) {
                is DestinationResult.Success -> {
                    selection.clearSelection()
                    form.value = FormState()
                    saved.send(
                        "${result.value.name} (±${UiText.meters(result.value.estimatedErrorMeters)} relative to the marker)",
                    )
                }
                is DestinationResult.Failure -> form.update {
                    it.copy(saving = false, nameError = errorText(result.error))
                }
            }
        }
    }

    private fun errorText(error: DestinationError) = when (error) {
        DestinationError.BlankName -> "Enter a name"
        is DestinationError.NameTooLong -> "Use at most ${error.max} characters"
        DestinationError.DuplicateName -> "A destination with this name already exists"
        DestinationError.NotLocalized -> "Not localized: scan the venue marker again"
        DestinationError.LocalizationUnreliable -> "Position too uncertain: rescan the marker, then retry"
        DestinationError.NotFound -> "Destination not found"
    }

    override fun onCleared() {
        controller.close()
    }
}
