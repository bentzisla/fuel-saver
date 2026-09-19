package com.fuelroute.data.obd

import android.util.Log
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
    val lastRawReply: String? = null,
    val lastError: String? = null,
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
                mutableLive.update { it.copy(status = ObdStatus.Error, lastError = "CONNECT") }
                return@launch
            }

            val replies = mutableMapOf<String, String>()
            for (command in ElmProtocol.initializationCommands) {
                val reply = transport.sendCommand(command)
                replies[command] = reply
                Log.d(TAG, "ELM init $command -> ${reply.trim()}")
            }

            val atz = replies["ATZ"].orEmpty()
            if (!atz.contains("ELM327", ignoreCase = true)) {
                Log.e(TAG, "ATZ did not return ELM327 banner: \"$atz\"")
                transport.disconnect()
                mutableLive.update {
                    it.copy(
                        status = ObdStatus.Error,
                        deviceName = transport.deviceName,
                        lastError = "bad ATZ: ${atz.trim()}",
                    )
                }
                return@launch
            }

            mutableLive.update {
                it.copy(status = ObdStatus.Connected, deviceName = transport.deviceName)
            }
            settleProtocol(transport)
            runLoop(transport, vehicle, vehicleId)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableLive.update { it.copy(status = ObdStatus.Disconnected) }
    }

    /**
     * After `ATSP0` some adapters keep answering `SEARCHING...` while they auto-detect the
     * bus protocol. Give them up to [PROTOCOL_LOCK_TIMEOUT_MS] to settle (first real data
     * PID that is no longer SEARCHING wins). If it never settles we keep going — the run
     * loop surfaces SEARCHING via `lastError` instead of crashing.
     */
    private suspend fun settleProtocol(transport: ObdTransport) {
        val deadline = System.currentTimeMillis() + PROTOCOL_LOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val raw = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
            if (!raw.contains("SEARCHING", ignoreCase = true)) return
            delay(PROTOCOL_LOCK_RETRY_MS)
        }
        Log.w(TAG, "protocol still SEARCHING after ${PROTOCOL_LOCK_TIMEOUT_MS}ms")
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
        var consecutiveBad = 0

        try {
            while (true) {
                val now = System.currentTimeMillis()
                val rawSpeed = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_SPEED))
                val rawRpm = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_RPM))
                val rawCoolant = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_COOLANT_TEMP))
                val rawMaf = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_MAF))
                val rawFuelRate = transport.sendCommand(ElmProtocol.command(ElmProtocol.PID_FUEL_RATE))

                val speed = ElmProtocol.speed(rawSpeed)
                val rpm = ElmProtocol.rpm(rawRpm)
                val coolant = ElmProtocol.coolantTempC(rawCoolant)
                val maf = ElmProtocol.mafGps(rawMaf)
                val fuelRateRaw = ElmProtocol.fuelRateLph(rawFuelRate)

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

                val badReason = when {
                    rawSpeed.contains("SEARCHING", ignoreCase = true) -> "SEARCHING"
                    rawSpeed.contains("NO DATA", ignoreCase = true) ||
                        rawSpeed.contains("NODATA", ignoreCase = true) -> "NO DATA"
                    speed == null && rawSpeed.isNotBlank() -> "PARSE"
                    rawSpeed.isBlank() -> "TIMEOUT"
                    else -> null
                }
                if (badReason != null) consecutiveBad++ else consecutiveBad = 0
                val diagError = if (consecutiveBad >= CONSECUTIVE_ERROR_THRESHOLD) badReason else null

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
                        lastRawReply = rawSpeed.take(MAX_RAW_REPLY_CHARS),
                        lastError = diagError,
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
        private const val TAG = "FuelRoute"
        private const val MAX_RAW_REPLY_CHARS = 160
        private const val CONSECUTIVE_ERROR_THRESHOLD = 10
        private const val PROTOCOL_LOCK_TIMEOUT_MS = 3_000L
        private const val PROTOCOL_LOCK_RETRY_MS = 250L
    }
}