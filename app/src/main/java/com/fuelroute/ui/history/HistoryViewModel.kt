package com.fuelroute.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.history.LinkableSearch
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.history.ManualCostInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of a manual "קשר נסיעה" attempt, surfaced as a snackbar-style message. */
enum class LinkFeedback { LINKED, NONE_FOUND }

/** Transient result of a merge/split action, shown inline like [LinkFeedback]. */
enum class MergeSplitFeedback { MERGED, SPLIT, FAILED }

/** A pending split, awaiting the destructive-write confirmation. */
data class SplitRequest(val tripId: Long, val splitAtMs: Long)

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
    /** The ride whose detail dialog is open, or null when no dialog is shown. */
    val selectedEntry: DriveHistoryEntry? = null,
    /** The entry awaiting delete confirmation, or null when the confirm dialog is hidden. */
    val pendingDelete: DriveHistoryEntry? = null,
    /** Current fuel price per liter, used to preview a manual cost entry. */
    val pricePerLiter: Double = 0.0,
    /** True while the History list is in multi-select mode. */
    val selectionMode: Boolean = false,
    /** Stable selection keys ([DriveHistoryEntry.selectionId]) of the checked rows. */
    val selectedIds: Set<Long> = emptySet(),
    /** True while the bulk-delete confirmation is shown. */
    val pendingBulkDelete: Boolean = false,
    /** The ride whose manual-cost dialog is open, or null. */
    val manualEntryEntry: DriveHistoryEntry? = null,
    /** The ride whose merge/split dialog is open, or null. */
    val mergeSplitEntry: DriveHistoryEntry? = null,
    /** Trip ids awaiting the merge confirmation, or null. */
    val pendingMerge: List<Long>? = null,
    /** The split awaiting confirmation, or null. */
    val pendingSplit: SplitRequest? = null,
    /** Transient merge/split result, shown inline and cleared after a moment. */
    val mergeSplitFeedback: MergeSplitFeedback? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DriveHistoryRepository,
    private val vehicleRepository: VehicleRepository,
    private val fuelPriceRepository: FuelPriceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /** Active vehicle the history is scoped to; reloads whenever it changes. */
    private val activeVehicleId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            vehicleRepository.vehicles().collect { list ->
                val current = list.firstOrNull { it.id == activeVehicleId.value } ?: list.firstOrNull()
                if (current != null && current.id != activeVehicleId.value) {
                    activeVehicleId.value = current.id
                }
            }
        }
        viewModelScope.launch {
            val active = vehicleRepository.active()
            if (active.id != activeVehicleId.value) activeVehicleId.value = active.id
        }
        viewModelScope.launch {
            activeVehicleId.collect { id ->
                if (id != null) load()
            }
        }
    }

    /** Switches the active vehicle; History reloads for that vehicle. */
    fun selectVehicle(id: String) {
        viewModelScope.launch {
            vehicleRepository.setActive(id)
            activeVehicleId.value = vehicleRepository.active().id
        }
    }

    fun load() {
        val vehicleId = activeVehicleId.value ?: return
        viewModelScope.launch {
            val history = repository.recent(vehicleId)
            val vehicle = vehicleRepository.active()
            val price = fuelPriceRepository.current(vehicle.grade).pricePerLiter
            val liveKeys = history.entries.mapNotNull { it.selectionId }.toSet()
            _state.value = _state.value.copy(
                entries = history.entries,
                accuracyPct = history.accuracyPct,
                isLoading = false,
                pricePerLiter = price,
                selectedIds = _state.value.selectedIds.intersect(liveKeys),
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
            val vehicleId = activeVehicleId.value ?: return@launch
            val candidates = repository.linkCandidates(vehicleId)
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

    /** Opens the detail dialog for the tapped ride. */
    fun openDetail(entry: DriveHistoryEntry) {
        _state.value = _state.value.copy(selectedEntry = entry)
    }

    /** Closes the detail dialog. */
    fun dismissDetail() {
        _state.value = _state.value.copy(selectedEntry = null)
    }

    /** Asks for confirmation before deleting [entry]; the dialog is shown until confirmed/cancelled. */
    fun requestDelete(entry: DriveHistoryEntry) {
        _state.value = _state.value.copy(pendingDelete = entry)
    }

    /** Confirms the pending delete, removes the underlying rows, and reloads History. */
    fun confirmDelete() {
        val entry = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            repository.delete(entry)
            _state.value = _state.value.copy(
                pendingDelete = null,
                selectedEntry = _state.value.selectedEntry?.takeUnless { it == entry },
            )
            load()
        }
    }

    /** Dismisses the delete confirmation without deleting anything. */
    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    fun clearLinkFeedback() {
        _state.value = _state.value.copy(linkFeedback = null)
    }

    // --- Manual post-drive cost entry ---------------------------------------

    /**
     * Opens the manual-cost dialog for [entry]. Works for any ride ([DriveHistoryEntry.canEnterManualCost]),
     * including an undriven search (no trip yet) and a driven ride whose OBD cost the user wants
     * to correct.
     */
    fun openManualCost(entry: DriveHistoryEntry) {
        if (!entry.canEnterManualCost) return
        _state.value = _state.value.copy(manualEntryEntry = entry, selectedEntry = null)
    }

    fun dismissManualCost() {
        _state.value = _state.value.copy(manualEntryEntry = null)
    }

    /**
     * Saves the manual entry (validated by the dialog via [ManualCostCalculator]) and reloads.
     * See [DriveHistoryRepository.recordManualCost] for how an undriven search gets a trip of
     * its own to hold the entry.
     */
    fun saveManualCost(input: ManualCostInput) {
        val entry = _state.value.manualEntryEntry ?: return
        val vehicleId = activeVehicleId.value ?: return
        viewModelScope.launch {
            repository.recordManualCost(
                vehicleId = vehicleId,
                entry = entry,
                cost = input.cost,
                distanceKm = input.distanceKm,
                litersPer100Km = input.litersPer100Km,
                fallbackPricePerLiter = _state.value.pricePerLiter,
            )
            _state.value = _state.value.copy(manualEntryEntry = null)
            load()
        }
    }

    // --- Bulk delete --------------------------------------------------------

    /** Toggles multi-select mode, clearing any stale selection when leaving it. */
    fun toggleSelectionMode() {
        _state.value = if (_state.value.selectionMode) {
            _state.value.copy(selectionMode = false, selectedIds = emptySet())
        } else {
            _state.value.copy(selectionMode = true)
        }
    }

    /** Checks/unchecks one row in multi-select mode. */
    fun toggleSelection(entry: DriveHistoryEntry) {
        val key = entry.selectionId ?: return
        val current = _state.value.selectedIds
        _state.value = _state.value.copy(
            selectedIds = if (key in current) current - key else current + key,
        )
    }

    fun requestBulkDelete() {
        if (_state.value.selectedIds.isEmpty()) return
        _state.value = _state.value.copy(pendingBulkDelete = true)
    }

    fun cancelBulkDelete() {
        _state.value = _state.value.copy(pendingBulkDelete = false)
    }

    /** Confirms the bulk delete, removes every selected row, and leaves selection mode. */
    fun confirmBulkDelete() {
        val selected = _state.value.selectedIds
        val entries = _state.value.entries.filter { it.selectionId != null && it.selectionId in selected }
        viewModelScope.launch {
            repository.deleteMany(entries)
            _state.value = _state.value.copy(
                pendingBulkDelete = false,
                selectionMode = false,
                selectedIds = emptySet(),
            )
            load()
        }
    }

    // --- Merge / split ------------------------------------------------------

    /** Opens the merge/split dialog for [entry]. */
    fun openMergeSplit(entry: DriveHistoryEntry) {
        if (entry.tripId == null) return
        _state.value = _state.value.copy(mergeSplitEntry = entry, selectedEntry = null)
    }

    fun dismissMergeSplit() {
        _state.value = _state.value.copy(mergeSplitEntry = null)
    }

    /** Stages a merge of [ids] for confirmation. */
    fun requestMerge(ids: List<Long>) {
        if (ids.size < 2) return
        _state.value = _state.value.copy(pendingMerge = ids)
    }

    fun cancelMerge() {
        _state.value = _state.value.copy(pendingMerge = null)
    }

    /** Confirms the staged merge and reloads History. */
    fun confirmMerge() {
        val ids = _state.value.pendingMerge ?: return
        viewModelScope.launch {
            val newId = repository.mergeTrips(ids)
            _state.value = _state.value.copy(
                pendingMerge = null,
                mergeSplitEntry = null,
                mergeSplitFeedback = if (newId != null) {
                    MergeSplitFeedback.MERGED
                } else {
                    MergeSplitFeedback.FAILED
                },
            )
            load()
        }
    }

    /** Stages a split of [tripId] at [splitAtMs] for confirmation. */
    fun requestSplit(tripId: Long, splitAtMs: Long) {
        _state.value = _state.value.copy(pendingSplit = SplitRequest(tripId, splitAtMs))
    }

    fun cancelSplit() {
        _state.value = _state.value.copy(pendingSplit = null)
    }

    /** Confirms the staged split and reloads History. */
    fun confirmSplit() {
        val request = _state.value.pendingSplit ?: return
        viewModelScope.launch {
            val result = repository.splitTrip(request.tripId, request.splitAtMs)
            _state.value = _state.value.copy(
                pendingSplit = null,
                mergeSplitEntry = null,
                mergeSplitFeedback = if (result != null) {
                    MergeSplitFeedback.SPLIT
                } else {
                    MergeSplitFeedback.FAILED
                },
            )
            load()
        }
    }

    fun clearMergeSplitFeedback() {
        _state.value = _state.value.copy(mergeSplitFeedback = null)
    }
}
