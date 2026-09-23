package com.fuelroute.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.learning.EngineDisplacement
import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.VehicleProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class VehicleUiState(
    val isLoaded: Boolean = false,
    /** True when loading the vehicles failed; the screen then offers a retry instead of blank. */
    val error: Boolean = false,
    val vehicles: List<VehicleProfile> = emptyList(),
    val activeId: String? = null,
    val showForm: Boolean = false,
    val editingId: String? = null,
    val name: String = "",
    val fuelType: FuelType = FuelType.GASOLINE,
    val ratedCombined: String = "",
    val displacement: String = "",
    val tankCapacity: String = "",
    val grade: String = FuelGrades.GASOLINE_95,
    val saved: Boolean = false,
    val pendingDelete: VehicleProfile? = null,
)

@HiltViewModel
class VehicleViewModel @Inject constructor(
    private val repository: VehicleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleUiState())
    val uiState: StateFlow<VehicleUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Loads the vehicle list and active vehicle. Re-collectable: the screen calls this from its
     * error-state retry button, which recovers a failed [repository.vehicles] collection.
     */
    fun load() {
        _uiState.update { it.copy(isLoaded = false, error = false) }
        viewModelScope.launch {
            repository.vehicles()
                .catch { _uiState.update { state -> state.copy(isLoaded = true, error = true) } }
                .collect { vehicles ->
                    _uiState.update { state ->
                        state.copy(
                            isLoaded = true,
                            error = false,
                            vehicles = vehicles,
                            activeId = state.activeId ?: vehicles.firstOrNull()?.id,
                        )
                    }
                }
        }
        viewModelScope.launch {
            runCatching { repository.active() }
                .onSuccess { active -> _uiState.update { it.copy(activeId = active.id) } }
                .onFailure { _uiState.update { it.copy(isLoaded = true, error = true) } }
        }
    }

    fun select(id: String) {
        viewModelScope.launch {
            repository.setActive(id)
            _uiState.update { it.copy(activeId = id, saved = false) }
        }
    }

    fun startAdd() = _uiState.update {
        it.copy(
            showForm = true,
            editingId = null,
            name = "",
            fuelType = FuelType.GASOLINE,
            ratedCombined = "",
            displacement = "",
            tankCapacity = "",
            grade = FuelGrades.GASOLINE_95,
            saved = false,
        )
    }

    fun startEdit(profile: VehicleProfile) = _uiState.update {
        it.copy(
            showForm = true,
            editingId = profile.id,
            name = profile.name,
            fuelType = profile.fuelType,
            ratedCombined = profile.ratedCombinedL100.toString(),
            displacement = profile.engineDisplacementL?.toString().orEmpty(),
            tankCapacity = profile.tankCapacityL?.toString().orEmpty(),
            grade = normalizeGrade(profile.fuelType, profile.grade),
            saved = false,
        )
    }

    fun cancelEdit() = _uiState.update { it.copy(showForm = false, editingId = null, saved = false) }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, saved = false) }

    fun onFuelTypeSelect(value: FuelType) = _uiState.update { state ->
        state.copy(
            fuelType = value,
            grade = normalizeGrade(value, state.grade),
            saved = false,
        )
    }

    fun onRatedCombinedChange(value: String) = _uiState.update { it.copy(ratedCombined = value, saved = false) }

    fun onDisplacementChange(value: String) = _uiState.update { it.copy(displacement = value, saved = false) }

    fun onTankCapacityChange(value: String) = _uiState.update { it.copy(tankCapacity = value, saved = false) }

    fun onGradeSelect(value: String) = _uiState.update { it.copy(grade = value, saved = false) }

    fun save() {
        val state = _uiState.value
        val name = state.name.trim()
        val ratedCombined = state.ratedCombined.trim()
        // Litres expected; a value typed in cc (1800) is converted (1.8) and anything outside
        // 0.6-8.0 L is dropped (unknown) — it feeds the speed-density fuel formula directly.
        val displacementLiters = EngineDisplacement.parseLiters(state.displacement)
        val displacement = displacementLiters?.toString().orEmpty()
        val tankCapacity = state.tankCapacity.trim()
        _uiState.value = state.copy(
            name = name,
            ratedCombined = ratedCombined,
            displacement = displacement,
            tankCapacity = tankCapacity,
        )

        val existing = state.vehicles.firstOrNull { it.id == state.editingId }
        val profile = (existing ?: VehicleProfile(id = UUID.randomUUID().toString(), name = name)).copy(
            name = name,
            fuelType = state.fuelType,
            ratedCombinedL100 = ratedCombined.toDoubleOrNull() ?: DEFAULT_RATED_L100,
            engineDisplacementL = displacementLiters,
            tankCapacityL = tankCapacity.toDoubleOrNull(),
            grade = normalizeGrade(state.fuelType, state.grade),
        )
        viewModelScope.launch {
            repository.upsert(profile)
            if (existing == null) repository.setActive(profile.id)
            _uiState.update {
                it.copy(
                    showForm = false,
                    editingId = null,
                    activeId = profile.id,
                    saved = true,
                )
            }
        }
    }

    fun requestDelete(profile: VehicleProfile) = _uiState.update { it.copy(pendingDelete = profile) }

    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val profile = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            repository.delete(profile.id)
            val active = repository.active()
            _uiState.update { it.copy(pendingDelete = null, activeId = active.id) }
        }
    }

    private companion object {
        const val DEFAULT_RATED_L100 = 7.0

        /** Keeps [grade] within the grades allowed for [fuelType], falling back to the first valid one. */
        fun normalizeGrade(fuelType: FuelType, grade: String): String {
            val allowed = FuelGrades.forFuelType(fuelType)
            return grade.takeIf { it in allowed } ?: allowed.first()
        }
    }
}
