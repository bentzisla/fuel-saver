package com.fuelroute.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

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
}

@Dao
interface RefuelDao {

    @Insert
    suspend fun insert(refuel: RefuelEntity)

    @Query("SELECT * FROM refuel ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RefuelEntity>

    @Query("SELECT COALESCE(SUM(liters), 0.0) FROM refuel WHERE isFull = 1 AND vehicleId = :vehicleId")
    suspend fun totalFullLiters(vehicleId: String): Double
}

@Dao
interface RouteSearchDao {

    @Insert
    suspend fun insert(search: RouteSearchEntity)

    @Query("SELECT * FROM route_search ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RouteSearchEntity>
}