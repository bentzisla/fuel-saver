package com.fuelroute.ui.refuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.RangeEstimator
import com.fuelroute.domain.learning.Calibration
import com.fuelroute.domain.learning.RefuelCalibrator
import com.fuelroute.domain.model.Refuel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A tank-to-tank calibration that hit the [RefuelCalibrator] bounds. It is not applied until the
 * user confirms: a clamped result usually means drives without the dongle or a missed full fill.
 */
data class ClampedCalibration(
    val factor: Double,
    val pumpedLitres: Double,
    val obdLitres: Double,
)

/** The OBD logged too little of the pumped fuel to calibrate; shown, never applied. */
data class LowCoverageNotice(
    val pumpedLitres: Double,
    val obdLitres: Double,
)

data class RefuelUiState(
    val liters: String = "",
    val totalPrice: String = "",
    val isFull: Boolean = true,
    val refuels: List<Refuel> = emptyList(),
    val correction: Double = 1.0,
    val saved: Boolean = false,
    /** A clamped calibration waiting for the user's confirmation (dialog). */
    val pendingClamped: ClampedCalibration? = null,
    val lowCoverage: LowCoverageNotice? = null,
    val tankCapacityL: Double? = null,
    val showTankWarning: Boolean = false,
)

@HiltViewModel
class RefuelViewModel @Inject constructor(
    private val refuelRepository: RefuelRepository,
    private val vehicleRepository: VehicleRepository,
    private val fuelPriceRepository: FuelPriceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RefuelUiState())
    val state: StateFlow<RefuelUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            _state.update {
                it.copy(
                    refuels = refuelRepository.recent(vehicle.id, 20),
                    correction = vehicle.fuelRateCorrection,
                    tankCapacityL = vehicle.tankCapacityL,
                )
            }
        }
    }

    fun onLitersChange(value: String) =
        _state.update { it.copy(liters = value, saved = false, lowCoverage = null, showTankWarning = false) }

    fun onPriceChange(value: String) =
        _state.update { it.copy(totalPrice = value, saved = false, lowCoverage = null) }

    fun onFullChange(value: Boolean) = _state.update { it.copy(isFull = value, lowCoverage = null) }

    fun dismissTankWarning() = _state.update { it.copy(showTankWarning = false) }

    /** Confirms the over-capacity warning and saves as-is. */
    fun confirmTankWarning() = save(confirmed = true)

    /** The user accepts the clamped calibration: persist it. */
    fun applyClampedCalibration() {
        val pending = _state.value.pendingClamped ?: return
        _state.update { it.copy(pendingClamped = null) }
        viewModelScope.launch {
            vehicleRepository.updateFuelRateCorrection(pending.factor)
            val updated = vehicleRepository.active().fuelRateCorrection
            _state.update { it.copy(correction = updated) }
        }
    }

    /** The user rejects the clamped calibration: the current correction stays. */
    fun discardClampedCalibration() = _state.update { it.copy(pendingClamped = null) }

    fun save(confirmed: Boolean = false) {
        val state = _state.value
        val liters = state.liters.trim().toDoubleOrNull()
        val price = state.totalPrice.trim().toDoubleOrNull()
        if (liters == null || liters <= 0.0 || price == null || price < 0.0) return

        if (!confirmed && RangeEstimator.exceedsTankCapacity(liters, state.tankCapacityL)) {
            _state.update { it.copy(showTankWarning = true) }
            return
        }

        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            val vehicleId = vehicle.id
            // The correction the trips since the previous full fill were logged with.
            val activeCorrection = vehicle.fuelRateCorrection
            refuelRepository.add(liters, price, state.isFull, vehicleId, vehicle.grade, activeCorrection)

            var pendingClamped: ClampedCalibration? = null
            var lowCoverage: LowCoverageNotice? = null
            if (state.isFull) {
                // A full refuel refreshes the observed price for the vehicle's grade,
                // unless the user pinned it.
                fuelPriceRepository.onFullRefuel(vehicle.grade, price / liters)
                // Calibrate against the tank-to-tank interval, not lifetime totals.
                val interval = refuelRepository.lastFullInterval(vehicleId)
                if (interval != null) {
                    val calibration = RefuelCalibrator.calibrate(
                        interval.pumpedLitres,
                        interval.obdLitres,
                        interval.activeCorrection ?: activeCorrection,
                    )
                    when (calibration) {
                        is Calibration.Exact -> vehicleRepository.updateFuelRateCorrection(calibration.factor)
                        is Calibration.Clamped -> pendingClamped =
                            ClampedCalibration(calibration.factor, interval.pumpedLitres, interval.obdLitres)
                        is Calibration.LowCoverage -> lowCoverage =
                            LowCoverageNotice(calibration.pumpedLitres, calibration.obdLitres)
                        Calibration.Insufficient -> Unit
                    }
                }
            }

            val updatedCorrection = vehicleRepository.active().fuelRateCorrection
            val refuels = refuelRepository.recent(vehicleId, 20)
            _state.update {
                it.copy(
                    liters = "",
                    totalPrice = "",
                    refuels = refuels,
                    correction = updatedCorrection,
                    saved = true,
                    pendingClamped = pendingClamped,
                    lowCoverage = lowCoverage,
                    showTankWarning = false,
                )
            }
        }
    }
}
