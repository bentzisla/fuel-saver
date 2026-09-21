package com.fuelroute.data.obd

import android.os.SystemClock
import java.util.Locale

/**
 * A dynamic ELM327 simulator that produces a realistic, time-varying drive cycle
 * (idle -> accelerate -> cruise -> decelerate -> idle) instead of fixed values.
 * It exercises the whole pipeline (protocol parsing, fuel rate, speed bins, trip
 * detection, live UI) without any dongle, on the emulator or a real phone.
 */
class SimulatedObdTransport(
    override val deviceName: String = "Simulated ELM327 (demo)",
) : ObdTransport {

    private var connected = false
    private var startElapsedMs = 0L

    override val isSimulated: Boolean = true

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect(): Result<Unit> {
        connected = true
        startElapsedMs = SystemClock.elapsedRealtime()
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendCommand(command: String): String {
        if (!connected) return "UNABLE TO CONNECT"
        val tSec = (SystemClock.elapsedRealtime() - startElapsedMs) / 1000.0
        val s = stateAt(tSec)
        return when (command.trim().uppercase(Locale.US)) {
            "010D" -> String.format(Locale.US, "41 0D %02X", s.speed)
            "010C" -> twoBytes("41 0C", s.rpm * 4)
            "0105" -> String.format(Locale.US, "41 05 %02X", s.coolant + 40)
            "0110" -> twoBytes("41 10", (s.mafGps * 100).toInt())
            "015E" -> twoBytes("41 5E", (s.fuelRateLph * 20).toInt())
            "010B" -> String.format(Locale.US, "41 0B %02X", 100)
            "010F" -> String.format(Locale.US, "41 0F %02X", 24 + 40)
            "0100" -> "41 00 BE 3F A8 13"
            "0120" -> "41 20 80 00 00 00"
            "0140", "0160" -> "NO DATA"
            "0902" -> VIN_RESPONSE
            "ATRV" -> String.format(Locale.US, "%.1fV", 12.4)
            "ATZ" -> "ELM327 v1.5"
            "ATE0", "ATL0", "ATS0", "ATS1", "ATH0", "ATSP0", "ATAT1", "ATST64" -> "OK"
            else -> "NO DATA"
        }
    }

    private data class SimState(
        val speed: Int,
        val rpm: Int,
        val coolant: Int,
        val mafGps: Double,
        val fuelRateLph: Double,
    )

    private fun stateAt(tSec: Double): SimState {
        val coolant = (30 + 60 * (tSec / 15.0)).toInt().coerceIn(25, 92)
        val speed = speedAt(tSec)
        val rpm = if (speed < 1) 800 else (800 + speed * 28).coerceAtMost(3_200)
        val fuelRate = fuelRateLph(speed)
        val mafGps = fuelRate * 3.04
        return SimState(speed, rpm, coolant, mafGps, fuelRate)
    }

    private fun fuelRateLph(speed: Int): Double {
        if (speed < 1) return 0.85
        val l100 = 6.0 + 0.0008 * (speed - 75.0) * (speed - 75.0)
        return l100 * speed / 100.0
    }

    private fun speedAt(tSec: Double): Int {
        val cycle = 72.0
        val t = tSec % cycle
        return when {
            t < 5 -> 0
            t < 20 -> ((t - 5) / 15.0 * 50).toInt()
            t < 30 -> 50
            t < 42 -> 50 + ((t - 30) / 12.0 * 50).toInt()
            t < 47 -> 100
            t < 57 -> (100 * (1 - (t - 47) / 10.0)).toInt()
            else -> 0
        }
    }

    private fun twoBytes(prefix: String, value: Int): String {
        val v = value.coerceIn(0, 0xFFFF)
        return String.format(Locale.US, "%s %02X %02X", prefix, (v shr 8) and 0xFF, v and 0xFF)
    }

    companion object {
        /** Mode 09 PID 02 reply for VIN "WP0ZZZ99ZMS123456". */
        const val VIN_RESPONSE = "49 02 01 57 50 30 5A 5A 5A 39 39 5A 4D 53 31 32 33 34 35 36"
    }
}