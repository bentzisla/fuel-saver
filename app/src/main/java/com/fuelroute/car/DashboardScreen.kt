package com.fuelroute.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fuelroute.R
import com.fuelroute.data.obd.LiveObdState
import com.fuelroute.data.obd.ObdEngine
import com.fuelroute.data.obd.ObdStatus
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.obd.EmaSmoother
import com.fuelroute.domain.obd.LiveCostCalculator
import com.fuelroute.domain.obd.LiveDashboardValues
import com.fuelroute.service.ObdLoggingService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The car dashboard (card 15). A [PaneTemplate] with four glanceable Hebrew rows, refreshed at
 * ~1 Hz, plus a [MessageTemplate] when the dongle is not connected. It observes the singleton
 * [ObdEngine] only and never starts or stops the OBD connection itself.
 *
 * Driver-distraction limits honoured: four rows (the pane maximum), no scrolling list, no text
 * input, and one parked-safe action.
 */
@OptIn(FlowPreview::class)
class DashboardScreen(
    carContext: CarContext,
    private val engine: ObdEngine,
    private val fuelPriceRepository: FuelPriceRepository,
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
) : Screen(carContext) {

    private val fuelRateSmoother = EmaSmoother()
    private val speedSmoother = EmaSmoother()

    private var values: LiveDashboardValues? = null
    private var status: ObdStatus = ObdStatus.Disconnected
    private var lastError: String? = null
    private var pricePerLiter: Double = ModelConstants.DEFAULT_FUEL_PRICE
    private var lastRenderedStatus: ObdStatus? = null
    private var lastRenderedError: String? = null
    private var firstTemplateLogged = false

    init {
        // Confirms the host actually rendered the dashboard (card 42), after the session marker.
        Log.i(TAG, "car dashboard screen created")
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val vehicle = runCatching { vehicleRepository.active() }.getOrNull()
                val grade = vehicle?.grade ?: FuelGrades.GASOLINE_95
                combine(
                    engine.live,
                    fuelPriceRepository.price(grade),
                ) { state, price -> state to price.pricePerLiter }
                    .sample(DISPLAY_REFRESH_MS)
                    .collect { (state, price) -> onState(state, price) }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!firstTemplateLogged) {
            firstTemplateLogged = true
            // Distinguishes "screen created" from "host actually pulled a template" (card 45): if
            // this never appears, the host created the screen but never rendered it.
            Log.i(TAG, "car first template served (status=$status)")
        }
        return if (status == ObdStatus.Connected) {
            buildDashboard(values)
        } else {
            buildDisconnected()
        }
    }

    private fun onState(state: LiveObdState, price: Double) {
        pricePerLiter = price
        status = state.status
        lastError = state.lastError

        if (state.status == ObdStatus.Connected) {
            lastRenderedStatus = state.status
            lastRenderedError = state.lastError
            val display = valuesFor(state, price)
            if (LiveCostCalculator.shouldInvalidate(values, display)) {
                values = display
                invalidate()
            }
        } else {
            // Reset the smoother so a reconnect never flashes the previous trip's numbers.
            fuelRateSmoother.reset()
            speedSmoother.reset()
            values = null
            if (lastRenderedStatus != state.status || lastRenderedError != state.lastError) {
                lastRenderedStatus = state.status
                lastRenderedError = state.lastError
                invalidate()
            }
        }
    }

    private fun valuesFor(state: LiveObdState, price: Double): LiveDashboardValues {
        // The engine already smooths fuel and distance over a trip-computer window and only
        // reports L/100 km while really moving (instantL100 == null when crawling/idle). Never
        // recompute L/100 km here from a smoothed rate / smoothed speed: at 1-2 km/h that
        // division is what produced hundreds of L/100 km.
        val fuelRate = fuelRateSmoother.update(state.fuelRateLph)
        val speed = speedSmoother.update(state.speedKmh)
        val moving = state.instantL100 != null
        val consumption = if (moving) state.instantL100 else fuelRate
        return LiveDashboardValues(
            costPerHour = round(LiveCostCalculator.costPerHour(fuelRate, price), 1),
            consumption = consumption?.let { round(it, 1) },
            moving = moving,
            tripCost = round(LiveCostCalculator.tripCost(state.tripFuelL, price), 2),
            speedKmh = speed?.let { round(it, 0) },
            tripDistanceKm = round(state.tripDistanceKm, 1),
        )
    }

    /** Four rows — the [Pane] maximum — so nothing scrolls and everything is glanceable. */
    private fun buildDashboard(current: LiveDashboardValues?): Template {
        val costPerHour = current?.costPerHour ?: 0.0
        val consumption = current?.consumption
        val moving = current?.moving ?: true

        val consumptionText = when {
            consumption == null -> string(R.string.car_value_unknown)
            moving -> string(R.string.car_consumption_l100, format(consumption, 1))
            else -> string(R.string.car_consumption_lph, format(consumption, 1))
        }
        val speedText = current?.speedKmh
            ?.let { string(R.string.car_speed_value, format(it, 0)) }
            ?: string(R.string.car_value_unknown)
        val distanceText = current
            ?.let { string(R.string.car_distance_value, format(it.tripDistanceKm, 1)) }
            ?: string(R.string.car_value_unknown)

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(string(R.string.car_cost_per_hour))
                    .addText(string(R.string.car_money, format(costPerHour, 1)))
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle(string(R.string.car_consumption))
                    .addText(consumptionText)
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle(string(R.string.car_trip_cost))
                    .addText(string(R.string.car_money, format(current?.tripCost ?: 0.0, 2)))
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle(string(R.string.car_speed))
                    .addText(speedText)
                    .addText(distanceText)
                    .build(),
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(titleHeader())
            .build()
    }

    private fun buildDisconnected(): Template {
        val message = when (status) {
            ObdStatus.Connecting -> string(R.string.car_connecting)
            ObdStatus.Error -> lastError?.let { string(R.string.car_error, it) }
                ?: string(R.string.car_error_unknown)
            else -> lastError?.let { string(R.string.car_disconnected_reason, it) }
                ?: string(R.string.car_disconnected)
        }
        val builder = MessageTemplate.Builder(message)
            .setHeader(titleHeader())
        if (status != ObdStatus.Connecting) {
            builder.addAction(
                Action.Builder()
                    .setTitle(string(R.string.car_connect))
                    .setOnClickListener { startLogging() }
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * Single-tap, parked-safe action that asks the existing logging service (cards 07/14) to run.
     * This screen still does not own the connection: it just starts the service the same way the
     * phone UI does.
     */
    private fun startLogging() {
        lifecycleScope.launch {
            val address = runCatching {
                settingsRepository.settings.first().lastDeviceAddress
            }.getOrNull()
            ObdLoggingService.start(carContext, address, auto = true)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        carContext.getString(resId, *args)

    private fun titleHeader(): Header = Header.Builder()
        .setTitle(string(R.string.car_title))
        .build()

    private fun string(resId: Int): String = carContext.getString(resId)

    private fun format(value: Double, decimals: Int): String {
        if (!value.isFinite()) return string(R.string.car_value_unknown)
        return String.format(Locale.getDefault(), "%.${decimals}f", value)
    }

    private fun round(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return 0.0
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            else -> 100.0
        }
        return Math.round(value * factor) / factor
    }

    private companion object {
        const val TAG = "FuelRoute"

        /** ~1 Hz: the host rate-limits invalidate(), so coalesce the 4 Hz engine stream. */
        const val DISPLAY_REFRESH_MS = 1_000L
    }
}