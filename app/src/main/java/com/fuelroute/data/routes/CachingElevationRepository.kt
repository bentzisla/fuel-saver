package com.fuelroute.data.routes

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory LRU cache in front of [GoogleElevationRepository], keyed by (polyline, samples) -
 * mirrors [CachingRoutesRepository]. Elevation for a given path never changes, so entries use a
 * long TTL rather than the routes cache's 10-minute one.
 */
@Singleton
class CachingElevationRepository @Inject constructor(
    private val delegate: GoogleElevationRepository,
) : ElevationRepository {

    private data class Key(val encodedPolyline: String, val samples: Int)
    private data class Entry(val profile: List<Double>, val storedAtMs: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    override suspend fun profile(encodedPolyline: String, samples: Int): List<Double>? {
        val key = Key(encodedPolyline, samples)
        val now = System.currentTimeMillis()

        mutex.withLock {
            val entry = cache[key]
            if (entry != null) {
                if (now - entry.storedAtMs <= TTL_MS) return entry.profile
                cache.remove(key)
            }
        }

        val profile = delegate.profile(encodedPolyline, samples) ?: return null
        mutex.withLock {
            cache[key] = Entry(profile, System.currentTimeMillis())
            while (cache.size > MAX_ENTRIES) {
                val lru = cache.entries.firstOrNull() ?: break
                cache.remove(lru.key)
            }
        }
        return profile
    }

    private companion object {
        const val MAX_ENTRIES = 32
        const val TTL_MS = 24 * 60 * 60 * 1000L
    }
}
