package com.fuelroute.domain.obd

/**
 * Pure rules for the quiet background check that finds the saved OBD dongle mid-drive.
 *
 * A cheap ELM327 is a passive Bluetooth SPP device: it never connects to the phone by itself, so
 * the ACL broadcast the zero-touch receiver listens for only ever fires for sockets the app itself
 * opens. Nothing would start logging on its own. Instead, every few minutes a short, invisible
 * probe opens one RFCOMM socket to the saved dongle, asks whether the engine is running, and closes
 * it again. Only a running engine starts the logging service, so a parked car (whose dongle stays
 * powered from the OBD port) never raises a notification.
 */
object ObdProbePolicy {

    /** Probe interval choices offered in Settings, in minutes. */
    val INTERVAL_CHOICES_MIN = listOf(2, 5, 10, 15)

    const val DEFAULT_INTERVAL_MIN = 5

    private const val MIN_INTERVAL_MIN = 1
    private const val MAX_INTERVAL_MIN = 60

    fun clampIntervalMin(minutes: Int): Int = minutes.coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)

    // --- Link --------------------------------------------------------------------------------

    /**
     * Per-socket connect deadline while probing. An absent dongle fails by itself after the
     * Bluetooth page timeout (~5 s); this only bounds a connect that hangs.
     */
    const val CONNECT_TIMEOUT_MS = 8_000L

    /**
     * A socket variant that fails faster than this was answered and refused by the dongle (wrong
     * socket type), so the next variant is worth a try. A slower failure is the page timeout of a
     * dongle that is not there, and the other variants would only repeat it.
     */
    const val FAST_FAIL_MS = 2_500L

    /**
     * The probe's own socket makes the dongle's ACL link go up and down. For this long after the
     * probe ends, ACL broadcasts for the dongle are ours and must not start or stop logging.
     */
    const val ACL_SUPPRESS_GRACE_MS = 10_000L

    /** True when an ACL broadcast for the probed dongle falls inside the probe's own window. */
    fun isAclSuppressed(probeActive: Boolean, suppressUntilMs: Long, nowMs: Long): Boolean =
        probeActive || nowMs < suppressUntilMs

    // --- Engine state ------------------------------------------------------------------------

    /** At or above this the alternator is charging: the engine is (very likely) running. */
    const val RUNNING_MIN_VOLTS = 13.2

    /** At or below this the battery is resting: the engine is off, no need to wake the bus. */
    const val OFF_MAX_VOLTS = 12.9

    /** Below this an RPM reading is an engine that is not running (ignition on, engine off). */
    const val RUNNING_MIN_RPM = 300.0

    enum class Engine { RUNNING, OFF, UNKNOWN }

    /**
     * Fast verdict from `ATRV`, or null when the voltage cannot decide and RPM must be asked.
     * Only [Engine.OFF] is trusted from voltage alone (a clearly resting battery); a charging
     * voltage is confirmed with RPM, since a battery maintainer or surface charge right after
     * switching off can read high too. Readings outside the plausible car range (clones answer
     * with their own 0 V / 3.3 V / 5 V rail) are ignored.
     */
    fun engineFromVoltage(volts: Double?): Engine? {
        if (volts == null) return null
        if (volts !in ObdConnectionPolicy.MIN_PLAUSIBLE_BATTERY_VOLTS..ObdConnectionPolicy.MAX_PLAUSIBLE_BATTERY_VOLTS) {
            return null
        }
        return if (volts <= OFF_MAX_VOLTS) Engine.OFF else null
    }

    /**
     * Verdict from the reply to `010C`: a parsed RPM decides; `NO DATA` / `UNABLE TO CONNECT` /
     * a failed bus search means the ignition is off; silence says nothing.
     */
    fun engineFromRpm(rpm: Double?, replied: Boolean): Engine = when {
        rpm != null -> if (rpm >= RUNNING_MIN_RPM) Engine.RUNNING else Engine.OFF
        replied -> Engine.OFF
        else -> Engine.UNKNOWN
    }

    // --- Decisions ---------------------------------------------------------------------------

    /** What one probe found. */
    enum class Outcome {
        /** No socket could be opened: the dongle is out of range or unpowered. */
        ABSENT,

        /** A socket opened but the adapter never answered (a hung clone). */
        UNRESPONSIVE,

        /** The adapter answered and the engine is off. */
        ENGINE_OFF,

        /** The adapter answered and the engine is running: a drive is underway. */
        ENGINE_RUNNING,
    }

    /** Why a scheduled probe was skipped without touching Bluetooth, or null to probe. */
    enum class Skip { DISABLED, NO_DEVICE, NO_PERMISSION, BLUETOOTH_OFF, ALREADY_LOGGING }

    fun skipReason(
        enabled: Boolean,
        autoConnect: Boolean,
        hasDevice: Boolean,
        hasPermission: Boolean,
        bluetoothOn: Boolean,
        loggingActive: Boolean,
    ): Skip? = when {
        !enabled || !autoConnect -> Skip.DISABLED
        !hasDevice -> Skip.NO_DEVICE
        !hasPermission -> Skip.NO_PERMISSION
        !bluetoothOn -> Skip.BLUETOOTH_OFF
        loggingActive -> Skip.ALREADY_LOGGING
        else -> null
    }

    enum class Action {
        /** Start the logging service for the dongle. */
        START_LOGGING,

        /** The drive the user disconnected from is over: lift the manual-disconnect latch. */
        CLEAR_MANUAL_DISCONNECT,

        NOTHING,
    }

    /**
     * How long the dongle must stay unreachable before that alone counts as "the drive is over".
     * One missed probe proves little (a clone that hung mid-drive looks exactly like an absent one).
     */
    const val ABSENT_ENDS_DRIVE_MS = 30L * 60 * 1000

    /**
     * What to do with a probe's [outcome]. A manual disconnect is honoured for the rest of that
     * drive only: while the engine keeps running nothing restarts logging, and once the car is
     * seen off (or the dongle has been out of reach for [ABSENT_ENDS_DRIVE_MS]) the latch is
     * lifted, so the next drive is logged again without the user reconnecting by hand.
     *
     * @param absentForMs how long the dongle has been continuously unreachable, including this
     *   probe (0 when it was reachable last time).
     */
    fun afterProbe(outcome: Outcome, manualDisconnect: Boolean, absentForMs: Long = 0L): Action = when (outcome) {
        Outcome.ENGINE_RUNNING -> if (manualDisconnect) Action.NOTHING else Action.START_LOGGING
        Outcome.ENGINE_OFF -> if (manualDisconnect) Action.CLEAR_MANUAL_DISCONNECT else Action.NOTHING
        Outcome.ABSENT ->
            if (manualDisconnect && absentForMs >= ABSENT_ENDS_DRIVE_MS) Action.CLEAR_MANUAL_DISCONNECT else Action.NOTHING
        Outcome.UNRESPONSIVE -> Action.NOTHING
    }
}
