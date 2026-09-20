package com.fuelroute.ui.curve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBasis
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.CurveDataQuality
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.model.SpeedPoint
import com.fuelroute.domain.model.binIndexToSpeedKmh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LearnedPoint(
    val speedKmh: Double,
    val litersPer100Km: Double,
    val distanceKm: Double,
)

data class CurveUiState(
    val isLoading: Boolean = true,
    val effectivePoints: List<SpeedPoint> = emptyList(),
    val defaultPoints: List<SpeedPoint> = emptyList(),
    val manualPoints: List<SpeedPoint> = emptyList(),
    val learnedPoints: List<LearnedPoint> = emptyList(),
    val totalKm: Double = 0.0,
    val totalSamples: Int = 0,
    val idleLph: Double? = null,
    val efficientSpeedKmh: Double? = null,
    val efficientL100: Double? = null,
    val calibrationFactor: Double = 1.0,
    val learnedShare: Double = 0.0,
    val quality: CurveDataQuality = CurveDataQuality.NONE,
    val hasManualCurve: Boolean = false,
    val hasLearnedData: Boolean = false,
)

@HiltViewModel
class CurveViewModel @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val learnedCurveRepository: LearnedCurveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CurveUiState())
    val state: StateFlow<CurveUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = buildState()
        }
    }

    fun adoptLearned() {
        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            val current = _state.value
            if (current.effectivePoints.size >= 2) {
                vehicleRepository.upsert(vehicle.copy(manualCurve = current.effectivePoints))
            }
            learnedCurveRepository.reset(vehicle.id)
            _state.value = buildState()
        }
    }

    fun resetLearning() {
        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            learnedCurveRepository.reset(vehicle.id)
            _state.value = buildState()
        }
    }

    fun clearManual() {
        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            vehicleRepository.upsert(vehicle.copy(manualCurve = null))
            _state.value = buildState()
        }
    }

    private suspend fun buildState(): CurveUiState {
        val vehicle = vehicleRepository.active()
        val learned = learnedCurveRepository.learnedCurve(vehicle.id)
        val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
        val manual = vehicle.manualCurve
            ?.takeIf { it.size >= 2 }
            ?.let { runCatching { ConsumptionCurve(it) }.getOrNull() }
        val fallback = manual ?: default
        val effective = CurveBlender.blend(learned, fallback)

        val learnedPoints = learned.bins
            .filter { it.binIndex > 0 && it.litersPer100Km != null }
            .map { LearnedPoint(binIndexToSpeedKmh(it.binIndex), it.litersPer100Km!!, it.distanceKm) }

        val effectiveSamples = effective.samples()
        val best = effectiveSamples.minByOrNull { it.litersPer100Km }

        return CurveUiState(
            isLoading = false,
            effectivePoints = effectiveSamples,
            defaultPoints = default.samples(),
            manualPoints = manual?.samples().orEmpty(),
            learnedPoints = learnedPoints,
            totalKm = learned.totalDistanceKm,
            totalSamples = learned.bins.sumOf { it.samples },
            idleLph = learned.idleLitersPerHour,
            efficientSpeedKmh = best?.speedKmh,
            efficientL100 = best?.litersPer100Km,
            calibrationFactor = vehicle.fuelRateCorrection,
            learnedShare = CurveBasis.learnedShare(learned, effectiveSamples.map { it.speedKmh }),
            quality = CurveBasis.quality(learned.totalDistanceKm),
            hasManualCurve = manual != null,
            hasLearnedData = learned.totalDistanceKm > 0.0,
        )
    }
}