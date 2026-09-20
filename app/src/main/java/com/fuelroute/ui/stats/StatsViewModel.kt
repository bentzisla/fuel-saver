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
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ConsumptionCurve
import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.model.Trip
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.service.ObdLoggingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
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

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _bonded = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bonded: StateFlow<List<BluetoothDevice>> = _bonded.asStateFlow()

    private val _connectingName = MutableStateFlow<String?>(null)
    val connectingName: StateFlow<String?> = _connectingName.asStateFlow()

    private val _trips = MutableStateFlow<List<TripDisplay>>(emptyList())
    val trips: StateFlow<List<TripDisplay>> = _trips.asStateFlow()

    private val _vehicles = MutableStateFlow<List<VehicleProfile>>(emptyList())
    val vehicles: StateFlow<List<VehicleProfile>> = _vehicles.asStateFlow()

    private val _activeVehicle = MutableStateFlow<VehicleProfile?>(null)
    val activeVehicle: StateFlow<VehicleProfile?> = _activeVehicle.asStateFlow()

    /** Name of the vehicle recognised from the OBD VIN, or null. Consumed by the UI toast. */
    private val _vinEvent = MutableStateFlow<String?>(null)
    val vinEvent: StateFlow<String?> = _vinEvent.asStateFlow()

    private var lastHandledVin: String? = null

    // Target of the most recent connect attempt (null = simulated demo), used by [retry]
    // so the user can repeat the exact same attempt after a failure.
    private var lastAddress: String? = null
    private var lastName: String? = null

    init {
        refreshDevices()

        viewModelScope.launch {
            vehicleRepository.vehicles().collect { list ->
                _vehicles.value = list
                val current = list.firstOrNull { it.id == _activeVehicle.value?.id } ?: list.firstOrNull()
                if (current != null && current != _activeVehicle.value) {
                    _activeVehicle.value = current
                }
            }
        }
        viewModelScope.launch {
            _activeVehicle.value = vehicleRepository.active()
        }
        viewModelScope.launch {
            _activeVehicle.collect { loadTrips() }
        }
        viewModelScope.launch {
            engine.live.collect { state ->
                if (state.status != ObdStatus.Connecting) {
                    _connectingName.update { null }
                }
                handleVin(state.vin)
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

    /** Switches the active vehicle; stats reload for that vehicle. */
    fun selectVehicle(id: String) {
        viewModelScope.launch {
            vehicleRepository.setActive(id)
            _activeVehicle.value = vehicleRepository.active()
        }
    }

    fun consumeVinEvent() {
        _vinEvent.value = null
    }

    private fun handleVin(vin: String?) {
        if (vin.isNullOrBlank() || vin == lastHandledVin) return
        lastHandledVin = vin
        viewModelScope.launch {
            val match = vehicleRepository.findByVin(vin)
            if (match != null) {
                if (match.id != _activeVehicle.value?.id) {
                    vehicleRepository.setActive(match.id)
                    _activeVehicle.value = vehicleRepository.active()
                }
                _vinEvent.value = match.name
            } else {
                // First sighting of this car: remember the VIN on the active vehicle so a
                // later connection can auto-switch to it.
                _activeVehicle.value?.let { active ->
                    if (active.vin != vin) vehicleRepository.upsert(active.copy(vin = vin))
                }
            }
        }
    }

    private fun loadTrips() {
        val vehicleId = _activeVehicle.value?.id
        if (vehicleId == null) {
            _trips.value = emptyList()
            return
        }
        viewModelScope.launch {
            val trips = tripRepository.recentTrips(vehicleId, 20)
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
        val vehicle = vehicleRepository.active()
        val learned = learnedCurveRepository.learnedCurve(vehicle.id)
        val default = DefaultCurve.forVehicle(vehicle.ratedCombinedL100, vehicle.fuelType)
        val manual = vehicle.manualCurve
            ?.takeIf { it.size >= 2 }
            ?.let { runCatching { ConsumptionCurve(it) }.getOrNull() }
        return CurveBlender.blend(learned, manual ?: default)
    }

    /** Starts a clearly-labelled simulated demo run (no dongle required). */
    fun connectDemo() {
        lastAddress = null
        lastName = null
        if (!engine.isRunning) engine.reset()
        _connectingName.value = null
        viewModelScope.launch { settingsRepository.saveManualDisconnect(false) }
        ObdLoggingService.start(appContext, null)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        lastAddress = device.address
        lastName = device.name ?: device.address
        if (!engine.isRunning) engine.reset()
        _connectingName.value = lastName
        ObdLoggingService.start(appContext, device.address)
        viewModelScope.launch {
            settingsRepository.saveManualDisconnect(false)
            settingsRepository.saveLastDeviceAddress(device.address)
        }
    }

    fun autoConnect() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.autoConnect && !settings.lastDeviceAddress.isNullOrBlank()) {
                // Explicit (re-)arm: clear the sticky manual-disconnect latch.
                settingsRepository.saveManualDisconnect(false)
                lastAddress = settings.lastDeviceAddress
                lastName = settings.lastDeviceName ?: settings.lastDeviceAddress
                if (!engine.isRunning) engine.reset()
                _connectingName.value = lastName
                ObdLoggingService.start(appContext, settings.lastDeviceAddress, auto = true)
            }
        }
    }

    /** User-initiated disconnect: stops logging and clears the visible error/device. */
    fun disconnect() {
        _connectingName.value = null
        // Sticky latch — must be persisted BEFORE stopping so a racing ACL/STATE broadcast
        // (the dongle is often still connected at this instant) can't re-arm logging. The
        // async launch below was racy: the receiver could read the old (false) value.
        runBlocking { settingsRepository.saveManualDisconnect(true) }
        engine.disconnect()
        ObdLoggingService.stop(appContext)
    }

    /** Full reset: stops logging and wipes engine state so the next connect starts clean. */
    fun reset() {
        _connectingName.value = null
        runBlocking { settingsRepository.saveManualDisconnect(true) }
        engine.reset()
        ObdLoggingService.stop(appContext)
    }

    /**
     * Retries the last attempt (same dongle, or the demo) after a failure. Resets first so
     * a stuck/errored engine cannot reject the new run, then re-arms the logging service.
     */
    fun retry() {
        if (!engine.isRunning) engine.reset()
        _connectingName.value = lastName
        viewModelScope.launch { settingsRepository.saveManualDisconnect(false) }
        ObdLoggingService.start(appContext, lastAddress)
    }

    fun stop() = disconnect()
}