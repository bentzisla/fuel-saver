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
import com.fuelroute.domain.fuel.EfficientSpeed
import com.fuelroute.domain.fuel.EfficientSpeedInsight
import com.fuelroute.domain.fuel.ManualCurveResult
import com.fuelroute.domain.fuel.ManualCurveValidator
import com.fuelroute.domain.model.SpeedPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LearnedPoint(
    val speedKmh: Double,
    val litersPer100Km: Double,
    val distanceKm: Double,
    /**
     * False when the blend ignores this measurement as implausible against the fallback curve
     * ([CurveBlender.usesLearnedValue]); the chart draws it hollow so a dot the curve does not
     * follow is not mistaken for data the recommendation is based on.
     */
    val usedInCurve: Boolean = true,
)

data class CurveUiState(
    val isLoading: Boolean = true,
    /** True when the last [CurveViewModel.load] failed; the screen then offers a retry. */
    val error: Boolean = false,
    val effectivePoints: List<SpeedPoint> = emptyList(),
    val defaultPoints: List<SpeedPoint> = emptyList(),
    val manualPoints: List<SpeedPoint> = emptyList(),
    val learnedPoints: List<LearnedPoint> = emptyList(),
    val totalKm: Double = 0.0,
    val totalSamples: Int = 0,
    val idleLph: Double? = null,
    /** The recommendation, computed from the same curve the chart draws (see [EfficientSpeed]). */
    val efficient: EfficientSpeedInsight? = null,
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
            _state.update { it.copy(isLoading = true, error = false) }
            _state.value = runCatching { buildState() }.getOrElse {
                // Never leave the screen spinning forever/blank on a repository failure.
                CurveUiState(isLoading = false, error = true)
            }
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

    /**
     * Persists a user-entered manual curve. An empty list clears the manual curve (stores null);
     * an input that normalizes to fewer than two points is rejected without touching storage.
     */
    fun saveManualCurve(points: List<SpeedPoint>) {
        viewModelScope.launch {
            val curve = when (val result = ManualCurveValidator.validate(points)) {
                is ManualCurveResult.Valid -> result.points
                ManualCurveResult.Cleared -> null
                is ManualCurveResult.Invalid -> return@launch
            }
            val vehicle = vehicleRepository.active()
            vehicleRepository.upsert(vehicle.copy(manualCurve = curve))
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

        // Only plausible bins are plotted: tiny-distance or corrupted rows (e.g. stored before the
        // sample sanity filters) are not drawn as if they were measurements. Of those, the ones the
        // blend still rejects against the fallback curve are flagged so the chart draws them hollow.
        val learnedPoints = learned.points
            .map {
                LearnedPoint(
                    speedKmh = it.speedKmh,
                    litersPer100Km = it.litersPer100Km,
                    distanceKm = it.distanceKm,
                    usedInCurve = CurveBlender.usesLearnedValue(it.litersPer100Km, fallback.litersPer100Km(it.speedKmh)),
                )
            }

        val effectiveSamples = effective.samples()

        return CurveUiState(
            isLoading = false,
            effectivePoints = effectiveSamples,
            defaultPoints = default.samples(),
            manualPoints = manual?.samples().orEmpty(),
            learnedPoints = learnedPoints,
            totalKm = learned.totalDistanceKm,
            totalSamples = learned.bins.sumOf { it.samples },
            idleLph = learned.idleLitersPerHour,
            efficient = EfficientSpeed.analyze(effective, learned, fallback),
            calibrationFactor = vehicle.fuelRateCorrection,
            learnedShare = CurveBasis.learnedShare(learned, effectiveSamples.map { it.speedKmh }),
            quality = CurveBasis.quality(learned.totalDistanceKm),
            hasManualCurve = manual != null,
            hasLearnedData = learned.totalDistanceKm > 0.0,
        )
    }
}