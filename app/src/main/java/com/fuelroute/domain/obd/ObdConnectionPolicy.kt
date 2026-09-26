package com.fuelroute.domain.obd

/**
 * Pure decision rules for the whole OBD connect lifecycle, shared by the Bluetooth transport,
 * the engine, and the foreground logging service. Kept free of Android/coroutine types so it
 * is unit-testable on the JVM.
 *
 * (This merges the former `ConnectPolicy`, which had grown as a second, overlapping object
 * during parallel remediation work.)
 */
object ObdConnectionPolicy {

    // --- Connect lifecycle -----------------------------------------------------------------

    /** Hard upper bound on a single Bluetooth connect attempt. */
    const val CONNECT_TIMEOUT_MS = 15_000L

    // --- Per-command deadlines ---------------------------------------------------------------
    //
    // Every ELM exchange has a hard deadline enforced by the transport (see `ElmLink`): when it
    // expires the link is CLOSED, because closing the socket is the only reliable way to
    // unblock a thread parked in `BluetoothSocket.read()`. A timed-out command therefore always
    // means "the adapter stopped answering; reconnect", never "try the next command". The
    // windows are deliberately generous: a healthy ELM327 always ends a reply with the `>`
    // prompt within its own bus timeout (ATST), so only a hung/silent adapter can hit them.

    /**
     * Deadline for a non-reset ELM initialization command (`ATE0`, `ATSP0`, …). A real adapter
     * answers in tens of ms; Bluetooth latency spikes on clones can reach several hundred ms,
     * which the previous 600 ms window mistook for a dead dongle.
     */
    const val INIT_READ_TIMEOUT_MS = 2_000L

    /**
     * `ATZ`/`ATWS` reset the whole adapter and take noticeably longer than the other init
     * commands (a real ELM327 answers in ~1 s, clones can be slower), so they get their own
     * window instead of being aborted by the short [INIT_READ_TIMEOUT_MS].
     */
    const val ATZ_READ_TIMEOUT_MS = 5_000L

    /** Default deadline for a run-loop poll / PID negotiation / VIN read. */
    const val COMMAND_TIMEOUT_MS = 10_000L

    /**
     * Deadline for the first data request after `ATSP0`. Automatic protocol search walks every
     * bus (slow ISO 9141 / KWP 5-baud inits take seconds each), so this is far longer than a
     * normal poll.
     */
    const val PROTOCOL_SEARCH_TIMEOUT_MS = 20_000L

    /** Deadline for the sacrificial line-clear command sent right after the socket opens. */
    const val LINE_CLEAR_TIMEOUT_MS = 2_000L

    /** Quiet time after the line-clear reply so late/stale bytes arrive and get drained. */
    const val LINE_CLEAR_SETTLE_MS = 200L

    /** Pause between a rejected `ATZ` banner and the next reset attempt. */
    const val RESET_RETRY_PAUSE_MS = 300L

    /** Best-effort `ATPC` sent before closing the socket on a clean stop. */
    const val CLOSE_PROTOCOL_TIMEOUT_MS = 800L

    /** Per-command init read window: [ATZ_READ_TIMEOUT_MS] for resets, else [INIT_READ_TIMEOUT_MS]. */
    fun initReadTimeoutMs(command: String): Long =
        if (isResetCommand(command)) ATZ_READ_TIMEOUT_MS else INIT_READ_TIMEOUT_MS

    /** `ATZ` (full reset) and `ATWS` (warm start) both reboot the ELM firmware. */
    fun isResetCommand(command: String): Boolean {
        val c = command.trim().uppercase()
        return c == "ATZ" || c == "ATWS"
    }

    // --- Protocol settle (after ATSP0) -------------------------------------------------------

    /** Total data requests tried while waiting for the automatic protocol search to lock. */
    const val PROTOCOL_SETTLE_ATTEMPTS = 3

    /** Pause after `ATPC` before retrying a failed protocol search. */
    const val PROTOCOL_RETRY_PAUSE_MS = 500L

    /**
     * True when a failed protocol search should be retried (after `ATPC`). Only bus-level
     * failures (`UNABLE TO CONNECT`, `BUS INIT: ...ERROR`, `CAN ERROR`, `STOPPED`, a bare
     * `SEARCHING...`) are worth retrying; `NO DATA` means the bus is up but the ECU is quiet,
     * and no reply at all means the link was closed by the deadline.
     *
     * @param attempt 1-based number of the request that just failed.
     */
    fun shouldRetryProtocolSearch(outcome: ElmProtocol.SearchOutcome, attempt: Int): Boolean =
        outcome == ElmProtocol.SearchOutcome.BUS_ERROR && attempt < PROTOCOL_SETTLE_ATTEMPTS

    // --- RFCOMM hygiene ----------------------------------------------------------------------

    /**
     * Minimum quiet time between closing an RFCOMM socket to a dongle and opening the next one.
     * Cheap ELM327 clones accept exactly one RFCOMM connection and need about a second to
     * release the channel; connecting sooner is refused (or silently accepted and never
     * answered), which looks like "only a power-cycle fixes it".
     */
    const val RFCOMM_RELEASE_MS = 1_000L

    /** How long to wait before the next socket connect, given the previous close time. */
    fun rfcommReleaseWaitMs(lastCloseAtMs: Long?, nowMs: Long): Long {
        if (lastCloseAtMs == null) return 0L
        val elapsed = nowMs - lastCloseAtMs
        if (elapsed < 0) return RFCOMM_RELEASE_MS
        return (RFCOMM_RELEASE_MS - elapsed).coerceAtLeast(0L)
    }

    // --- Engine stop -------------------------------------------------------------------------

    /** Time the run loop gets to notice `stopRequested` and exit by itself (sends `ATPC`). */
    const val STOP_GRACE_MS = 1_500L

    /** Time a cancelled run loop gets to finish its cleanup before the transport is forced shut. */
    const val STOP_CANCEL_JOIN_MS = 2_000L

    /** Final wait after force-closing the transport; beyond it the stuck job is abandoned. */
    const val STOP_FORCE_JOIN_MS = 3_000L

    /**
     * A freshly started logging service never stops itself during this window, so the engine
     * state flow's initial `Disconnected` value cannot kill it before the first `Connecting`
     * emission (the `Disconnected -> Connecting` transient).
     */
    const val STARTUP_GRACE_MS = 2_000L

    /** Engine status names, mirrored from `ObdStatus` for the pure policy. */
    const val STATUS_DISCONNECTED = "Disconnected"
    const val STATUS_CONNECTING = "Connecting"
    const val STATUS_CONNECTED = "Connected"
    const val STATUS_ERROR = "Error"

    // --- Connect retry / workaround chain (card 35) ----------------------------------------

    /** How many times the full connect workaround chain is tried before giving up. */
    const val CONNECT_ATTEMPTS = 3

    /** Short pause between full connect-chain retries (not the long reconnect backoff). */
    const val CONNECT_RETRY_BACKOFF_MS = 750L

    /**
     * Ordered Bluetooth SPP socket strategies. Cheap ELM327 dongles often reject the secure
     * service-record lookup but accept the insecure variant, or only the raw channel 1.
     */
    enum class ConnectVariant { SECURE_RFCOMM, INSECURE_RFCOMM, CHANNEL_1 }

    /** The workaround order: secure -> insecure -> reflection channel 1. */
    fun connectVariants(): List<ConnectVariant> =
        listOf(ConnectVariant.SECURE_RFCOMM, ConnectVariant.INSECURE_RFCOMM, ConnectVariant.CHANNEL_1)

    /** Number of attempts the transport makes for a single `connect()` call. */
    fun connectAttempts(): Int = CONNECT_ATTEMPTS

    /** True while another full-chain connect attempt is allowed ([attempt] is 1-based). */
    fun shouldRetryConnect(attempt: Int): Boolean = attempt < CONNECT_ATTEMPTS

    // --- Reconnect / backoff ---------------------------------------------------------------

    /** Consecutive empty/failed speed replies that trigger a reconnect. */
    const val RECONNECT_AFTER_FAILURES = 5

    /** Give up reconnecting after this total wait; status becomes `Error`. */
    const val MAX_RECONNECT_WINDOW_MS = 180_000L

    /** Backoff never grows beyond this. */
    const val BACKOFF_CAP_MS = 60_000L

    /** Reconnect attempts after a dropped link before the status becomes `Error`. */
    const val MAX_RECONNECT_ATTEMPTS = 3

    fun shouldReconnect(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= RECONNECT_AFTER_FAILURES

    // --- Mid-drive bad-reply escalation ladder ----------------------------------------------
    //
    // [shouldReconnect] alone used to be the whole story: 5 consecutive bad replies and the
    // run loop tore the RFCOMM socket down and reconnected. On a cheap single-channel clone on
    // a slow bus (K-line/ISO9141/KWP2000, ~300-500ms per PID) that is trigger-happy — and once
    // any ONE command truly times out, [com.fuelroute.data.obd.ElmLink]'s own watchdog has
    // *already* closed the socket to unblock the parked read (that is the one reliable way to
    // free a thread stuck in a non-cancellable `BluetoothSocket.read()`), so every subsequent
    // command in the same streak returns instantly, and the socket is dead well before the
    // engine "decides" to reconnect. There was no cheaper recovery in between a single bad
    // reply and a full teardown.
    //
    // This ladder adds a real middle step and switches the final decision from a raw count to
    // an elapsed-time window, so a short run of bad replies (a couple of genuinely slow PIDs,
    // a late/mis-synced reply) gets time to clear on its own, or a chance at a cheap
    // non-destructive resync, before the RFCOMM link is torn down.

    /**
     * Minimum duration of an unbroken bad-reply streak, with the transport link still reporting
     * open, before trying a non-destructive resync (drain + soft `ATPC`) instead of jumping to
     * a full reconnect. Below this the streak is treated as a transient blip.
     */
    const val SOFT_RESYNC_AFTER_BAD_MS = 3_000L

    /**
     * Deadline for the non-destructive resync command itself (`ATPC` via the soft, non-blocking
     * exchange): short, because it must not itself burn into the reconnect budget below.
     */
    const val SOFT_RESYNC_TIMEOUT_MS = 500L

    /**
     * Only once a bad streak has lasted this long — with no valid reply in all that time — is a
     * full RFCOMM teardown + reconnect worth its cost. Chosen at the low end of the requested
     * 8-10s window: long enough that a slow-but-alive bus (or the one-shot soft resync above)
     * has a real chance to recover, short enough that a genuinely dead link is not mistaken for
     * a merely slow one for too long.
     */
    const val RECONNECT_AFTER_BAD_MS = 9_000L

    /** Next step of the mid-drive bad-reply escalation ladder (see [nextBadStreakAction]). */
    enum class BadStreakAction {
        /** Keep polling; the streak has not lasted long enough to act on yet. */
        WAIT,

        /** Try a one-shot non-destructive resync (drain + soft `ATPC`) on the SAME socket. */
        SOFT_RESYNC,

        /** Give up on this socket: tear it down and reconnect. */
        RECONNECT,
    }

    /**
     * Decides the next escalation step for an unbroken bad-reply streak.
     *
     * @param badStreakMs how long the current streak has run (`nowMs - streakStartedMs`).
     * @param linkOpen whether the transport still reports its link open. When false, the
     *   per-command watchdog already tore the socket down chasing a genuine timeout — there is
     *   no cheaper option left, so this returns [BadStreakAction.RECONNECT] immediately no
     *   matter how short the streak, instead of waiting out the rest of [RECONNECT_AFTER_BAD_MS]
     *   against a socket that no longer exists.
     * @param softResyncAttempted whether [BadStreakAction.SOFT_RESYNC] was already acted on once
     *   for this streak — it is a one-shot try, not repeated every poll.
     */
    fun nextBadStreakAction(
        badStreakMs: Long,
        linkOpen: Boolean,
        softResyncAttempted: Boolean,
    ): BadStreakAction {
        if (!linkOpen) return BadStreakAction.RECONNECT
        return when {
            badStreakMs >= RECONNECT_AFTER_BAD_MS -> BadStreakAction.RECONNECT
            badStreakMs >= SOFT_RESYNC_AFTER_BAD_MS && !softResyncAttempted -> BadStreakAction.SOFT_RESYNC
            else -> BadStreakAction.WAIT
        }
    }

    /**
     * True while a dropped link should still be re-established: reconnect attempts remain
     * AND the dongle is still ACL-connected. Hammering an absent dongle is pointless.
     *
     * @param attempt number of reconnect attempts already made (0-based).
     * @param deviceConnected whether the adapter still reports the dongle as connected.
     */
    fun shouldRetryReconnect(attempt: Int, deviceConnected: Boolean): Boolean =
        attempt < MAX_RECONNECT_ATTEMPTS && deviceConnected

    /**
     * Engine reconnect gate: like [shouldRetryReconnect], but the FIRST attempt is always made.
     * An SPP-only ELM327 has an ACL link only while one of our sockets is open, so right after
     * the engine (or the read watchdog) closed the socket the ACL is usually down already —
     * that says nothing about whether the dongle is still there. One attempt is not hammering.
     */
    fun shouldAttemptReconnect(attempt: Int, deviceConnected: Boolean): Boolean =
        attempt == 0 || shouldRetryReconnect(attempt, deviceConnected)

    /** Exponential backoff 2, 4, 8, 16, 32 s then a 60 s plateau (attempt >= 5). */
    fun backoffDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 5)
        return (2_000L shl safeAttempt).coerceAtMost(BACKOFF_CAP_MS)
    }

    /**
     * True while the engine's reconnect backoff loop should keep trying. Once a stop is
     * requested (the engine's `stopRequested` flag set by `stop()`/`disconnect()`/`reset()`)
     * the loop must return without further attempts, even if a blocking `connect()` just
     * returned.
     */
    fun shouldContinueReconnect(stopRequested: Boolean): Boolean = !stopRequested

    // --- Ignition-off detection ------------------------------------------------------------

    /** RPM must stay unavailable this long before we treat the ignition as off. */
    const val IGNITION_OFF_RPM_TIMEOUT_MS = 60_000L

    /** Below this voltage the engine cannot be running. */
    const val LOW_BATTERY_VOLTS = 11.5

    /**
     * At/below this speed the vehicle is treated as stationary when deciding whether a missing
     * RPM reply may be used as ignition-off evidence.
     */
    const val STATIONARY_SPEED_KMH = 1.0

    /**
     * True when an unavailable RPM reading may be accumulated toward the ignition-off timeout.
     *
     * Some clones simply do not answer PID 0C, so if RPM absence always armed the 60 s timer the
     * engine would stop itself mid-drive. RPM absence is therefore only meaningful when the PID
     * is actually supported (or negotiation failed and we fall back to the mandatory trio), or
     * when the vehicle is stationary and a missing RPM is expected.
     */
    fun shouldTrackRpmAbsence(rpmPidSupported: Boolean, speedKmh: Double?): Boolean =
        rpmPidSupported || (speedKmh ?: 0.0) < STATIONARY_SPEED_KMH

    /**
     * Plausible automotive battery voltage window. Readings outside it are adapter noise:
     * cheap ELM327 clones answer `ATRV` with their internal logic rail (0 V / 3.3 V / 5 V)
     * instead of the car's battery, so they must not be mistaken for a dead battery.
     */
    const val MIN_PLAUSIBLE_BATTERY_VOLTS = 8.0
    const val MAX_PLAUSIBLE_BATTERY_VOLTS = 16.0

    /**
     * True when the sample stream says the engine is off. RPM is the authoritative signal: it
     * must be missing for at least [IGNITION_OFF_RPM_TIMEOUT_MS]. Voltage is only a secondary
     * signal, and only when the reading lies inside [MIN_PLAUSIBLE_BATTERY_VOLTS]..
     * [MAX_PLAUSIBLE_BATTERY_VOLTS] and is below [LOW_BATTERY_VOLTS] — a clone reporting 0 V
     * or 3.3 V is ignored rather than read as a dead battery.
     */
    fun shouldStopForIgnitionOff(
        rpmNullSinceMs: Long?,
        nowMs: Long,
        batteryVoltage: Double?,
    ): Boolean {
        val rpmMissingLongEnough =
            rpmNullSinceMs != null && nowMs - rpmNullSinceMs >= IGNITION_OFF_RPM_TIMEOUT_MS
        val plausibleLowVoltage = batteryVoltage != null &&
            batteryVoltage in MIN_PLAUSIBLE_BATTERY_VOLTS..MAX_PLAUSIBLE_BATTERY_VOLTS &&
            batteryVoltage < LOW_BATTERY_VOLTS
        return rpmMissingLongEnough || plausibleLowVoltage
    }

    // --- Logging-service lifecycle ---------------------------------------------------------

    /**
     * True when the logging service should stop itself because the engine reached a terminal
     * state.
     *
     * @param status engine status name (`Disconnected`, `Connecting`, `Connected`, `Error`).
     * @param elapsedMs time since the service started logging.
     * @param sawData whether the engine ever reached `Connecting`/`Connected`, i.e. the connect
     *   attempt was actually entered. Once true a terminal state is unambiguous and stops
     *   immediately; when false we still wait out [STARTUP_GRACE_MS] so a slow start does not
     *   kill the service on the initial `Disconnected` emission.
     */
    fun shouldAutoStop(status: String, elapsedMs: Long, sawData: Boolean): Boolean {
        val terminal = status == STATUS_ERROR || status == STATUS_DISCONNECTED
        if (!terminal) return false
        if (sawData) return true
        return elapsedMs >= STARTUP_GRACE_MS
    }

    // --- Auto-connect gating ---------------------------------------------------------------

    /**
     * True when an adapter `STATE_ON` broadcast should auto-start for [target].
     *
     * A resolved bonded/last-used target is not enough: the adapter must also be ACL-connected,
     * so merely turning Bluetooth on with a paired-but-absent dongle does not summon the
     * foreground notification.
     */
    fun shouldAutoStartOnAdapterOn(target: String?, connectedAddresses: Collection<String>): Boolean {
        val address = target?.takeIf { it.isNotBlank() } ?: return false
        return connectedAddresses.any { it.equals(address, ignoreCase = true) }
    }

    /**
     * True when every auto-connect entry point must be suppressed because the user explicitly
     * disconnected (card 34). The latch is sticky and is only cleared by an explicit reconnect,
     * so this returns the latch verbatim.
     */
    fun shouldSuppressAutoConnect(manualDisconnect: Boolean): Boolean = manualDisconnect

    /**
     * True when an `ACL_DISCONNECTED` for the target dongle must NOT stop logging because our
     * own connect/init is in progress. SPP-only ELM327 dongles have an ACL link only while one
     * of our sockets is open, so each failed socket variant / init reopen makes it flap.
     */
    fun shouldIgnoreAclDisconnect(connectInProgress: Boolean): Boolean = connectInProgress

    // --- Mid-session auto-reconnect --------------------------------------------------------

    /**
     * Upper bound on consecutive service-level reconnect re-arms after a terminal drop. Bounded
     * so a genuinely absent/failing dongle (or a parked car with the ignition off) cannot spin
     * the foreground service forever.
     */
    const val MAX_AUTO_RECONNECT_ATTEMPTS = 5

    /**
     * True when the logging service should re-arm the engine after a terminal drop instead of
     * stopping itself. Requires all of:
     *
     *  - the user enabled auto-connect,
     *  - the user did not explicitly disconnect (the sticky [shouldSuppressAutoConnect] latch),
     *  - the dongle is still reachable (ACL-connected / a known last device address), and
     *  - the bounded attempt budget has not been exhausted ([attempt] is 0-based).
     *
     * @param attempt number of automatic re-arms already made in this logging session.
     */
    fun shouldAutoReconnect(
        attempt: Int,
        autoConnect: Boolean,
        manualDisconnect: Boolean,
        deviceConnected: Boolean,
    ): Boolean = autoConnect &&
        !manualDisconnect &&
        deviceConnected &&
        attempt < MAX_AUTO_RECONNECT_ATTEMPTS

    // --- Machine error codes ---------------------------------------------------------------

    /** Machine error codes surfaced through `LiveObdState.lastError`. */
    const val ERROR_CONNECT = "CONNECT"
    const val ERROR_CONNECT_TIMEOUT = "CONNECT TIMEOUT"
    const val ERROR_SOCKET_CLOSED = "SOCKET CLOSED"
    const val ERROR_SECURITY = "SECURITY"
    const val ERROR_SEARCHING = "SEARCHING"

    /** An ELM init command produced no reply within [INIT_READ_TIMEOUT_MS]. */
    const val ERROR_INIT_TIMEOUT = "INIT TIMEOUT"

    /** Writing an init command failed: the socket connected but the link is already dead. */
    const val ERROR_INIT_WRITE_FAILED = "INIT WRITE FAILED"

    /** The dongle closed the RFCOMM channel (EOF) during init: typically a stale/half-open link. */
    const val ERROR_INIT_EOF = "INIT EOF"

    /** The read failed with an IO error during init. */
    const val ERROR_INIT_READ_ERROR = "INIT READ ERROR"

    /** The link was already closed when an init command was due. */
    const val ERROR_INIT_LINK_CLOSED = "INIT LINK CLOSED"

    /**
     * Machine error code for an init command that produced no reply, by cause. `null` (a
     * transport that cannot tell) keeps the legacy [ERROR_INIT_TIMEOUT].
     */
    fun initErrorCode(failure: ElmLinkFailure?): String = when (failure) {
        null, ElmLinkFailure.TIMEOUT -> ERROR_INIT_TIMEOUT
        ElmLinkFailure.WRITE_FAILED -> ERROR_INIT_WRITE_FAILED
        ElmLinkFailure.EOF -> ERROR_INIT_EOF
        ElmLinkFailure.READ_ERROR -> ERROR_INIT_READ_ERROR
        ElmLinkFailure.LINK_CLOSED -> ERROR_INIT_LINK_CLOSED
    }

    /**
     * How many times a connect+init session is attempted before its failure is surfaced. The
     * second attempt starts from a fully closed socket after [RFCOMM_RELEASE_MS], which is what
     * a manual unplug/replug used to achieve for single-link clones.
     */
    const val SESSION_OPEN_ATTEMPTS = 2

    /** True when a failed connect+init should be retried once more ([attempt] is 1-based). */
    fun shouldRetrySessionOpen(attempt: Int): Boolean = attempt < SESSION_OPEN_ATTEMPTS
}
