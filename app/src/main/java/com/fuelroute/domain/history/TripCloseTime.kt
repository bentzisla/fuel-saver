package com.fuelroute.domain.history

/**
 * Pure rule behind `TripDao.closeOpenTrips`: a trip whose `endedAtMs` was already checkpointed
 * (i.e. it no longer equals `startedAtMs`) keeps that real end time. Only a trip that never
 * advanced past its start is stamped with the close time.
 *
 * Without this, a crash/force-kill left open for days would be closed "now" and its duration
 * inflated from the real end to the recovery time.
 */
object TripCloseTime {

    fun preservedEndMs(startedAtMs: Long, endedAtMs: Long, nowMs: Long): Long =
        if (endedAtMs == startedAtMs) nowMs else endedAtMs
}