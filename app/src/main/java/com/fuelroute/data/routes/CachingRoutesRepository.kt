package com.fuelroute.data.routes

import com.fuelroute.domain.model.Route
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory LRU cache in front of [GoogleRoutesRepository]. The key buckets the departure
 * time into 10-minute slots so repeated lookups within a slot are served locally; entries
 * expire after [ttlMs]. [RoutesRepository.getAlternatives] with `forceRefresh = true` skips
 * the cache entirely (the UI "רענן" button) and re-populates it.
 */
@Singleton
class CachingRoutesRepository @Inject constructor(
    private val delegate: GoogleRoutesRepository,
) : RoutesRepository {

    private data class Key(
        val origin: String,
        val destination: String,
        val departureBucket: Long,
        val emissionType: String?,
        val via: List<Pair<Double, Double>>,
    )

    private data class Entry(val routes: List<Route>, val storedAtMs: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    override suspend fun getAlternatives(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions,
        forceRefresh: Boolean,
    ): List<Route> {
        val now = System.currentTimeMillis()
        val key = Key(
            origin = origin.cacheKey(),
            destination = destination.cacheKey(),
            departureBucket = (options.departureTimeMs ?: now) / BUCKET_MS,
            emissionType = options.emissionType,
            via = options.via,
        )

        if (!forceRefresh) {
            mutex.withLock {
                val entry = cache[key]
                if (entry != null) {
                    if (now - entry.storedAtMs <= TTL_MS) return entry.routes
                    cache.remove(key)
                }
            }
        }

        val routes = delegate.getAlternatives(origin, destination, options, forceRefresh)
        mutex.withLock {
            cache[key] = Entry(routes, System.currentTimeMillis())
            while (cache.size > MAX_ENTRIES) {
                val lru = cache.entries.firstOrNull() ?: break
                cache.remove(lru.key)
            }
        }
        return routes
    }

    private fun RouteWaypoint.cacheKey(): String = when {
        !placeId.isNullOrBlank() -> "place:$placeId"
        latitude != null && longitude != null -> "ll:${round(latitude)},${round(longitude)}"
        !address.isNullOrBlank() -> "addr:${address.trim().lowercase()}"
        else -> "empty"
    }

    private fun round(value: Double): String = "%.5f".format(value)

    private companion object {
        const val MAX_ENTRIES = 32
        const val TTL_MS = 10 * 60 * 1000L
        const val BUCKET_MS = 10 * 60 * 1000L
    }
}
