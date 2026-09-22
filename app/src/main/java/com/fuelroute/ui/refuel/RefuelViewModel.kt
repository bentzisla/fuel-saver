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

data class RefuelUiState(
    val liters: String = "",
    val totalPrice: String = "",
    val isFull: Boolean = true,
    val refuels: List<Refuel> = emptyList(),
    val correction: Double = 1.0,
    val saved: Boolean = false,
    val calibrationClamped: Boolean = false,
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
        _state.update { it.copy(liters = value, saved = false, calibrationClamped = false, showTankWarning = false) }

    fun onPriceChange(value: String) =
        _state.update { it.copy(totalPrice = value, saved = false, calibrationClamped = false) }

    fun onFullChange(value: Boolean) = _state.update { it.copy(isFull = value, calibrationClamped = false) }

    fun dismissTankWarning() = _state.update { it.copy(showTankWarning = false) }

    /** Confirms the over-capacity warning and saves as-is. */
    fun confirmTankWarning() = save(confirmed = true)

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
            refuelRepository.add(liters, price, state.isFull, vehicleId, vehicle.grade)

            var clamped = false
            if (state.isFull) {
                // A full refuel refreshes the observed price for the vehicle's grade,
                // unless the user pinned it.
                fuelPriceRepository.onFullRefuel(vehicle.grade, price / liters)
                // Calibrate against the tank-to-tank interval, not lifetime totals.
                val interval = refuelRepository.lastFullInterval(vehicleId)
                if (interval != null) {
                    when (val calibration = RefuelCalibrator.calibrate(interval.pumpedLitres, interval.obdLitres)) {
                        is Calibration.Exact -> vehicleRepository.updateFuelRateCorrection(calibration.factor)
                        is Calibration.Clamped -> {
                            vehicleRepository.updateFuelRateCorrection(calibration.factor)
                            clamped = true
                        }
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
                    calibrationClamped = clamped,
                    showTankWarning = false,
                )
            }
        }
    }
}