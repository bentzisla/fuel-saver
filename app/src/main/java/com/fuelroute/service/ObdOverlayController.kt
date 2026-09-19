package com.fuelroute.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.fuelroute.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small draggable bubble drawn over other apps (needs SYSTEM_ALERT_WINDOW).
 * It shows the live fuel consumption while the OBD logging service runs in the
 * background. Tap opens the app, dragging repositions it, and the ✕ dismisses it.
 */
class ObdOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubble: BubbleView? = null

    val isShowing: Boolean
        get() = bubble != null

    fun show(onTap: () -> Unit) {
        if (bubble != null) return

        val view = BubbleView(context)
        view.onTap = onTap
        view.onClose = ::hide

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        bubble = view
        windowManager.addView(view, params)
    }

    fun hide() {
        val view = bubble ?: return
        bubble = null
        runCatching { windowManager.removeView(view) }
    }

    fun updateSpeed(speedKmh: Double?) = bubble?.setSpeed(speedKmh)

    fun updateConsumption(litersPer100Km: Double?) = bubble?.setConsumption(litersPer100Km)

    /**
     * The overlay view itself; handles its own dragging by moving its window
     * params. Text children are not clickable so touches on them reach here and
     * drive drag/tap. The close button handles its own click.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class BubbleView(context: Context) : LinearLayout(context) {

        var onTap: (() -> Unit)? = null
        var onClose: (() -> Unit)? = null

        private val valueText: TextView
        private val unitText: TextView
        private val speedText: TextView

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(220, 18, 18, 18))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }

            val close = TextView(context).apply {
                text = context.getString(R.string.overlay_close)
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END }
                setOnClickListener { onClose?.invoke() }
            }
            addView(close)

            valueText = TextView(context).apply {
                text = context.getString(R.string.overlay_no_data)
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(valueText)

            unitText = TextView(context).apply {
                text = context.getString(R.string.overlay_unit_l100)
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 11f
            }
            addView(unitText)

            speedText = TextView(context).apply {
                text = context.getString(R.string.overlay_no_data)
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
            }
            addView(speedText)
        }

        fun setConsumption(litersPer100Km: Double?) {
            valueText.text = if (litersPer100Km != null) {
                String.format(java.util.Locale.US, "%.1f", litersPer100Km)
            } else {
                context.getString(R.string.overlay_no_data)
            }
        }

        fun setSpeed(speedKmh: Double?) {
            speedText.text = if (speedKmh != null) {
                "${speedKmh.roundToInt()} ${context.getString(R.string.overlay_unit_kmh)}"
            } else {
                context.getString(R.string.overlay_no_data)
            }
        }

        private var initialRawX = 0f
        private var initialRawY = 0f
        private var initialParamsX = 0
        private var initialParamsY = 0
        private var moved = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialRawX = event.rawX
                    initialRawY = event.rawY
                    val params = layoutParams as WindowManager.LayoutParams
                    initialParamsX = params.x
                    initialParamsY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = layoutParams as WindowManager.LayoutParams
                    val dx = (event.rawX - initialRawX).roundToInt()
                    val dy = (event.rawY - initialRawY).roundToInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = initialParamsX + dx
                    params.y = initialParamsY + dy
                    context.getSystemService(WindowManager::class.java).updateViewLayout(this, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap?.invoke()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).roundToInt()
    }
}