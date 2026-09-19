package com.fuelroute.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "obd_sample")
data class ObdSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val timestampMs: Long,
    val speedKmh: Double?,
    val rpm: Double?,
    val mafGps: Double?,
    val fuelRateLph: Double?,
    val mapKpa: Double?,
    val intakeTempC: Double?,
    val coolantTempC: Double?,
    val engineLoadPct: Double?,
    val fuelLevelPct: Double?,
)

@Entity(tableName = "speed_bin_stats", primaryKeys = ["vehicleId", "binIndex"])
data class SpeedBinStatsEntity(
    val vehicleId: String,
    val binIndex: Int,
    val distanceKm: Double,
    val fuelL: Double,
    val seconds: Double,
    val samples: Int,
)

@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val idleSeconds: Double,
)

@Entity(tableName = "refuel")
data class RefuelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val timestampMs: Long,
    val liters: Double,
    val totalPrice: Double,
    val isFull: Boolean,
)

@Entity(tableName = "route_search")
data class RouteSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originLabel: String,
    val destinationLabel: String,
    val timestampMs: Long,
    val cheapestCost: Double,
    val fastestCost: Double,
    val savedAmount: Double,
    val predictedLiters: Double,
    val distanceKm: Double,
    val durationMin: Double,
)