package com.fuelroute.data.obd

import com.fuelroute.data.db.ObdSampleDao
import com.fuelroute.data.db.ObdSampleEntity
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.TripEntity
import com.fuelroute.domain.learning.FuelRateCalculator
import com.fuelroute.domain.learning.SpeedBinAggregator
import com.fuelroute.domain.learning.TripDetector
import com.fuelroute.domain.model.ObdSample
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.VehicleProfile
import com.fuelroute.domain.obd.ElmProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ObdStatus { Disconnected, Connecting, Connected, Error }

data class LiveObdState(
    val status: ObdStatus = ObdStatus.Disconnected,
    val deviceName: String? = null,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val coolantTempC: Double? = null,
    val fuelRateLph: Double? = null,
    val instantL100: Double? = null,
    val tripDistanceKm: Double = 0.0,
    val tripFuelL: Double = 0.0,
    val tripSeconds: Double = 0.0,
    val bins: List<SpeedBinStats> = emptyList(),
    val totalDistanceKm: Double = 0.0,
    val sampleCount: Int = 0,
)

@Singleton
class ObdEngine @Inject constructor(
    private val sampleDao: ObdSampleDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLive = MutableStateFlow(LiveObdState())
    val live: StateFlow<LiveObdState> = mutableLive.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(transport: ObdTransport, vehicle: VehicleProfile) {
        if (job?.isActive == true) return
        val vehicleId = vehicle.id.ifBlank { DEFAULT_VEHICLE_ID }
        job = scope.launch {
            runCatching {
                sampleDao.deleteOlderThan(System.currentTimeMillis() - SAMPLE_RETENTION_MS)
            }
            mutableLive.update { it.copy(status = ObdStatus.Connecting) }
            val connected = transport.connect()
            if (connected.isFailure) {
                mutableLive.update { it.copy(status = ObdStatus.Error) }
                return@launch
            }
            for (command in ElmProtocol.initializationCommands) {
                transport.sendCommand(command)
            }
            mutableLive.update { it.copy(status = ObdStatus.Connected, deviceName = transport.deviceName) }
            runLoop(transport, vehicle, vehicleId)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableLive.update { it.copy(status = ObdStatus.Disconnected) }
    }

    private suspend fun runLoop(transport: ObdTransport, vehicle: VehicleProfile, vehicleId: String) {
        val bins = mutableMapOf<Int, SpeedBinStats>()
        speedBinDao.getForVehicle(vehicleId).forEach {
            bins[it.binIndex] = SpeedBinStats(
                vehicleId = it.vehicleId,
                binIndex = it.binIndex,
                distanceKm = it.distanceKm,
                fuelL = it.fuelL,
                seconds = it.seconds,
                samples = it.samples,
            )
        }

        val aggregator = SpeedBinAggregator()
        val tripDetector = TripDetector()

        var lastSample: ObdSample? = null
        var sampleCount = 0
        var tripDistance = 0.0
        var tripFuel = 0.0
        var tripSeconds = 0.0
        var tripIdleSeconds = 0.0
        var tripMaxSpeed = 0.0
        var lastPersistMs = 0L

        try {
            while (true) {
                val now = System.currentTimeMillis()
                val speed = ElmProtocol.speed(transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED)))
                val rpm = ElmProtocol.rpm(transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_RPM)))
                val coolant = ElmProtocol.coolantTempC(transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_COOLANT_TEMP)))
                val maf = ElmProtocol.mafGps(transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_MAF)))
                val fuelRateRaw = ElmProtocol.fuelRateLph(transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_FUEL_RATE)))

                val sample = ObdSample(
                    timestampMs = now,
                    speedKmh = speed,
                    rpm = rpm,
                    mafGps = maf,
                    fuelRateLph = fuelRateRaw,
                    coolantTempC = coolant,
                )

                val fuelRate = FuelRateCalculator.fuelRateLph(sample, vehicle.fuelType, vehicle.engineDisplacementL)
                    ?.times(vehicle.fuelRateCorrection)
                val dtSec = lastSample?.let { ((now - it.timestampMs) / 1000.0).coerceIn(0.0, 2.0) } ?: 0.0

                aggregator.accumulate(bins, sample, dtSec, fuelRate, vehicleId)

                if (sample.engineRunning) {
                    val rate = fuelRate ?: 0.0
                    tripFuel += rate * dtSec / 3600.0
                    tripDistance += (speed ?: 0.0) * dtSec / 3600.0
                    tripSeconds += dtSec
                    if ((speed ?: 0.0) < 1.0) tripIdleSeconds += dtSec
                    tripMaxSpeed = maxOf(tripMaxSpeed, speed ?: 0.0)
                }

                when (val transition = tripDetector.onSample(sample)) {
                    is TripDetector.TripTransition.Ended -> {
                        tripDao.insert(
                            TripEntity(
                                vehicleId = vehicleId,
                                startedAtMs = transition.startedAtMs,
                                endedAtMs = transition.endedAtMs,
                                distanceKm = tripDistance,
                                fuelL = tripFuel,
                                avgSpeedKmh = if (tripSeconds > 0.0) tripDistance / (tripSeconds / 3600.0) else 0.0,
                                maxSpeedKmh = tripMaxSpeed,
                                idleSeconds = tripIdleSeconds,
                            ),
                        )
                        tripDistance = 0.0
                        tripFuel = 0.0
                        tripSeconds = 0.0
                        tripIdleSeconds = 0.0
                        tripMaxSpeed = 0.0
                    }
                    else -> Unit
                }

                sampleCount++
                if (now - lastPersistMs >= SAMPLE_PERSIST_INTERVAL_MS) {
                    sampleDao.insert(sample.toEntity(vehicleId))
                    lastPersistMs = now
                }

                val speedKmh = speed ?: 0.0
                val instantL100 = if (speedKmh > 1.0 && fuelRate != null) fuelRate / speedKmh * 100.0 else null
                mutableLive.update {
                    it.copy(
                        speedKmh = speed,
                        rpm = rpm,
                        coolantTempC = coolant,
                        fuelRateLph = fuelRate,
                        instantL100 = instantL100,
                        tripDistanceKm = tripDistance,
                        tripFuelL = tripFuel,
                        tripSeconds = tripSeconds,
                        bins = bins.values.sortedBy { bin -> bin.binIndex },
                        totalDistanceKm = bins.values.sumOf { bin -> bin.distanceKm },
                        sampleCount = sampleCount,
                    )
                }

                lastSample = sample
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            speedBinDao.upsertAll(bins.values.map { it.toEntity() })
            val end = tripDetector.forceEnd(lastSample?.timestampMs ?: System.currentTimeMillis())
            if (end is TripDetector.TripTransition.Ended) {
                tripDao.insert(
                    TripEntity(
                        vehicleId = vehicleId,
                        startedAtMs = end.startedAtMs,
                        endedAtMs = end.endedAtMs,
                        distanceKm = tripDistance,
                        fuelL = tripFuel,
                        avgSpeedKmh = if (tripSeconds > 0.0) tripDistance / (tripSeconds / 3600.0) else 0.0,
                        maxSpeedKmh = tripMaxSpeed,
                        idleSeconds = tripIdleSeconds,
                    ),
                )
            }
            transport.disconnect()
            mutableLive.update { it.copy(status = ObdStatus.Disconnected) }
        }
    }

    private fun ObdSample.toEntity(vehicleId: String) = ObdSampleEntity(
        vehicleId = vehicleId,
        timestampMs = timestampMs,
        speedKmh = speedKmh,
        rpm = rpm,
        mafGps = mafGps,
        fuelRateLph = fuelRateLph,
        mapKpa = mapKpa,
        intakeTempC = intakeTempC,
        coolantTempC = coolantTempC,
        engineLoadPct = engineLoadPct,
        fuelLevelPct = fuelLevelPct,
    )

    private fun SpeedBinStats.toEntity() = SpeedBinStatsEntity(
        vehicleId = vehicleId,
        binIndex = binIndex,
        distanceKm = distanceKm,
        fuelL = fuelL,
        seconds = seconds,
        samples = samples,
    )

    companion object {
        const val POLL_INTERVAL_MS = 250L
        const val SAMPLE_PERSIST_INTERVAL_MS = 1_000L
        const val SAMPLE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        const val DEFAULT_VEHICLE_ID = "default"
    }
}