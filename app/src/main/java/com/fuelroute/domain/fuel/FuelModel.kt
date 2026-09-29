package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.TrafficResolution
import kotlin.math.max
import kotlin.math.min

class FuelModel(
    private val curve: ConsumptionCurve,
    private val idleLitersPerHour: Double,
    private val overrides: FuelModelOverrides = FuelModelOverrides.DEFAULT,
    /** Vehicle curb weight for the grade term ([GradeModel]); see [VehicleProfile.massKg]. */
    private val massKg: Double = GradeModel.DEFAULT_VEHICLE_MASS_KG,
    /** Fuel energy density for the grade term; pass [GradeModel.DIESEL_MJ_PER_L] for diesel. */
    private val energyDensityMjPerL: Double = GradeModel.GASOLINE_MJ_PER_L,
) {

    private val stopGoWeight: Double get() = overrides.effectiveStopGoWeight

    private val idleRate: Double get() = idleLitersPerHour.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private val slowFactor: Double get() = sanitizeFactor(overrides.effectiveSlowFactor, ModelConstants.SLOW_FACTOR)

    private val jamFactor: Double get() = sanitizeFactor(overrides.effectiveJamFactor, ModelConstants.JAM_FACTOR)

    /**
     * Tightest lower clamp for a legacy (no per-level breakdown) segment's speed factor: the
     * smaller of the configured slow/jam factors, so lowering either slows every congested segment.
     */
    private val congestionFloor: Double get() = min(slowFactor, jamFactor)

    /**
     * One homogeneous stretch of a step: a single congestion level at the step's free-flow speed.
     * A step with a per-level breakdown yields up to three pieces (NORMAL/SLOW/JAM), otherwise one.
     */
    private class Piece(
        val distanceKm: Double,
        val staticSeconds: Double,
        /** Time at the modelled congested speed, before normalizing to the route duration. */
        val rawSeconds: Double,
    ) {
        /** Delay Google's congestion adds on this piece (0 for NORMAL). */
        val congestionDelaySeconds: Double get() = max(0.0, rawSeconds - staticSeconds)
    }

    /** Normalized timing of a route: per-piece seconds plus stationary delay charged at idle. */
    private class Timing(
        val pieces: List<List<Piece>>,
        val pieceSeconds: List<DoubleArray>,
        val stationarySeconds: Double,
    )

    /** Per-segment normalized seconds plus the stationary (idle) delay, for tests. */
    internal data class TimingSummary(
        val segmentSeconds: List<Double>,
        val stationarySeconds: Double,
    )

    fun cost(
        route: Route,
        pricePerLiter: Double,
        coldStartLiters: Double = 0.0,
    ): RouteCost {
        val rate = pricePerLiter.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val correction = overrides.effectiveFuelCorrection.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val coldStart = coldStartLiters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        val routeDistanceKm = (route.distanceMeters / 1000.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val routeDurationSeconds = route.durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val routeAvgSpeedKmh = if (routeDurationSeconds > 0.0) {
            routeDistanceKm / (routeDurationSeconds / 3600.0)
        } else {
            0.0
        }
        val timing = timing(route)
        // Stop-go penalty only where Google's traffic data says there is congestion. Without
        // traffic data every piece is NORMAL, and the unexplained delay is charged as idle time.
        val applyStopGo = route.trafficResolution != TrafficResolution.NONE
        val stationaryLiters = idleRate * timing.stationarySeconds / 3600.0
        val totalRawSeconds = timing.pieces.sumOf { pieces -> pieces.sumOf { it.rawSeconds } }
        var fuelLiters = coldStart * correction
        val segmentCosts = mutableListOf<SegmentCost>()

        route.segments.forEachIndexed { index, segment ->
            val distanceKm = (segment.distanceMeters / 1000.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val pieces = timing.pieces[index]
            val seconds = timing.pieceSeconds[index]
            var baseLiters = 0.0
            var stopGoLiters = 0.0
            var movingSeconds = 0.0
            pieces.forEachIndexed { p, piece ->
                val t = seconds[p]
                val speed = pieceSpeed(piece.distanceKm, t, routeAvgSpeedKmh)
                baseLiters += piece.distanceKm * curve.litersPer100Km(speed) / 100.0
                if (applyStopGo && piece.staticSeconds > 0.0) {
                    val extraHours = max(0.0, (t - piece.staticSeconds) / 3600.0)
                    stopGoLiters += idleRate * extraHours * stopGoWeight
                }
                movingSeconds += t
            }
            // Stationary delay is not tied to a place; attribute it by each step's share of the
            // raw time so the per-step breakdown still adds up to the route total.
            val stationaryShare = if (totalRawSeconds > 0.0) {
                stationaryLiters * pieces.sumOf { it.rawSeconds } / totalRawSeconds
            } else if (route.segments.isNotEmpty()) {
                stationaryLiters / route.segments.size
            } else {
                0.0
            }
            val gradeLiters = segment.elevationDeltaM?.let {
                GradeModel.extraLiters(it, massKg, energyDensityMjPerL, distanceMeters = segment.distanceMeters)
            } ?: 0.0
            // A descent's grade credit may exceed this segment's own liters, but it must never
            // make the *segment* cheaper than free (PLAN.md §4.4 / GradeModel).
            val segmentLiters = max(0.0, baseLiters + stopGoLiters + stationaryShare + gradeLiters) * correction
            fuelLiters += segmentLiters
            val effectiveSpeed = pieceSpeed(distanceKm, movingSeconds, routeAvgSpeedKmh)
            segmentCosts += SegmentCost(
                distanceKm = distanceKm,
                effectiveSpeedKmh = effectiveSpeed,
                congestion = segment.congestion,
                // Distance-weighted over the step's sub-segments, not the curve at the mean speed
                // (the curve is convex, so the latter would understate a mixed step).
                litersPer100Km = if (distanceKm > 0.0) {
                    baseLiters / distanceKm * 100.0
                } else {
                    curve.litersPer100Km(effectiveSpeed)
                },
                liters = segmentLiters,
            )
        }
        // A route with no segments still pays for its stationary delay (none today, but keep the
        // total consistent if that ever changes).
        if (route.segments.isEmpty()) fuelLiters += stationaryLiters * correction

        val toll = (route.tollCost ?: 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val fuelCost = fuelLiters * rate
        val durationMinutes = routeDurationSeconds / 60.0

        return RouteCost(
            route = route,
            fuelLiters = fuelLiters,
            fuelCost = fuelCost,
            tollCost = toll,
            totalCost = fuelCost + toll,
            durationMinutes = durationMinutes,
            distanceKm = routeDistanceKm,
            avgSpeedKmh = routeAvgSpeedKmh,
            segments = segmentCosts,
        )
    }

    internal fun timingSummary(route: Route): TimingSummary {
        val timing = timing(route)
        return TimingSummary(
            segmentSeconds = timing.pieceSeconds.map { it.sum() },
            stationarySeconds = timing.stationarySeconds,
        )
    }

    private fun pieceSpeed(distanceKm: Double, seconds: Double, routeAvgSpeedKmh: Double): Double = when {
        distanceKm <= 0.0 -> 0.0
        seconds > 0.0 -> distanceKm / (seconds / 3600.0)
        // Missing/zero staticDuration: use the route's average speed instead of 0, which would
        // charge the whole step at the curve's crawl rate.
        routeAvgSpeedKmh > 0.0 -> routeAvgSpeedKmh
        else -> 0.0
    }

    /**
     * Fits the modelled piece times to Google's traffic-aware route `duration` without distorting
     * speeds where there is no evidence of traffic.
     *
     * - Model too fast (Google is slower): the residual delay goes to the congested pieces first,
     *   in proportion to their own congestion delay, never below [MIN_QUEUE_SPEED_KMH]. Whatever
     *   is left (no congested pieces, or they are already at crawl speed) is stationary delay,
     *   charged at the idle rate. Uniformly slowing every step instead would lower a highway
     *   step's L/100km and so favour highway routes for delay that is really queueing.
     * - Model too slow (Google is faster): the congested pieces are sped up, in proportion to
     *   their congestion delay, at most back to free-flow. NORMAL pieces are never pushed above
     *   their free-flow speed; if even free-flow everywhere exceeds `duration` (Google's
     *   `duration` can undercut `staticDuration` in light traffic), the times stay at free-flow.
     */
    private fun timing(route: Route): Timing {
        val pieces = route.segments.map { piecesOf(it) }
        val seconds = pieces.map { list -> DoubleArray(list.size) { list[it].rawSeconds } }
        val target = route.durationSeconds
        val sumRaw = pieces.sumOf { list -> list.sumOf { it.rawSeconds } }
        if (!target.isFinite() || target <= 0.0 || !sumRaw.isFinite() || sumRaw <= 0.0) {
            return Timing(pieces, seconds, 0.0)
        }

        val flat = pieces.flatMapIndexed { s, list -> list.indices.map { p -> s to p } }
        val delta = target - sumRaw
        val totalCongestionDelay = pieces.sumOf { list -> list.sumOf { it.congestionDelaySeconds } }
        var stationary = 0.0

        if (delta > 0.0) {
            // Water-filling: share the delay by congestion delay among pieces with room left.
            var remaining = delta
            val room = HashMap<Pair<Int, Int>, Double>()
            for ((s, p) in flat) {
                val piece = pieces[s][p]
                if (piece.congestionDelaySeconds <= 0.0 || piece.distanceKm <= 0.0) continue
                val floorSeconds = piece.distanceKm / MIN_QUEUE_SPEED_KMH * 3600.0
                val capacity = floorSeconds - piece.rawSeconds
                if (capacity > 0.0) room[s to p] = capacity
            }
            while (remaining > EPS_SECONDS && room.isNotEmpty()) {
                val weightSum = room.keys.sumOf { (s, p) -> pieces[s][p].congestionDelaySeconds }
                if (weightSum <= 0.0) break
                var given = 0.0
                val filled = mutableListOf<Pair<Int, Int>>()
                for ((key, capacity) in room) {
                    val (s, p) = key
                    val share = remaining * pieces[s][p].congestionDelaySeconds / weightSum
                    val add = min(share, capacity)
                    seconds[s][p] += add
                    given += add
                    if (add >= capacity) filled += key else room[key] = capacity - add
                }
                filled.forEach { room.remove(it) }
                remaining -= given
                if (filled.isEmpty()) break
            }
            stationary = max(0.0, remaining)
        } else if (delta < 0.0 && totalCongestionDelay > 0.0) {
            val reduction = min(-delta, totalCongestionDelay)
            for ((s, p) in flat) {
                val piece = pieces[s][p]
                val cut = reduction * piece.congestionDelaySeconds / totalCongestionDelay
                seconds[s][p] = max(piece.staticSeconds, piece.rawSeconds - cut)
            }
        }
        return Timing(pieces, seconds, stationary)
    }

    private fun piecesOf(segment: RouteSegment): List<Piece> {
        val distanceM = segment.distanceMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val staticSeconds = segment.staticDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val slowM = segment.slowMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val jamM = segment.jamMeters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        if (distanceM <= 0.0 || slowM + jamM <= 0.0) {
            val factor = legacyCongestionFactor(segment)
            val raw = if (staticSeconds > 0.0) staticSeconds / factor else 0.0
            return listOf(Piece(distanceM / 1000.0, staticSeconds, raw))
        }

        // Rounding can make the breakdown a hair longer than the step: shrink it to fit.
        val congestedM = slowM + jamM
        val fit = if (congestedM > distanceM) distanceM / congestedM else 1.0
        val lengths = listOf(
            (distanceM - congestedM * fit).coerceAtLeast(0.0) to ModelConstants.NORMAL_FACTOR,
            slowM * fit to slowFactor,
            jamM * fit to jamFactor,
        )
        return lengths.filter { it.first > 0.0 }.map { (meters, factor) ->
            val share = meters / distanceM
            val pieceStatic = staticSeconds * share
            Piece(
                distanceKm = meters / 1000.0,
                staticSeconds = pieceStatic,
                rawSeconds = if (pieceStatic > 0.0) pieceStatic / factor else 0.0,
            )
        }
    }

    /**
     * Speed factor for a segment with no per-level breakdown (built by hand or by an older
     * mapper): maps its [RouteSegment.congestionFactor] onto the configured slow/jam severities
     * while preserving how mixed the segment is.
     *
     * Scaling the factor's deficit from `NORMAL_FACTOR` by the ratio of the override deficit to
     * the default deficit for the segment's dominant level keeps a partially-congested segment
     * partially congested, and turns a purely-SLOW segment into exactly the slow override (and
     * likewise for jam). The result is clamped to `[min(slow, jam), NORMAL_FACTOR]`.
     */
    private fun legacyCongestionFactor(segment: RouteSegment): Double {
        val factor = segment.congestionFactor
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, ModelConstants.NORMAL_FACTOR)
            ?: ModelConstants.NORMAL_FACTOR
        val (defaultLevel, overrideLevel) = when (segment.congestion) {
            CongestionLevel.SLOW -> ModelConstants.SLOW_FACTOR to slowFactor
            CongestionLevel.TRAFFIC_JAM -> ModelConstants.JAM_FACTOR to jamFactor
            CongestionLevel.NORMAL -> ModelConstants.NORMAL_FACTOR to ModelConstants.NORMAL_FACTOR
        }
        val defaultDeficit = ModelConstants.NORMAL_FACTOR - defaultLevel
        if (defaultDeficit <= 0.0) return factor.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
        val scale = (ModelConstants.NORMAL_FACTOR - overrideLevel) / defaultDeficit
        if (!scale.isFinite()) return factor.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
        val scaled = ModelConstants.NORMAL_FACTOR - (ModelConstants.NORMAL_FACTOR - factor) * scale
        return scaled.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
    }

    companion object {
        /**
         * Slowest speed the residual-delay allocation may push a congested piece to. Delay beyond
         * that is treated as standing still (idle), not as ever-slower driving.
         */
        const val MIN_QUEUE_SPEED_KMH = 5.0

        /** Smallest usable congestion speed factor; guards overrides of 0 or below. */
        private const val MIN_FACTOR = 0.05

        private const val EPS_SECONDS = 1e-6

        private fun sanitizeFactor(value: Double, default: Double): Double =
            value.takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(MIN_FACTOR, ModelConstants.NORMAL_FACTOR)
                ?: default
    }
}
