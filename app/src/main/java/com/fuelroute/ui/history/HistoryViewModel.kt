package com.fuelroute.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.history.LinkableSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of a manual "קשר נסיעה" attempt, surfaced as a snackbar-style message. */
enum class LinkFeedback { LINKED, NONE_FOUND }

data class HistoryUiState(
    val entries: List<DriveHistoryEntry> = emptyList(),
    val accuracyPct: Double? = null,
    val isLoading: Boolean = true,
    val linkTargetTripId: Long? = null,
    val linkTargetStartMs: Long = 0L,
    val linkTargetLabel: String? = null,
    val linkCandidates: List<LinkableSearch> = emptyList(),
    val isLoadingCandidates: Boolean = false,
    val linkFeedback: LinkFeedback? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DriveHistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val history = repository.recent()
            _state.value = _state.value.copy(
                entries = history.entries,
                accuracyPct = history.accuracyPct,
                isLoading = false,
            )
        }
    }

    /** Opens the "קשר נסיעה" picker for an unlinked drive. */
    fun openManualLink(entry: DriveHistoryEntry) {
        val tripId = entry.tripId ?: return
        _state.value = _state.value.copy(
            linkTargetTripId = tripId,
            linkTargetStartMs = entry.timestampMs,
            linkTargetLabel = entry.destinationLabel ?: entry.originLabel,
            linkCandidates = emptyList(),
            isLoadingCandidates = true,
        )
        viewModelScope.launch {
            val candidates = repository.linkCandidates()
            _state.value = _state.value.copy(
                linkCandidates = candidates,
                isLoadingCandidates = false,
            )
        }
    }

    fun dismissManualLink() {
        _state.value = _state.value.copy(
            linkTargetTripId = null,
            linkTargetLabel = null,
            linkCandidates = emptyList(),
            isLoadingCandidates = false,
        )
    }

    /** Pairs the picked drive with a specific search. */
    fun linkToSearch(searchId: Long) {
        val tripId = _state.value.linkTargetTripId ?: return
        viewModelScope.launch {
            repository.linkTrip(tripId, searchId)
            dismissManualLink()
            _state.value = _state.value.copy(linkFeedback = LinkFeedback.LINKED)
            load()
        }
    }

    /** One-tap pairing against the best search near the drive's start. */
    fun linkToNearest() {
        val state = _state.value
        val tripId = state.linkTargetTripId ?: return
        viewModelScope.launch {
            val linked = repository.linkTripToNearest(tripId, state.linkTargetStartMs)
            dismissManualLink()
            _state.value = _state.value.copy(
                linkFeedback = if (linked) LinkFeedback.LINKED else LinkFeedback.NONE_FOUND,
            )
            if (linked) load()
        }
    }

    fun clearLinkFeedback() {
        _state.value = _state.value.copy(linkFeedback = null)
    }
}
