package com.fuelroute.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {

    @Query("SELECT * FROM vehicle")
    fun getAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicle WHERE id = :id")
    suspend fun getById(id: String): VehicleEntity?

    @Upsert
    suspend fun upsert(vehicles: List<VehicleEntity>)

    @Delete
    suspend fun delete(vehicle: VehicleEntity)

    @Query("DELETE FROM vehicle WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM vehicle")
    suspend fun count(): Int
}

@Dao
interface LearningExtrasDao {

    @Query("SELECT * FROM learning_extras WHERE vehicleId = :vehicleId")
    suspend fun get(vehicleId: String): LearningExtrasEntity?

    @Upsert
    suspend fun upsert(entity: LearningExtrasEntity)

    @Query("DELETE FROM learning_extras WHERE vehicleId = :vehicleId")
    suspend fun reset(vehicleId: String)
}

@Dao
interface ObdSampleDao {

    @Insert
    suspend fun insert(sample: ObdSampleEntity)

    @Query("SELECT COUNT(*) FROM obd_sample")
    suspend fun count(): Int

    @Query("DELETE FROM obd_sample WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

@Dao
interface SpeedBinDao {

    @Upsert
    suspend fun upsertAll(bins: List<SpeedBinStatsEntity>)

    @Query("SELECT * FROM speed_bin_stats WHERE vehicleId = :vehicleId ORDER BY binIndex")
    suspend fun getForVehicle(vehicleId: String): List<SpeedBinStatsEntity>

    @Query("SELECT COALESCE(SUM(fuelL), 0.0) FROM speed_bin_stats WHERE vehicleId = :vehicleId")
    suspend fun totalFuelForVehicle(vehicleId: String): Double

    @Query("DELETE FROM speed_bin_stats WHERE vehicleId = :vehicleId")
    suspend fun resetForVehicle(vehicleId: String)
}

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity)

    @Query("SELECT * FROM trip ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TripEntity>

    @Query("SELECT * FROM trip WHERE isOpen = 1 ORDER BY startedAtMs DESC")
    suspend fun recentOpenTrips(): List<TripEntity>

    @Query(
        "SELECT * FROM route_search WHERE departureTimeMs >= :tripStartMs - :windowMs " +
            "AND departureTimeMs <= :tripStartMs ORDER BY departureTimeMs DESC LIMIT 1"
    )
    suspend fun findActiveRouteSearch(tripStartMs: Long, windowMs: Long): RouteSearchEntity?
}

@Dao
interface RefuelDao {

    @Insert
    suspend fun insert(refuel: RefuelEntity)

    @Query("SELECT * FROM refuel ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RefuelEntity>

    @Query("SELECT COALESCE(SUM(liters), 0.0) FROM refuel WHERE isFull = 1 AND vehicleId = :vehicleId")
    suspend fun totalFullLiters(vehicleId: String): Double

    @Query(
        "SELECT * FROM refuel WHERE isFull = 1 AND vehicleId = :vehicleId " +
            "AND timestampMs >= :sinceMs ORDER BY timestampMs ASC"
    )
    suspend fun fullRefuelsSince(vehicleId: String, sinceMs: Long): List<RefuelEntity>
}

@Dao
interface RouteSearchDao {

    @Insert
    suspend fun insert(search: RouteSearchEntity)

    @Query("SELECT * FROM route_search ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RouteSearchEntity>
}