package com.fuelroute.ui.refuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.refuel.RefuelRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.model.Refuel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_VEHICLE_ID = "default"

data class RefuelUiState(
    val liters: String = "",
    val totalPrice: String = "",
    val isFull: Boolean = true,
    val refuels: List<Refuel> = emptyList(),
    val correction: Double = 1.0,
    val saved: Boolean = false,
)

@HiltViewModel
class RefuelViewModel @Inject constructor(
    private val refuelRepository: RefuelRepository,
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RefuelUiState())
    val state: StateFlow<RefuelUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val vehicle = vehicleRepository.profile.first()
            _state.update {
                it.copy(
                    refuels = refuelRepository.recent(20),
                    correction = vehicle.fuelRateCorrection,
                )
            }
        }
    }

    fun onLitersChange(value: String) = _state.update { it.copy(liters = value, saved = false) }
    fun onPriceChange(value: String) = _state.update { it.copy(totalPrice = value, saved = false) }
    fun onFullChange(value: Boolean) = _state.update { it.copy(isFull = value) }

    fun save() {
        val liters = _state.value.liters.trim().toDoubleOrNull()
        val price = _state.value.totalPrice.trim().toDoubleOrNull()
        if (liters == null || liters <= 0.0 || price == null || price < 0.0) return

        viewModelScope.launch {
            val vehicle = vehicleRepository.profile.first()
            val vehicleId = vehicle.id.ifBlank { DEFAULT_VEHICLE_ID }
            refuelRepository.add(liters, price, _state.value.isFull, vehicleId)

            if (_state.value.isFull) {
                settingsRepository.saveFuelPrice(price / liters)
                val totalPumped = refuelRepository.totalFullLiters(vehicleId)
                val totalObd = refuelRepository.totalObdFuel(vehicleId)
                if (totalObd >= 1.0) {
                    val correction = (totalPumped / totalObd).coerceIn(0.7, 1.4)
                    vehicleRepository.updateFuelRateCorrection(correction)
                }
            }

            val updatedCorrection = vehicleRepository.profile.first().fuelRateCorrection
            val refuels = refuelRepository.recent(20)
            _state.update {
                it.copy(
                    liters = "",
                    totalPrice = "",
                    refuels = refuels,
                    correction = updatedCorrection,
                    saved = true,
                )
            }
        }
    }
}