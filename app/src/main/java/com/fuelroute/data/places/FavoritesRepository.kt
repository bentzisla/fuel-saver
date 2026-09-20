package com.fuelroute.data.places

import com.fuelroute.data.db.FavoriteDestinationDao
import com.fuelroute.data.db.FavoriteDestinationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Room-free favorite destination used by the UI layer. */
data class FavoriteDestination(
    val id: Long,
    val label: String,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sortOrder: Int = 0,
    val createdAtMs: Long = 0L,
)

/**
 * Durable, user-curated list of destinations ("בית", "עבודה", ...).
 *
 * Distinct from [PlacesHistoryRepository]: favorites are named, ordered and never evicted, and they
 * survive an app restart because they live in the Room `favorite_destination` table.
 *
 * TODO(1.5): include the `favorite_destination` table in BackupRepository export/import and dedupe
 * on import by `placeId`, then normalized label, once the backup feature lands.
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: FavoriteDestinationDao,
) {
    /** Ordered by [FavoriteDestination.sortOrder] then creation time (the DAO already orders rows). */
    val favorites: Flow<List<FavoriteDestination>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Pins a destination. Dedupes by `placeId` when both sides have one, otherwise by normalized
     * label, so starring the same place from different surfaces never creates a duplicate. A
     * duplicate refreshes its coordinates instead of inserting a new row.
     */
    suspend fun add(label: String, placeId: String?, lat: Double?, lng: Double?) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return
        val all = dao.observeAll().first().map { it.toDomain() }
        val existing = all.firstOrNull { matches(it, placeId, trimmed) }
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    label = trimmed,
                    placeId = placeId ?: existing.placeId,
                    latitude = lat ?: existing.latitude,
                    longitude = lng ?: existing.longitude,
                ).toEntity(),
            )
            return
        }
        val nextOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsert(
            FavoriteDestinationEntity(
                label = trimmed,
                placeId = placeId,
                latitude = lat,
                longitude = lng,
                sortOrder = nextOrder,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Renames an existing favorite without touching its `placeId`/coordinates. */
    suspend fun rename(id: Long, label: String) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return
        val existing = dao.observeAll().first().firstOrNull { it.id == id } ?: return
        dao.upsert(existing.copy(label = trimmed))
    }

    suspend fun remove(id: Long) {
        val existing = dao.observeAll().first().firstOrNull { it.id == id } ?: return
        dao.delete(existing)
    }

    /**
     * Moves [id] to [newSortOrder], then renumbers the whole list `0..n-1` so ordering stays
     * deterministic regardless of gaps left by deletions.
     */
    suspend fun move(id: Long, newSortOrder: Int) {
        val ordered = dao.observeAll().first()
            .sortedWith(compareBy({ it.sortOrder }, { it.createdAtMs }))
            .toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return
        val item = ordered.removeAt(from)
        val target = newSortOrder.coerceIn(0, ordered.size)
        ordered.add(target, item)
        ordered.forEachIndexed { index, entity ->
            if (entity.sortOrder != index) dao.updateSortOrder(entity.id, index)
        }
    }

    suspend fun isFavorite(placeId: String?, label: String): Boolean = find(placeId, label) != null

    /** The favorite matching an exact place, or a normalized label when no `placeId` is known. */
    suspend fun find(placeId: String?, label: String): FavoriteDestination? {
        val all = dao.observeAll().first().map { it.toDomain() }
        return all.firstOrNull { matches(it, placeId, label) }
    }

    private fun FavoriteDestinationEntity.toDomain() = FavoriteDestination(
        id = id,
        label = label,
        placeId = placeId,
        latitude = latitude,
        longitude = longitude,
        sortOrder = sortOrder,
        createdAtMs = createdAtMs,
    )

    private fun FavoriteDestination.toEntity() = FavoriteDestinationEntity(
        id = id,
        label = label,
        placeId = placeId,
        latitude = latitude,
        longitude = longitude,
        sortOrder = sortOrder,
        createdAtMs = createdAtMs,
    )

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Trim + lowercase + collapse whitespace, so "  Home " and "home" are the same label. */
        fun normalizeLabel(label: String): String =
            label.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

        /** True when [favorite] refers to the same place as the (`placeId`, `label`) pair. */
        fun matches(favorite: FavoriteDestination, placeId: String?, label: String): Boolean =
            if (!favorite.placeId.isNullOrBlank() && !placeId.isNullOrBlank()) {
                favorite.placeId == placeId
            } else {
                normalizeLabel(favorite.label) == normalizeLabel(label)
            }
    }
}