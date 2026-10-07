package com.saver.enphaselive

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class HistoryChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bars = JSONArray()
    private var period = "day"
    var selected = -1
        private set
    var isLoading = false
        private set
    private var loadingMessage = "Loading history..."
    private var emptyMessage = "Connect Enphase history in Settings"
    private val colors = mapOf(
        "production" to Color.rgb(0, 182, 211),
        "consumption" to Color.rgb(248, 125, 38),
        "import" to Color.rgb(143, 155, 166),
        "export" to Color.rgb(143, 155, 166),
        "charge" to Color.rgb(124, 205, 65),
        "discharge" to Color.rgb(124, 205, 65)
    )

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showLoading(message: String) {
        isLoading = true
        loadingMessage = message
        selected = -1
        invalidate()
    }

    fun update(history: JSONObject) {
        isLoading = false
        period = history.optString("period", "day")
        bars = history.optJSONArray("bars") ?: JSONArray()
        selected = -1
        emptyMessage = "No historical readings returned for this period"
        contentDescription = "Historical energy chart, ${bars.length()} intervals. Solar, imports and battery discharge above zero. Consumption, exports and battery charging below zero."
        invalidate()
    }

    fun message(value: String) {
        isLoading = false
        emptyMessage = value
        if (bars.length() == 0) invalidate()
    }

    fun clearSelection() {
        if (selected != -1) {
            selected = -1
            invalidate()
        }
    }

    private fun amount(bar: JSONObject, key: String): Double =
        bar.optDouble(key, 0.0).let { if (it.isFinite() && it >= 0) it else 0.0 }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val left = 44 * d
        val right = width - 8 * d
        val top = 22 * d
        val bottom = height - 36 * d
        if (right <= left || bottom <= top) return

        var limit = 0.0
        var available = false
        for (i in 0 until bars.length()) {
            val b = bars.optJSONObject(i) ?: continue
            val pos = listOf("production", "import", "discharge").sumOf { amount(b, it) }
            val neg = listOf("consumption", "export", "charge").sumOf { amount(b, it) }
            limit = max(limit, max(pos, neg))
            if (HistoryParser.metrics.any { b.has(it) && !b.isNull(it) }) available = true
        }

        // Y-axis unit
        paint.textSize = 12 * d
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.rgb(147, 172, 186)
        c.drawText("kWh", 4 * d, 16 * d, paint)

        if (!available && !isLoading) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 14 * d
            c.drawText(emptyMessage, width / 2f, height / 2f, paint)
            return
        }

        limit = max(1.0, kotlin.math.ceil(limit / 5) * 5)
        val zero = (top + bottom) / 2f
        val half = (bottom - top) / 2f

        // Grid lines
        paint.strokeWidth = d
        for (tick in -2..2) {
            val value = limit * tick / 2
            val y = zero - (half * tick / 2).toFloat()
            paint.color = Color.rgb(36, 56, 70)
            c.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(147, 172, 186)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 11 * d
            c.drawText(String.format(Locale.US, "%.0f", abs(value)), left - 7 * d, y + 4 * d, paint)
        }

        if (bars.length() > 0) {
            val step = (right - left) / bars.length()
            val barWidth = step * .72f

            // 1. Draw selection highlight column if selected
            if (selected in 0 until bars.length()) {
                val hx = left + selected * step + step * .14f
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(45, 86, 216, 238)
                c.drawRoundRect(hx - 3 * d, top, hx + barWidth + 3 * d, bottom, 4 * d, 4 * d, paint)
            }

            // 2. Draw bars
            paint.style = Paint.Style.FILL
            for (i in 0 until bars.length()) {
                val b = bars.optJSONObject(i) ?: continue
                val x = left + i * step + step * .14f
                var positive = zero
                var negative = zero

                for (key in listOf("production", "import", "discharge")) {
                    val h = (amount(b, key) / limit * half).toFloat()
                    paint.color = colors.getValue(key)
                    c.drawRect(x, positive - h, x + barWidth, positive, paint)
                    positive -= h
                }
                for (key in listOf("consumption", "export", "charge")) {
                    val h = (amount(b, key) / limit * half).toFloat()
                    paint.color = colors.getValue(key)
                    c.drawRect(x, negative, x + barWidth, negative + h, paint)
                    negative += h
                }

                if (i % max(1, bars.length() / 8) == 0 || i == bars.length() - 1) {
                    paint.color = Color.rgb(147, 172, 186)
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 11 * d
                    c.drawText(b.optString("label"), x + barWidth / 2, bottom + 22 * d, paint)
                }
            }

            // 3. Draw Floating Tooltip Overlay if selected
            if (selected in 0 until bars.length()) {
                val b = bars.optJSONObject(selected)
                if (b != null) {
                    drawTooltip(c, d, left, right, top, bottom, selected, step, barWidth, b)
                }
            }
        }

        // 4. Loading overlay if currently loading
        if (isLoading) {
            drawLoadingState(c, d)
        }
    }

    private fun drawTooltip(
        c: Canvas, d: Float, left: Float, right: Float, top: Float, bottom: Float,
        index: Int, step: Float, barWidth: Float, bar: JSONObject
    ) {
        val dateStr = bar.optString("date")
        val labelStr = bar.optString("label")
        val isMonthView = period == "month"

        val title = if (isMonthView) {
            runCatching {
                LocalDate.parse(dateStr).format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy"))
            }.getOrDefault("Day $labelStr")
        } else {
            val hour = labelStr.toIntOrNull()
            if (hour != null) {
                val h12 = if (hour == 0) "12 AM" else if (hour < 12) "$hour AM" else if (hour == 12) "12 PM" else "${hour - 12} PM"
                val nextH = (hour + 1) % 24
                val nextH12 = if (nextH == 0) "12 AM" else if (nextH < 12) "$nextH AM" else if (nextH == 12) "12 PM" else "${nextH - 12} PM"
                val dayShort = runCatching { LocalDate.parse(dateStr).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrDefault("")
                "$dayShort · $h12 - $nextH12"
            } else {
                "$dateStr $labelStr"
            }
        }

        val prod = amount(bar, "production")
        val cons = amount(bar, "consumption")
        val net = prod - cons

        val tipW = 380f * d
        val tipH = 50f * d
        val barCenterX = left + index * step + step * .14f + barWidth / 2f
        val tipX = (barCenterX - tipW / 2f).coerceIn(left + 4 * d, right - tipW - 4 * d)
        val tipY = top + 6 * d

        // Shadow
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 0, 0, 0)
        c.drawRoundRect(tipX + 2 * d, tipY + 3 * d, tipX + tipW + 2 * d, tipY + tipH + 3 * d, 10 * d, 10 * d, paint)

        // Card background
        paint.color = Color.rgb(14, 30, 42)
        c.drawRoundRect(tipX, tipY, tipX + tipW, tipY + tipH, 10 * d, 10 * d, paint)

        // Card stroke
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = Color.rgb(45, 80, 102)
        c.drawRoundRect(tipX, tipY, tipX + tipW, tipY + tipH, 10 * d, 10 * d, paint)

        // Header: Date Title
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(235, 245, 250)
        paint.textSize = 12 * d
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        c.drawText(title, tipX + 12 * d, tipY + 18 * d, paint)

        // Dismiss icon: ✕
        paint.color = Color.rgb(140, 165, 180)
        paint.textSize = 11 * d
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("✕", tipX + tipW - 12 * d, tipY + 18 * d, paint)

        // Metrics Row:
        val yMet = tipY + 37 * d
        paint.textSize = 11 * d
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT

        // 1. Produced
        paint.color = Color.rgb(0, 182, 211)
        c.drawText("● ${String.format(Locale.US, "%.1f", prod)} kWh Prod", tipX + 12 * d, yMet, paint)

        // 2. Consumed
        paint.color = Color.rgb(248, 125, 38)
        c.drawText("● ${String.format(Locale.US, "%.1f", cons)} kWh Home", tipX + 135 * d, yMet, paint)

        // 3. Net
        val netStr = if (net >= 0) "+${String.format(Locale.US, "%.1f", net)} kWh Exp" else "${String.format(Locale.US, "%.1f", abs(net))} kWh Imp"
        paint.color = if (net >= 0) Color.rgb(135, 229, 177) else Color.rgb(169, 185, 247)
        c.drawText("● $netStr", tipX + 265 * d, yMet, paint)
    }

    private fun drawLoadingState(c: Canvas, d: Float) {
        val pillW = 260 * d
        val pillH = 46 * d
        val pillX = (width - pillW) / 2f
        val pillY = (height - pillH) / 2f

        // Backdrop pill
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 14, 28, 38)
        c.drawRoundRect(pillX, pillY, pillX + pillW, pillY + pillH, 23 * d, 23 * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = Color.rgb(42, 75, 95)
        c.drawRoundRect(pillX, pillY, pillX + pillW, pillY + pillH, 23 * d, 23 * d, paint)

        // Rotating cyan spinner
        val now = SystemClock.uptimeMillis()
        val spinAngle = (now % 1000L) / 1000f * 360f
        val spinCx = pillX + 24 * d
        val spinCy = pillY + pillH / 2f
        val spinR = 10 * d
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * d
        paint.color = Color.rgb(86, 216, 238)
        val rectF = RectF(spinCx - spinR, spinCy - spinR, spinCx + spinR, spinCy + spinR)
        c.drawArc(rectF, spinAngle, 270f, false, paint)

        // Loading message
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(235, 245, 250)
        paint.textSize = 12 * d
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        c.drawText(loadingMessage, pillX + 44 * d, spinCy + 4.5f * d, paint)

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.length() == 0 || isLoading) return super.onTouchEvent(event)
        val d = resources.displayMetrics.density
        val left = 44 * d
        val right = width - 8 * d
        if (right <= left) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // If tapping close icon on top right of tooltip
                if (selected != -1 && event.action == MotionEvent.ACTION_DOWN) {
                    val tipY = 22 * d + 6 * d
                    val tipH = 50 * d
                    val tipW = 380 * d
                    val step = (right - left) / bars.length()
                    val barCenterX = left + selected * step + step * .14f + (step * .72f) / 2f
                    val tipX = (barCenterX - tipW / 2f).coerceIn(left + 4 * d, right - tipW - 4 * d)
                    if (event.x >= tipX + tipW - 36 * d && event.x <= tipX + tipW + 6 * d &&
                        event.y >= tipY && event.y <= tipY + tipH
                    ) {
                        selected = -1
                        invalidate()
                        return true
                    }
                }

                if (event.x in left..right) {
                    val step = (right - left) / bars.length()
                    val newIndex = ((event.x - left) / step).toInt().coerceIn(0, bars.length() - 1)
                    if (event.action == MotionEvent.ACTION_DOWN && newIndex == selected) {
                        selected = -1
                    } else {
                        selected = newIndex
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
