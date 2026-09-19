package com.fuelroute.ui.stats

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.obd.BluetoothDevicesRepository
import com.fuelroute.data.obd.LearnedCurveRepository
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.data.obd.TripRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.model.Trip
import com.fuelroute.service.ObdLoggingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripDisplay(
    val trip: Trip,
    val predictedL100: Double?,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: ObdEngine,
    private val bluetoothRepository: BluetoothDevicesRepository,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val vehicleRepository: VehicleRepository,
    private val learnedCurveRepository: LearnedCurveRepository,
) : ViewModel() {

    val live: StateFlow<LiveObdState> = engine.live

    private val _bonded = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bonded: StateFlow<List<BluetoothDevice>> = _bonded.asStateFlow()

    private val _connectingName = MutableStateFlow<String?>(null)
    val connectingName: StateFlow<String?> = _connectingName.asStateFlow()

    private val _trips = MutableStateFlow<List<TripDisplay>>(emptyList())
    val trips: StateFlow<List<TripDisplay>> = _trips.asStateFlow()

    init {
        refreshDevices()
        loadTrips()
        viewModelScope.launch {
            engine.live.collect { state ->
                if (state.status != ObdStatus.Connecting) {
                    _connectingName.update { null }
                }
                if (state.status == ObdStatus.Disconnected) {
                    loadTrips()
                }
            }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _bonded.value = bluetoothRepository.bondedDevices()
        }
    }

    private fun loadTrips() {
        viewModelScope.launch {
            val trips = tripRepository.recentTrips(20)
            val curve = effectiveCurve()
            _trips.value = trips.map { trip ->
                TripDisplay(
                    trip = trip,
                    predictedL100 = trip.litersPer100Km?.let { curve.litersPer100Km(trip.avgSpeedKmh) },
                )
            }
        }
    }

    private suspend fun effectiveCurve(): ConsumptionCurve {
        val vehicle = vehicleRepository.profile.first()
        val learned = learnedCurveRepository.learnedCurve(vehicle.id.ifBlank { DEFAULT_VEHICLE_ID })
        val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
        val manual = vehicle.manualCurve
            ?.takeIf { it.size >= 2 }
            ?.let { runCatching { ConsumptionCurve(it) }.getOrNull() }
        return CurveBlender.blend(learned, manual ?: default)
    }

    fun connectDemo() {
        ObdLoggingService.start(appContext, null)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _connectingName.value = device.name ?: device.address
        ObdLoggingService.start(appContext, device.address)
        viewModelScope.launch { settingsRepository.saveLastDeviceAddress(device.address) }
    }

    fun autoConnect() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.autoConnect && !settings.lastDeviceAddress.isNullOrBlank()) {
                ObdLoggingService.start(appContext, settings.lastDeviceAddress)
            }
        }
    }

    fun stop() {
        ObdLoggingService.stop(appContext)
    }

    private companion object {
        const val DEFAULT_VEHICLE_ID = "default"
    }
}