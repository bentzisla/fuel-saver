package com.fuelroute.domain.obd

/**
 * Rate-based PID polling plan for the OBD run loop.
 *
 * Polling all nine PIDs (plus `ATRV`) sequentially every loop took well over 2 s per iteration on
 * slow K-line/KWP buses (~300-500 ms per PID), and the learning aggregator drops any sample with
 * dt > 2 s, so such cars learned nothing. Instead:
 *
 *  - every loop: speed, RPM and the ONE best fuel-rate source (5E > MAF > MAP for speed-density);
 *  - slowly changing values on their own interval ([SLOW_INTERVALS_MS]): coolant, IAT, engine
 *    load, fuel level. Between polls the last reply is held (see [reply]) so the cold-engine
 *    filter and speed-density still see a value;
 *  - an optional PID that answers `NO DATA` [dropAfterNoData] times in a row while the bus is
 *    alive (speed/RPM answered in the same loop) is dropped for the rest of the session. Speed
 *    and RPM are never dropped: the ignition-off logic depends on them.
 *
 * When PID negotiation failed only the mandatory trio (speed, RPM, coolant) is polled, as before.
 * Pure Kotlin, not thread-safe (owned by one loop); rebuild it after re-negotiation.
 */
class PidScheduler(
    private val supportedPids: Set<Int>,
    private val negotiationFailed: Boolean,
    private val dropAfterNoData: Int = DROP_AFTER_NO_DATA,
) {
    private val lastPolledMs = mutableMapOf<Int, Long>()
    private val lastReply = mutableMapOf<Int, String>()
    private val noDataStreak = mutableMapOf<Int, Int>()
    private val droppedPids = mutableSetOf<Int>()

    /** PIDs removed from the loop after repeated `NO DATA`. */
    val dropped: Set<Int>
        get() = droppedPids

    private fun optionalAllowed(pid: Int): Boolean =
        !negotiationFailed && pid in supportedPids && pid !in droppedPids

    /** The fuel-rate PID polled every loop, or null when none is usable. */
    fun fuelSourcePid(): Int? = FUEL_SOURCES.firstOrNull { optionalAllowed(it) }

    /** PIDs to send this iteration, in send order (speed first). */
    fun pidsDue(nowMs: Long): List<Int> {
        val due = mutableListOf(ElmProtocol.PID_SPEED, ElmProtocol.PID_RPM)
        fuelSourcePid()?.let { due += it }
        for ((pid, intervalMs) in SLOW_INTERVALS_MS) {
            if (pid in due || pid in droppedPids) continue
            // Coolant is mandatory (cold-engine filter), everything else must be advertised.
            if (pid != ElmProtocol.PID_COOLANT_TEMP && !optionalAllowed(pid)) continue
            val last = lastPolledMs[pid]
            if (last == null || nowMs - last >= intervalMs) due += pid
        }
        return due
    }

    /**
     * Records the raw [reply] to [pid] polled at [nowMs]. [busAlive] = speed or RPM parsed to a
     * valid value in the same loop; only then does `NO DATA` count toward dropping the PID (with
     * the ignition off EVERY PID says `NO DATA`, which says nothing about support).
     */
    fun record(pid: Int, reply: String, nowMs: Long, busAlive: Boolean) {
        lastPolledMs[pid] = nowMs
        lastReply[pid] = reply
        when {
            isNoData(reply) && busAlive -> {
                val streak = (noDataStreak[pid] ?: 0) + 1
                noDataStreak[pid] = streak
                if (streak >= dropAfterNoData && pid !in NEVER_DROPPED) droppedPids += pid
            }
            reply.isNotBlank() && !isNoData(reply) -> noDataStreak[pid] = 0
        }
    }

    /**
     * The reply to parse for [pid] this iteration: the fresh one when polled now, else the last
     * one while it is still within the PID's hold (twice its interval plus a second); `""` when
     * there is none or it is stale.
     */
    fun reply(pid: Int, nowMs: Long): String {
        val polledAt = lastPolledMs[pid] ?: return ""
        val holdMs = SLOW_INTERVALS_MS[pid]?.let { it * 2 + 1_000L } ?: 0L
        return if (nowMs - polledAt <= holdMs) lastReply[pid].orEmpty() else ""
    }

    companion object {
        /** Consecutive `NO DATA` replies (with the bus alive) before an optional PID is dropped. */
        const val DROP_AFTER_NO_DATA = 5

        /** Fuel-rate sources in priority order; only the first usable one is polled. */
        val FUEL_SOURCES = listOf(ElmProtocol.PID_FUEL_RATE, ElmProtocol.PID_MAF, ElmProtocol.PID_MAP)

        /** Slow PIDs and their poll interval. */
        val SLOW_INTERVALS_MS: Map<Int, Long> = linkedMapOf(
            ElmProtocol.PID_COOLANT_TEMP to 5_000L,
            ElmProtocol.PID_INTAKE_TEMP to 10_000L,
            ElmProtocol.PID_ENGINE_LOAD to 30_000L,
            ElmProtocol.PID_FUEL_LEVEL to 30_000L,
        )

        private val NEVER_DROPPED = setOf(ElmProtocol.PID_SPEED, ElmProtocol.PID_RPM)

        fun isNoData(reply: String): Boolean =
            reply.contains("NO DATA", ignoreCase = true) || reply.contains("NODATA", ignoreCase = true)
    }
}
