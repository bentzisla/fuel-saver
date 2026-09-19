package com.fuelroute.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.VehicleProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class VehicleUiState(
    val isLoaded: Boolean = false,
    val id: String = "",
    val name: String = "",
    val fuelType: FuelType = FuelType.GASOLINE,
    val ratedCombined: String = "",
    val displacement: String = "",
    val tankCapacity: String = "",
    val saved: Boolean = false,
)

@HiltViewModel
class VehicleViewModel @Inject constructor(
    private val repository: VehicleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.profile.first().let { profile ->
                _uiState.update {
                    it.copy(
                        isLoaded = true,
                        id = profile.id,
                        name = profile.name,
                        fuelType = profile.fuelType,
                        ratedCombined = profile.ratedCombinedL100.toString(),
                        displacement = profile.engineDisplacementL?.toString().orEmpty(),
                        tankCapacity = profile.tankCapacityL?.toString().orEmpty(),
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, saved = false) }

    fun onFuelTypeSelect(value: FuelType) = _uiState.update { it.copy(fuelType = value, saved = false) }

    fun onRatedCombinedChange(value: String) = _uiState.update { it.copy(ratedCombined = value, saved = false) }

    fun onDisplacementChange(value: String) = _uiState.update { it.copy(displacement = value, saved = false) }

    fun onTankCapacityChange(value: String) = _uiState.update { it.copy(tankCapacity = value, saved = false) }

    fun save() {
        val state = _uiState.value
        val normalized = state.copy(
            name = state.name.trim(),
            ratedCombined = state.ratedCombined.trim(),
            displacement = state.displacement.trim(),
            tankCapacity = state.tankCapacity.trim(),
        )
        _uiState.value = normalized

        val profile = VehicleProfile(
            id = normalized.id.ifBlank { UUID.randomUUID().toString() },
            name = normalized.name,
            fuelType = normalized.fuelType,
            ratedCombinedL100 = normalized.ratedCombined.toDoubleOrNull() ?: DEFAULT_RATED_L100,
            engineDisplacementL = normalized.displacement.toDoubleOrNull(),
            tankCapacityL = normalized.tankCapacity.toDoubleOrNull(),
        )
        viewModelScope.launch {
            repository.save(profile)
            _uiState.update {
                it.copy(id = profile.id, name = profile.name, saved = true)
            }
        }
    }

    private companion object {
        const val DEFAULT_RATED_L100 = 7.0
    }
}