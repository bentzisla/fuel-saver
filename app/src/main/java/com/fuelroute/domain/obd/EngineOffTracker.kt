package com.fuelroute.domain.obd

/**
 * Run-level ignition-off detection for the OBD loop.
 *
 * Owned by one run and deliberately NOT reset by an in-run reconnect: a reconnect used to clear
 * the RPM-missing clock, so a parked car whose link kept dropping never reached the 60 s
 * ignition-off timeout. Two clocks are kept:
 *
 *  - [rpmNullSinceMs]: RPM reply missing (only counted when [ObdConnectionPolicy.shouldTrackRpmAbsence]
 *    allows it), stopped after [rpmTimeoutMs] ([ObdConnectionPolicy.shouldStopForIgnitionOff]);
 *  - [engineOffSinceMs]: no evidence of a running engine at all (RPM missing or 0 and not
 *    moving), stopped after [engineOffCapMs] — covers key-on/engine-off, where RPM answers 0.
 *
 * Pure Kotlin, not thread-safe.
 */
class EngineOffTracker(
    private val rpmTimeoutMs: Long = ObdConnectionPolicy.IGNITION_OFF_RPM_TIMEOUT_MS,
    private val engineOffCapMs: Long = ObdConnectionPolicy.ENGINE_OFF_RUN_CAP_MS,
) {
    var rpmNullSinceMs: Long? = null
        private set

    var engineOffSinceMs: Long? = null
        private set

    /**
     * Folds one poll in. [rpm]/[speedKmh] are the raw parsed replies (null = no valid reply).
     */
    fun onSample(nowMs: Long, rpm: Double?, speedKmh: Double?, rpmPidSupported: Boolean) {
        if (rpm == null && ObdConnectionPolicy.shouldTrackRpmAbsence(rpmPidSupported, speedKmh)) {
            if (rpmNullSinceMs == null) rpmNullSinceMs = nowMs
        } else {
            rpmNullSinceMs = null
        }
        val engineEvidence = (rpm ?: 0.0) > 0.0 ||
            (speedKmh ?: 0.0) >= ObdConnectionPolicy.STATIONARY_SPEED_KMH
        if (engineEvidence) {
            engineOffSinceMs = null
        } else if (engineOffSinceMs == null) {
            engineOffSinceMs = nowMs
        }
    }

    /** True when the run should end as ignition-off. */
    fun shouldStop(nowMs: Long, batteryVoltage: Double?): Boolean {
        if (ObdConnectionPolicy.shouldStopForIgnitionOff(rpmNullSinceMs, nowMs, batteryVoltage, rpmTimeoutMs)) {
            return true
        }
        val offSince = engineOffSinceMs ?: return false
        return nowMs - offSince >= engineOffCapMs
    }
}
