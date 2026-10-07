package com.saver.enphaselive

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EnergyFlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var meters: JSONObject? = null
    private var disconnected = true
    private val cyan = Color.rgb(86, 216, 238)
    private val orange = Color.rgb(255, 196, 118)
    private val green = Color.rgb(135, 229, 177)
    private val purple = Color.rgb(169, 185, 247)
    private val white = Color.rgb(235, 245, 250)
    private val muted = Color.rgb(147, 172, 186)
    private var evPowerKw: Double? = null
    private var evMode: String? = null

    private var cachedGradTop = 0f
    private var cachedGradBottom = 0f
    private var cachedGradColor = 0
    private var cachedGradient: LinearGradient? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun update(data: JSONObject) {
        meters = data.optJSONObject("meters")
        disconnected = false
        contentDescription = "Solar ${power("pv")} kilowatts. Home ${power("load")} kilowatts. Grid ${power("grid")} kilowatts. Battery ${meters?.optInt("soc") ?: 0} percent."
        invalidate()
    }

    fun offline() {
        disconnected = true
        invalidate()
    }

    fun setEvCharger(powerKw: Double?, mode: String?) {
        this.evPowerKw = powerKw
        this.evMode = mode
        invalidate()
    }

    private fun power(name: String): Double? = meters?.optJSONObject(name)?.let {
        if (it.has("agg_p_mw")) it.optDouble("agg_p_mw") / 1e6 else null
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = white, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }

    /**
     * Maps battery charge percentage (0-100) to a continuous color transition
     * from warning Red (low), to Amber/Yellow (medium), to Emerald Green (high).
     */
    private fun getBatteryColor(soc: Int): Int {
        if (soc < 0) return green
        val clamped = soc.coerceIn(0, 100)
        return when {
            clamped <= 20 -> {
                // Low (< 20%): Warning Red
                Color.rgb(240, 68, 68) // #F04444
            }
            clamped <= 50 -> {
                // 20% to 50%: Red -> Yellow
                val t = (clamped - 20) / 30f
                val r = (240 + (250 - 240) * t).toInt()
                val g = (68 + (205 - 68) * t).toInt()
                val b = (68 + (45 - 68) * t).toInt()
                Color.rgb(r, g, b)
            }
            clamped <= 80 -> {
                // 50% to 80%: Yellow -> Lime/Green
                val t = (clamped - 50) / 30f
                val r = (250 + (100 - 250) * t).toInt()
                val g = (205 + (225 - 205) * t).toInt()
                val b = (45 + (120 - 45) * t).toInt()
                Color.rgb(r, g, b)
            }
            else -> {
                // 80% to 100%: Emerald Green
                val t = (clamped - 80) / 20f
                val r = (100 + (82 - 100) * t).toInt()
                val g = (225 + (229 - 225) * t).toInt()
                val b = (120 + (140 - 120) * t).toInt()
                Color.rgb(r, g, b)
            }
        }
    }

    /**
     * Renders a crisp vector battery icon with terminal nub and charge fill level.
     */
    private fun drawBatteryIcon(c: Canvas, cx: Float, cy: Float, soc: Int, batColor: Int) {
        val w = 24f
        val h = 13f
        val left = cx - w / 2f
        val top = cy - h / 2f
        val right = left + w - 3f
        val bottom = top + h

        // Outer battery shell
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = batColor
        c.drawRoundRect(left, top, right, bottom, 3f, 3f, paint)

        // Positive terminal cap on right
        paint.style = Paint.Style.FILL
        c.drawRoundRect(right, cy - 3.5f, right + 2.5f, cy + 3.5f, 1f, 1f, paint)

        // Inner charge bar
        val fillW = (right - left - 3f) * (soc.coerceIn(0, 100) / 100f)
        if (fillW > 1f) {
            c.drawRoundRect(left + 1.5f, top + 1.5f, left + 1.5f + fillW, bottom - 1.5f, 2f, 2f, paint)
        }
    }

    /**
     * Renders an actual horizontal physical battery gauge with casing, positive terminal (+),
     * inner fill from Red through Yellow to Green, and overlaid charge stats.
     */
    private fun drawBatteryGauge(
        c: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        soc: Int,
        remKwh: Double?,
        batColor: Int
    ) {
        val totalH = bottom - top
        val nibW = 5f
        val nibH = 14f

        val bodyLeft = left + 2f
        val bodyTop = top
        val bodyRight = right - nibW - 2f
        val bodyBottom = bottom

        // 1. Positive terminal cap (+) on the right edge
        val nibTop = top + (totalH - nibH) / 2f
        val nibBottom = nibTop + nibH
        val nibLeft = bodyRight
        val nibRight = bodyRight + nibW

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(24, 45, 58)
        c.drawRoundRect(nibLeft, nibTop, nibRight, nibBottom, 2.5f, 2.5f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(45, 75, 95)
        c.drawRoundRect(nibLeft, nibTop, nibRight, nibBottom, 2.5f, 2.5f, paint)

        // 2. Main battery outer shell
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 26, 36)
        c.drawRoundRect(bodyLeft, bodyTop, bodyRight, bodyBottom, 7f, 7f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(45, 75, 95)
        c.drawRoundRect(bodyLeft, bodyTop, bodyRight, bodyBottom, 7f, 7f, paint)

        // 3. Inner charge fill level (proportional to SoC %)
        val pad = 3f
        val innerLeft = bodyLeft + pad
        val innerTop = bodyTop + pad
        val innerRight = bodyRight - pad
        val innerBottom = bodyBottom - pad
        val innerMaxW = innerRight - innerLeft

        val fillW = innerMaxW * (soc.coerceIn(0, 100) / 100f)
        if (fillW > 1f) {
            val r = Color.red(batColor)
            val g = Color.green(batColor)
            val b = Color.blue(batColor)

            paint.style = Paint.Style.FILL
            if (cachedGradient == null || cachedGradTop != innerTop || cachedGradBottom != innerBottom || cachedGradColor != batColor) {
                val topGrad = Color.argb(235, r, g, b)
                val botGrad = Color.argb(175, (r * 0.75f).toInt(), (g * 0.75f).toInt(), (b * 0.75f).toInt())
                cachedGradient = LinearGradient(0f, innerTop, 0f, innerBottom, topGrad, botGrad, Shader.TileMode.CLAMP)
                cachedGradTop = innerTop
                cachedGradBottom = innerBottom
                cachedGradColor = batColor
            }
            paint.shader = cachedGradient

            c.drawRoundRect(innerLeft, innerTop, innerLeft + fillW, innerBottom, 4.5f, 4.5f, paint)
            paint.shader = null
        }

        // 4. Centered text: e.g. "88% · 31.1 kWh"
        val textStr = if (remKwh != null) {
            String.format(Locale.US, "%d%% · %.1f kWh", soc, remKwh)
        } else {
            "$soc%"
        }

        paint.style = Paint.Style.FILL
        paint.textSize = 12f
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.setShadowLayer(5f, 0f, 1f, Color.argb(230, 0, 0, 0))
        paint.color = Color.WHITE
        c.drawText(textStr, (bodyLeft + bodyRight) / 2f, top + totalH / 2f + 4.5f, paint)
        paint.clearShadowLayer()
    }

    private fun node(
        c: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        value: Double?,
        title: String,
        color: Int,
        icon: String,
        subBadgeText: String? = null,
        subBadgeColor: Int = white,
        subBadgeBgColor: Int = Color.rgb(22, 45, 60),
        subBadgeStrokeColor: Int = Color.rgb(42, 69, 83),
        isBattery: Boolean = false,
        batterySoc: Int = -1,
        batteryKwh: Double? = null
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(16, 35, 47)
        c.drawRoundRect(x, y, x + w, y + h, 18f, 18f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(37, 66, 79)
        c.drawRoundRect(x, y, x + w, y + h, 18f, 18f, paint)

        val valStr = if (value == null) "—" else String.format(Locale.US, "%.1f kW", abs(value))

        if (isBattery && batterySoc >= 0) {
            drawBatteryIcon(c, x + w / 2f, y + 24f, batterySoc, color)
            text(c, valStr, x + w / 2f, y + 60f, 32f, white, true)
            text(c, title, x + w / 2f, y + 82f, 12.5f, muted)

            val pillLeft = x + 8f
            val pillRight = x + w - 8f
            val pillTop = y + 95f
            val pillBottom = y + 131f
            drawBatteryGauge(c, pillLeft, pillTop, pillRight, pillBottom, batterySoc, batteryKwh, color)
        } else if (subBadgeText != null) {
            text(c, icon, x + w / 2f, y + 26f, 25f, color)
            text(c, valStr, x + w / 2f, y + 60f, 32f, white, true)
            text(c, title, x + w / 2f, y + 82f, 12.5f, muted)

            val pillLeft = x + 8f
            val pillRight = x + w - 8f
            val pillTop = y + 95f
            val pillBottom = y + 131f

            paint.style = Paint.Style.FILL
            paint.color = subBadgeBgColor
            c.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, 8f, 8f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = subBadgeStrokeColor
            c.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, 8f, 8f, paint)

            text(c, subBadgeText, x + w / 2f, pillTop + 23f, 12.5f, subBadgeColor, bold = true)
        } else {
            text(c, icon, x + w / 2f, y + 36f, 28f, color)
            text(c, valStr, x + w / 2f, y + 78f, 34f, white, true)
            text(c, title, x + w / 2f, y + 110f, 13.5f, muted)
        }
    }

    private fun link(
        c: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        value: Double?,
        color: Int,
        minAnimateKw: Double = 0.05
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(42, 69, 83)
        c.drawLine(x1, y1, x2, y2, paint)

        // Only animate arrows if value exceeds minAnimateKw threshold
        if (value == null || abs(value) < minAnimateKw || disconnected) return

        val direction = if (value < 0) -1 else 1
        val dx = x2 - x1
        val dy = y2 - y1
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length <= 0) return

        val ax = dx / length * direction
        val ay = dy / length * direction
        val wingX = ax * 8f
        val wingY = ay * 8f
        val perpX = -ay * 6f
        val perpY = ax * 6f

        val numArrows = when {
            length > 100f -> 3
            length > 30f -> 2
            else -> 1
        }
        val now = SystemClock.uptimeMillis()
        val cycleMs = 1600f
        val phase = (now % cycleMs.toLong()) / cycleMs // 0.0f .. 1.0f

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        paint.strokeWidth = 3f

        for (i in 0 until numArrows) {
            val baseFrac = i.toFloat() / numArrows
            var frac = (baseFrac + phase * direction) % 1.0f
            if (frac < 0f) frac += 1.0f

            val fade = when {
                frac < 0.12f -> (frac / 0.12f).coerceIn(0f, 1f)
                frac > 0.88f -> ((1.0f - frac) / 0.12f).coerceIn(0f, 1f)
                else -> 1.0f
            }
            val alpha = (255 * fade).toInt()
            if (alpha <= 5) continue

            paint.color = Color.argb(alpha, r, g, b)

            val x = x1 + dx * frac
            val y = y1 + dy * frac

            c.drawLine(x - wingX + perpX, y - wingY + perpY, x, y, paint)
            c.drawLine(x - wingX - perpX, y - wingY - perpY, x, y, paint)
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        val vWidth = 720f
        val vHeight = 360f
        val scale = min(width / vWidth, height / vHeight)
        c.save()
        c.translate((width - vWidth * scale) / 2f, (height - vHeight * scale) / 2f)
        c.scale(scale, scale)

        val stale = meters?.optLong("last_update", 0)?.let { System.currentTimeMillis() / 1000 - it > 30 } ?: true
        if (disconnected || stale) c.saveLayerAlpha(0f, 0f, vWidth, vHeight, 125)

        val centerX = 360f
        val centerY = 180f
        val nodeW = 215f
        val nodeH = 140f

        val solarTop = 4f
        val solarBottom = solarTop + nodeH // 144f
        val batteryBottom = vHeight - 4f   // 356f
        val batteryTop = batteryBottom - nodeH // 216f

        val middleNodeTop = centerY - nodeH / 2f // 110f
        val gridLeft = 4f
        val gridRight = gridLeft + nodeW // 219f
        val homeLeft = vWidth - 4f - nodeW // 501f
        val centerNodeLeft = centerX - nodeW / 2f // 252.5f

        val grid = power("grid")
        val battery = power("storage")
        val load = power("load")

        val soc = meters?.optInt("soc", meters?.optInt("enc_agg_soc", -1) ?: -1) ?: -1
        val capWh = meters?.optDouble("enc_agg_energy", 40000.0) ?: 40000.0
        val capKwh = capWh / 1000.0
        val remKwh = if (soc >= 0) capKwh * (soc / 100.0) else null
        val batColor = getBatteryColor(soc)

        // Connecting lines:
        // Solar and Battery squished closer vertically (length = 36f, 2 arrows)
        // Grid and Home spaced further apart horizontally (length = 141f, 3 arrows)
        link(c, centerX, solarBottom, centerX, centerY, power("pv"), cyan, minAnimateKw = 0.05)
        link(c, gridRight, centerY, centerX, centerY, grid, purple, minAnimateKw = 1.0)
        link(c, centerX, centerY, homeLeft, centerY, load, orange, minAnimateKw = 0.05)
        link(c, centerX, batteryTop, centerX, centerY, battery, batColor, minAnimateKw = 0.05)

        // 1. Solar Node (Top)
        node(
            c = c,
            x = centerNodeLeft,
            y = solarTop,
            w = nodeW,
            h = nodeH,
            value = power("pv"),
            title = "Solar producing",
            color = cyan,
            icon = "☀️"
        )

        // 2. Grid Node (Left - spaced out to left edge)
        val gridTitle = if (grid == null) "Grid" else if (abs(grid) < .05) "Grid idle" else if (grid < 0) "Exporting" else "Importing"
        node(
            c = c,
            x = gridLeft,
            y = middleNodeTop,
            w = nodeW,
            h = nodeH,
            value = grid,
            title = gridTitle,
            color = purple,
            icon = "↔"
        )

        // 3. Home Node (Right - spaced out to right edge)
        val evPwr = evPowerKw
        val evCharging = evPwr != null && evPwr > 0.05
        val evBadgeText = when {
            evPwr == null -> "🚗 EV: Standby"
            evCharging -> String.format(Locale.US, "🚗 EV %.1f kW · Charging", evPwr)
            !evMode.isNullOrEmpty() -> String.format(Locale.US, "🚗 EV 0.0 kW · %s", evMode)
            else -> "🚗 EV 0.0 kW · Unplugged"
        }
        val evBadgeColor = if (evCharging) cyan else Color.rgb(255, 196, 118)
        val evBadgeBg = if (evCharging) Color.rgb(18, 48, 55) else Color.rgb(24, 42, 54)
        val evBadgeStroke = if (evCharging) Color.rgb(35, 80, 95) else Color.rgb(45, 68, 80)

        node(
            c = c,
            x = homeLeft,
            y = middleNodeTop,
            w = nodeW,
            h = nodeH,
            value = load,
            title = "Home using",
            color = orange,
            icon = "⌂",
            subBadgeText = evBadgeText,
            subBadgeColor = evBadgeColor,
            subBadgeBgColor = evBadgeBg,
            subBadgeStrokeColor = evBadgeStroke
        )

        // 4. Battery Node (Bottom - squished closer to center)
        val batState = if (battery == null) "Battery" else if (abs(battery) < .05) "Idle" else if (battery < 0) "Charging" else "Discharging"
        val batTitle = if (soc >= 0) "$batState · $soc%" else batState

        node(
            c = c,
            x = centerNodeLeft,
            y = batteryTop,
            w = nodeW,
            h = nodeH,
            value = battery,
            title = batTitle,
            color = batColor,
            icon = "🔋",
            isBattery = true,
            batterySoc = soc,
            batteryKwh = remKwh
        )

        if (disconnected || stale) c.restore()
        c.restore()

        val hasActiveFlow = !disconnected && !stale && (
            (power("pv") ?: 0.0) >= 0.05 ||
            abs(power("grid") ?: 0.0) >= 1.0 ||
            (power("load") ?: 0.0) >= 0.05 ||
            abs(power("storage") ?: 0.0) >= 0.05
        )
        if (hasActiveFlow) {
            postInvalidateOnAnimation()
        }
    }
}
