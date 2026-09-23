package com.fuelroute.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.history.DriveHistoryRepository
import com.fuelroute.data.refuel.FullRefuelInterval
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.CalibrationFitter
import com.fuelroute.domain.fuel.FuelModelOverrides
import com.fuelroute.domain.learning.Calibration
import com.fuelroute.domain.learning.RefuelCalibrator
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
    /** True when the initial load failed; the screen then offers a retry instead of blanking. */
    val error: Boolean = false,
    /**
     * Raw persisted override text per field. Blank means "no override" (use the model
     * default); the screen derives the effective value as `text.toDoubleOrNull() ?: default`.
     */
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
    /** Override text as it was before the last apply, for one-tap rollback. */
    val previousFuelCorrection: String? = null,
    // Refuel (tank-to-tank) calibration, shown alongside the drive-fit result.
    val refuelCorrection: Double = 1.0,
    val refuelInterval: FullRefuelInterval? = null,
    val refuelCalibration: Calibration? = null,
)

/**
 * Backs the guided calibration screen (card 33): edits [FuelModelOverrides] persisted in
 * [SettingsRepository], fits a single global correction factor against linked drives, and
 * surfaces the last tank-to-tank [RefuelCalibrator] result. The fit/refuel math itself stays
 * in `domain/`; this only orchestrates and exposes it.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveHistoryRepository: DriveHistoryRepository,
    private val vehicleRepository: VehicleRepository,
    private val refuelRepository: RefuelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Loads overrides, linked drives and refuel calibration; retryable from the screen. */
    fun load() {
        _state.update { it.copy(isLoaded = false, error = false) }
        viewModelScope.launch {
            runCatching {
                val overrides = settingsRepository.modelOverrides.first()
                val pairs = linkedPairs()
                val currentCorrection = overrides.effectiveFuelCorrection
                val uncorrected = CalibrationFitter.uncorrectedPairs(pairs, currentCorrection)
                _state.value = fromOverrides(overrides).copy(
                    pairCount = pairs.size,
                    currentMape = if (pairs.isEmpty()) {
                        null
                    } else {
                        CalibrationFitter.suggestedMape(uncorrected, currentCorrection)
                    },
                )
                loadRefuelCalibration()
            }.onFailure {
                // Never leave the guided flow invisible (isLoaded stays false) on a failure.
                _state.update { it.copy(isLoaded = true, error = true) }
            }
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
            _state.update {
                it.copy(
                    slowFactor = "",
                    jamFactor = "",
                    stopGoWeight = "",
                    coldStartDefaultL = "",
                    idleLphDefault = "",
                    fuelCorrection = "",
                    suggestedFactor = null,
                    afterMape = null,
                    previousFuelCorrection = null,
                    message = RESET_MESSAGE,
                )
            }
        }
    }

    /**
     * Applies the "recommended" preset: factory defaults for every congestion/idle constant
     * plus the latest fitted fuel correction.
     */
    fun applyRecommendedPreset() {
        val factor = _state.value.suggestedFactor ?: return
        viewModelScope.launch {
            settingsRepository.saveModelOverrides(FuelModelOverrides(fuelCorrection = factor))
            _state.update {
                it.copy(
                    slowFactor = "",
                    jamFactor = "",
                    stopGoWeight = "",
                    coldStartDefaultL = "",
                    idleLphDefault = "",
                    fuelCorrection = format(factor),
                    previousFuelCorrection = null,
                    message = RECOMMENDED_MESSAGE,
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Fits a global fuel correction over linked drives and reports before/after MAPE. */
    fun fit() {
        viewModelScope.launch {
            _state.update { it.copy(isFitting = true) }
            val currentCorrection = _state.value.fuelCorrection.toDoubleOrNull() ?: 1.0
            // Stored predictions already include the active correction; undo it before fitting so
            // the result is an absolute correction rather than a compounding one.
            val uncorrected = CalibrationFitter.uncorrectedPairs(linkedPairs(), currentCorrection)
            val factor = CalibrationFitter.fitCorrection(uncorrected)
            _state.update {
                it.copy(
                    isFitting = false,
                    suggestedFactor = factor,
                    currentMape = if (factor == null) {
                        null
                    } else {
                        CalibrationFitter.suggestedMape(uncorrected, currentCorrection)
                    },
                    afterMape = if (factor == null) null else CalibrationFitter.suggestedMape(uncorrected, factor),
                    pairCount = uncorrected.size,
                )
            }
        }
    }

    /** Discards a suggested factor without persisting anything. */
    fun discardSuggested() = _state.update { it.copy(suggestedFactor = null, afterMape = null) }

    /** Persists the fitted factor as the [FuelModelOverrides.fuelCorrection] override. */
    fun applySuggested() {
        val factor = _state.value.suggestedFactor ?: return
        viewModelScope.launch {
            val previous = _state.value.fuelCorrection
            val overrides = currentOverrides().copy(fuelCorrection = factor)
            settingsRepository.saveModelOverrides(overrides)
            _state.update {
                it.copy(
                    fuelCorrection = format(factor),
                    previousFuelCorrection = previous,
                    message = APPLIED_MESSAGE,
                )
            }
        }
    }

    /** Restores the correction that was active before the last apply. */
    fun rollback() {
        val previous = _state.value.previousFuelCorrection ?: return
        viewModelScope.launch {
            val overrides = currentOverrides().copy(fuelCorrection = previous.toDoubleOrNull())
            settingsRepository.saveModelOverrides(overrides)
            _state.update {
                it.copy(
                    fuelCorrection = previous,
                    previousFuelCorrection = null,
                    message = ROLLBACK_MESSAGE,
                )
            }
        }
    }

    private suspend fun linkedPairs(): List<Pair<Double, Double>> =
        driveHistoryRepository.recent(vehicleRepository.active().id).entries.mapNotNull { entry ->
            val predicted = entry.predictedLiters
            val actual = entry.actualLiters
            if (predicted != null && actual != null && predicted > 0.0 && actual > 0.0) {
                predicted to actual
            } else {
                null
            }
        }

    private suspend fun loadRefuelCalibration() {
        val vehicle = vehicleRepository.active()
        val interval = refuelRepository.lastFullInterval(vehicle.id)
        val calibration = interval?.let {
            RefuelCalibrator.calibrate(it.pumpedLitres, it.obdLitres)
        }
        _state.update {
            it.copy(
                refuelCorrection = vehicle.fuelRateCorrection,
                refuelInterval = interval,
                refuelCalibration = calibration,
            )
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

    /** Encodes only the explicit overrides; unset fields stay blank so defaults apply. */
    private fun fromOverrides(overrides: FuelModelOverrides) = CalibrationUiState(
        isLoaded = true,
        slowFactor = overrides.slowFactor?.let(::format).orEmpty(),
        jamFactor = overrides.jamFactor?.let(::format).orEmpty(),
        stopGoWeight = overrides.stopGoWeight?.let(::format).orEmpty(),
        coldStartDefaultL = overrides.coldStartDefaultL?.let(::format).orEmpty(),
        idleLphDefault = overrides.idleLphDefault?.let(::format).orEmpty(),
        fuelCorrection = overrides.fuelCorrection?.let(::format).orEmpty(),
    )

    companion object {
        val RESET_MESSAGE = com.fuelroute.R.string.calibration_reset_done
        val APPLIED_MESSAGE = com.fuelroute.R.string.calibration_applied
        val ROLLBACK_MESSAGE = com.fuelroute.R.string.calibration_rollback_done
        val RECOMMENDED_MESSAGE = com.fuelroute.R.string.calibration_preset_recommended_done

        private fun format(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}