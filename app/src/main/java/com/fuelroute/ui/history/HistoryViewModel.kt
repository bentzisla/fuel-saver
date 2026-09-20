package com.fuelroute.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.history.DriveHistoryEntry
import com.fuelroute.data.history.DriveHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val entries: List<DriveHistoryEntry> = emptyList(),
    val accuracyPct: Double? = null,
    val isLoading: Boolean = true,
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
            _state.value = HistoryUiState(
                entries = history.entries,
                accuracyPct = history.accuracyPct,
                isLoading = false,
            )
        }
    }
}