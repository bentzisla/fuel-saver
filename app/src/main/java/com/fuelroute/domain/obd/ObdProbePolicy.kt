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

    /** Below this an RPM reading is an engine that is not running (ignition on, engine off). */
    const val RUNNING_MIN_RPM = 300.0

    enum class Engine { RUNNING, OFF, UNKNOWN }

    /** What an `ATRV` reading says on its own. */
    enum class Voltage {
        /** No reading, or outside the plausible car range (a clone's own 0 V / 3.3 V / 5 V rail). */
        UNKNOWN,

        /**
         * Below [RUNNING_MIN_VOLTS]: a resting battery OR a running engine with a smart
         * (regenerative) alternator, which holds 12.4-12.8 V while cruising. Never trusted as
         * "engine off" by itself.
         */
        AMBIGUOUS,

        /** At or above [RUNNING_MIN_VOLTS]: charging, but a maintainer or surface charge reads high too. */
        CHARGING,
    }

    /**
     * Classifies `ATRV`. It is diagnostic only: the probe always confirms with RPM (`010C`), since
     * neither band decides the engine state reliably (see [Voltage]).
     */
    fun classifyVoltage(volts: Double?): Voltage = when {
        volts == null -> Voltage.UNKNOWN
        volts !in ObdConnectionPolicy.MIN_PLAUSIBLE_BATTERY_VOLTS..ObdConnectionPolicy.MAX_PLAUSIBLE_BATTERY_VOLTS ->
            Voltage.UNKNOWN
        volts >= RUNNING_MIN_VOLTS -> Voltage.CHARGING
        else -> Voltage.AMBIGUOUS
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

        /**
         * The logging engine claimed the dongle while (or before) the probe held it; the probe
         * gave it up at once (see [com.fuelroute.data.obd.ObdDongleSession]). Logging is active.
         */
        YIELDED,
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
        Outcome.UNRESPONSIVE, Outcome.YIELDED -> Action.NOTHING
    }

    // --- Fallback notification --------------------------------------------------------------

    /**
     * Channel of the "drive detected, tap to start logging" notification shown when Android
     * refuses to start the logging service from the background worker. High importance (heads-up
     * with sound): the user has to act while the drive is on, a silent entry went unnoticed. A
     * channel's importance cannot be raised after creation, hence a new id.
     */
    const val FALLBACK_CHANNEL_ID = "obd_drive_detected_alert"

    /** The former silent (IMPORTANCE_LOW) channel, deleted when the new one is created. */
    const val LEGACY_FALLBACK_CHANNEL_ID = "obd_drive_detected"

    /** The fallback notification is dropped after this long: the drive it announced is likely over. */
    const val FALLBACK_NOTIFICATION_TIMEOUT_MS = 30L * 60 * 1000

    // --- Backoff and search avoidance --------------------------------------------------------

    /** After this many quiet probes in a row (absent / engine off) the interval grows to [BACKOFF_1_MIN]. */
    const val BACKOFF_1_AFTER = 3
    const val BACKOFF_1_MIN = 15

    /** After this many quiet probes in a row the interval grows to [BACKOFF_2_MIN]. */
    const val BACKOFF_2_AFTER = 6
    const val BACKOFF_2_MIN = 30

    /**
     * Delay before the next probe: the configured interval, stretched (never shortened) after a
     * streak of quiet probes, e.g. 5 -> 15 -> 30 min for a car parked overnight. The streak is
     * reset by a running engine, logging, an ACL connect of the dongle or the app being opened.
     */
    fun nextDelayMin(intervalMin: Int, quietStreak: Int): Int {
        val base = clampIntervalMin(intervalMin)
        val backoff = when {
            quietStreak >= BACKOFF_2_AFTER -> BACKOFF_2_MIN
            quietStreak >= BACKOFF_1_AFTER -> BACKOFF_1_MIN
            else -> base
        }
        return maxOf(base, backoff)
    }

    /** True when [quietStreak] already stretched the interval, i.e. a reset should reschedule. */
    fun isBackedOff(intervalMin: Int, quietStreak: Int): Boolean =
        nextDelayMin(intervalMin, quietStreak) > clampIntervalMin(intervalMin)

    /** Consecutive absent / engine-off probes after [outcome]. A hung clone neither extends nor ends it. */
    fun nextQuietStreak(current: Int, outcome: Outcome): Int = when (outcome) {
        Outcome.ABSENT, Outcome.ENGINE_OFF -> current + 1
        Outcome.ENGINE_RUNNING, Outcome.YIELDED -> 0
        Outcome.UNRESPONSIVE -> current
    }

    /** Consecutive engine-off probes after [outcome] (an absent dongle says nothing about the bus). */
    fun nextOffStreak(current: Int, outcome: Outcome): Int = when (outcome) {
        Outcome.ENGINE_OFF -> current + 1
        Outcome.ENGINE_RUNNING, Outcome.YIELDED -> 0
        Outcome.ABSENT, Outcome.UNRESPONSIVE -> current
    }

    /**
     * After this many engine-off probes in a row, a failed `010C` under the remembered protocol is
     * taken as "engine off" without falling back to the expensive automatic search: the car is
     * parked, and searching every protocol only wakes its bus again.
     */
    const val TRUST_PROTOCOL_AFTER_OFF = 3

    /**
     * Whether a failed RPM request under the remembered protocol should be retried with the
     * automatic search (`ATSP0`), which covers the dongle having moved to another car.
     */
    fun fallbackSearchAllowed(offStreak: Int): Boolean = offStreak < TRUST_PROTOCOL_AFTER_OFF
}
