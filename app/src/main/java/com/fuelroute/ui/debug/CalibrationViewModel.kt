package com.fuelroute.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.fuel.CalibrationFitter
import com.fuelroute.domain.fuel.FuelModelOverrides
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalibrationUiState(
    val isLoaded: Boolean = false,
    val slowFactor: String = "",
    val jamFactor: String = "",
    val stopGoWeight: String = "",
    val coldStartDefaultL: String = "",
    val idleLphDefault: String = "",
    val fuelCorrection: String = "",
    val isFitting: Boolean = false,
    val suggestedFactor: Double? = null,
    val currentMape: Double? = null,
    val afterMape: Double? = null,
    val pairCount: Int = 0,
    val message: Int? = null,
)

/**
 * Backs the debug calibration screen: edits [FuelModelOverrides] persisted in
 * [SettingsRepository], and fits a single global correction factor against linked drives.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveHistoryRepository: DriveHistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = fromOverrides(settingsRepository.modelOverrides.first())
        }
    }

    fun onSlowFactorChange(value: String) = updateField(value) { it.copy(slowFactor = value) }
    fun onJamFactorChange(value: String) = updateField(value) { it.copy(jamFactor = value) }
    fun onStopGoWeightChange(value: String) = updateField(value) { it.copy(stopGoWeight = value) }
    fun onColdStartChange(value: String) = updateField(value) { it.copy(coldStartDefaultL = value) }
    fun onIdleLphChange(value: String) = updateField(value) { it.copy(idleLphDefault = value) }
    fun onFuelCorrectionChange(value: String) = updateField(value) { it.copy(fuelCorrection = value) }

    /** Clears every override so the model falls back to `ModelConstants`. */
    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.saveModelOverrides(FuelModelOverrides.DEFAULT)
            _state.update { it.copy(message = RESET_MESSAGE) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Fits a global fuel correction over linked drives and reports before/after MAPE. */
    fun fit() {
        viewModelScope.launch {
            _state.update { it.copy(isFitting = true) }
            val history = driveHistoryRepository.recent()
            val pairs = history.entries.mapNotNull { entry ->
                val predicted = entry.predictedLiters
                val actual = entry.actualLiters
                if (predicted != null && actual != null && predicted > 0.0 && actual > 0.0) {
                    predicted to actual
                } else {
                    null
                }
            }
            val factor = CalibrationFitter.fitCorrection(pairs)
            _state.update {
                it.copy(
                    isFitting = false,
                    suggestedFactor = factor,
                    currentMape = if (factor == null) null else CalibrationFitter.suggestedMape(pairs, 1.0),
                    afterMape = if (factor == null) null else CalibrationFitter.suggestedMape(pairs, factor),
                    pairCount = pairs.size,
                )
            }
        }
    }

    /** Persists the fitted factor as the [FuelModelOverrides.fuelCorrection] override. */
    fun applySuggested() {
        val factor = _state.value.suggestedFactor ?: return
        viewModelScope.launch {
            val overrides = currentOverrides().copy(fuelCorrection = factor)
            settingsRepository.saveModelOverrides(overrides)
            _state.update {
                it.copy(fuelCorrection = format(factor), message = APPLIED_MESSAGE)
            }
        }
    }

    private fun updateField(
        value: String,
        transform: (CalibrationUiState) -> CalibrationUiState,
    ) {
        _state.update(transform)
        viewModelScope.launch { settingsRepository.saveModelOverrides(currentOverrides()) }
    }

    private fun currentOverrides() = FuelModelOverrides(
        slowFactor = _state.value.slowFactor.toDoubleOrNull(),
        jamFactor = _state.value.jamFactor.toDoubleOrNull(),
        stopGoWeight = _state.value.stopGoWeight.toDoubleOrNull(),
        coldStartDefaultL = _state.value.coldStartDefaultL.toDoubleOrNull(),
        idleLphDefault = _state.value.idleLphDefault.toDoubleOrNull(),
        fuelCorrection = _state.value.fuelCorrection.toDoubleOrNull(),
    )

    private fun fromOverrides(overrides: FuelModelOverrides) = CalibrationUiState(
        isLoaded = true,
        slowFactor = format(overrides.effectiveSlowFactor),
        jamFactor = format(overrides.effectiveJamFactor),
        stopGoWeight = format(overrides.effectiveStopGoWeight),
        coldStartDefaultL = format(overrides.effectiveColdStartDefaultL),
        idleLphDefault = format(overrides.effectiveIdleLphDefault),
        fuelCorrection = format(overrides.effectiveFuelCorrection),
    )

    companion object {
        val RESET_MESSAGE = com.fuelroute.R.string.calibration_reset_done
        val APPLIED_MESSAGE = com.fuelroute.R.string.calibration_applied

        private fun format(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}