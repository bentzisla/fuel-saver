package com.fuelroute.domain.obd

/** A paired Bluetooth adapter candidate for auto-connect (name may be unknown). */
data class BondedObdDevice(
    val address: String,
    val name: String?,
)

/**
 * Pure rules for choosing which Bluetooth device is the OBD-II adapter and whether an
 * auto-connect should happen. Kept free of Android types so it is unit-testable on the JVM.
 */
object ObdDeviceMatcher {

    /** Name fragments shared by common ELM327 clones. Matched case-insensitively. */
    val NAME_PATTERNS: List<String> = listOf("OBD", "ELM327", "Vgate", "viecar", "KONNWEI")

    fun matchesName(name: String?): Boolean {
        val candidate = name?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        return NAME_PATTERNS.any { candidate.contains(it, ignoreCase = true) }
    }

    /**
     * Resolves the adapter when a device has just connected. Resolution order:
     * `lastDeviceAddress` → a bonded ELM-pattern name match → null. Returns null when
     * auto-connect is off or the device is unrelated.
     */
    fun resolveConnected(
        autoConnect: Boolean,
        lastDeviceAddress: String?,
        connectedAddress: String?,
        connectedName: String?,
    ): String? {
        if (!autoConnect) return null
        val address = connectedAddress?.takeIf { it.isNotBlank() } ?: return null
        val last = lastDeviceAddress?.takeIf { it.isNotBlank() }
        if (last != null) return address.takeIf { it.equals(last, ignoreCase = true) }
        return address.takeIf { matchesName(connectedName) }
    }

    /**
     * Resolves the adapter among already-bonded devices (boot / Bluetooth-on re-arm).
     * `lastDeviceAddress` wins when it is bonded; otherwise the first ELM-pattern match.
     */
    fun resolveBonded(
        autoConnect: Boolean,
        lastDeviceAddress: String?,
        bonded: List<BondedObdDevice>,
    ): String? {
        if (!autoConnect) return null
        val last = lastDeviceAddress?.takeIf { it.isNotBlank() }
        if (last != null) {
            return bonded.firstOrNull { it.address.equals(last, ignoreCase = true) }?.address
        }
        return bonded.firstOrNull { matchesName(it.name) }?.address
    }

    /** True when a disconnect of this device should stop logging. */
    fun isTargetDevice(
        lastDeviceAddress: String?,
        connectedAddress: String?,
        connectedName: String?,
    ): Boolean {
        val address = connectedAddress?.takeIf { it.isNotBlank() } ?: return false
        val last = lastDeviceAddress?.takeIf { it.isNotBlank() }
        if (last != null) return address.equals(last, ignoreCase = true)
        return matchesName(connectedName)
    }
}

/**
 * Short start/stop debounce so an adapter that flaps (an ACL disconnect immediately
 * followed by an ACL connect) does not re-enter the logging loop. Pure so it can be
 * driven with virtual time in tests.
 */
object AutoConnectDebounce {
    const val WINDOW_MS = 5_000L

    /** True when a start at [nowMs] should be ignored because a stop happened recently. */
    fun shouldIgnoreStart(
        lastStopAtMs: Long?,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): Boolean {
        if (lastStopAtMs == null) return false
        val elapsed = nowMs - lastStopAtMs
        return elapsed in 0 until windowMs
    }
}
