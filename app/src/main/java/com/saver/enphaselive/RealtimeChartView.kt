package com.saver.enphaselive

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Sense-inspired real-time area chart.
 * Features ultra-clean stepped shaded fills, hairline borders without busy thick lines,
 * subtle sparse gridlines, and a minimal Sense-style live cursor.
 */
class RealtimeChartView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmtShort = DateTimeFormatter.ofPattern("h:mm a")
    private val timeFmtSeconds = DateTimeFormatter.ofPattern("h:mm:ss a")
    private val timeFmtScrub = DateTimeFormatter.ofPattern("h:mm:ss a")

    private var durationSec: Long = 3600L // default 1hr
    private var samples: List<RealtimePowerSample> = emptyList()
    private var scrubX: Float? = null

    // Series visibility toggles (controlled by interactive metric pills)
    private var showSolar: Boolean = true
    private var showBattery: Boolean = true
    private var showHome: Boolean = true
    private var showGrid: Boolean = true

    // Colors - Sense-inspired palette
    private val colorProd = Color.rgb(0, 216, 246)       // Cyan (Solar)
    private val colorCons = Color.rgb(255, 128, 32)      // Sense Vibrant Orange (Usage)
    private val colorDisch = Color.rgb(82, 229, 140)     // Emerald Green (Battery Discharge)
    private val colorImport = Color.rgb(176, 150, 255)   // Soft Purple (Grid Import)
    private val colorExport = Color.rgb(120, 160, 255)   // Slate Blue (Grid Export)

    private val gridLineColor = Color.argb(28, 140, 180, 205) // Faint Sense gridlines
    private val axisTextColor = Color.rgb(130, 155, 170)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Real-time power meter graph"
    }

    fun setSeriesVisibility(solar: Boolean, battery: Boolean, home: Boolean, grid: Boolean) {
        if (showSolar != solar || showBattery != battery || showHome != home || showGrid != grid) {
            showSolar = solar
            showBattery = battery
            showHome = home
            showGrid = grid
            invalidate()
        }
    }

    fun setDuration(sec: Long) {
        if (durationSec != sec) {
            durationSec = sec
            scrubX = null
            invalidate()
        }
    }

    fun updateSamples(data: List<RealtimePowerSample>) {
        samples = data
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                scrubX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                postDelayed({
                    scrubX = null
                    invalidate()
                }, 4000L)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                postDelayed({
                    scrubX = null
                    invalidate()
                }, 4000L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = resources.displayMetrics.density
        val padLeft = 44f * d
        val padRight = 12f * d
        val padTop = 14f * d
        val padBottom = 24f * d

        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom
        if (chartW <= 0 || chartH <= 0) return

        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = nowSec - durationSec

        // 1. Calculate Peak Power Max Limit (only considering visible series)
        var maxPowerW = 500f
        for (s in samples) {
            var totalGenW = 0f
            if (showSolar) totalGenW += s.productionW
            if (showBattery) totalGenW += s.dischargeW
            var peakInSample = totalGenW
            if (showHome) peakInSample = max(peakInSample, s.consumptionW)
            if (showGrid) peakInSample = max(peakInSample, max(s.importW, s.exportW))
            maxPowerW = max(maxPowerW, peakInSample)
        }

        val yStepW = when {
            maxPowerW <= 2500f -> 1000f
            maxPowerW <= 6000f -> 2000f
            maxPowerW <= 12000f -> 3000f
            else -> 5000f
        }
        val yLimitW = (ceil(maxPowerW / yStepW) * yStepW).coerceAtLeast(yStepW * 2)

        // Sense styling: keep grid clean and sparse (2-3 lines max)
        val ticks = if (yLimitW / yStepW > 4) (yLimitW / (yStepW * 2)).toInt() else (yLimitW / yStepW).toInt()
        val zeroY = padTop + chartH

        // 2. Draw Sparse Faint Horizontal Grid Lines & Y-Axis Labels
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * d
        for (i in 0..ticks) {
            val frac = i.toFloat() / ticks
            val y = zeroY - frac * chartH
            val pValW = frac * yLimitW

            // Grid line
            paint.style = Paint.Style.STROKE
            paint.color = gridLineColor
            c.drawLine(padLeft, y, padLeft + chartW, y, paint)

            // Y-axis text
            paint.style = Paint.Style.FILL
            paint.color = axisTextColor
            paint.textSize = 9.5f * d
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.RIGHT
            val labelStr = if (pValW == 0f) {
                "0 W"
            } else if (pValW >= 1000f && (pValW % 1000f == 0f)) {
                "${(pValW / 1000f).toInt()} kW"
            } else if (pValW >= 1000f) {
                String.format(Locale.US, "%.1f kW", pValW / 1000f)
            } else {
                "${pValW.toInt()} W"
            }
            c.drawText(labelStr, padLeft - 6f * d, y + 3.5f * d, paint)
        }

        // 3. Draw X-Axis Baseline & Time Ticks (Sense style)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * d
        paint.color = Color.argb(40, 140, 180, 205)
        c.drawLine(padLeft, zeroY, padLeft + chartW, zeroY, paint)

        val xTicks = 4
        for (i in 0..xTicks) {
            val frac = i.toFloat() / xTicks
            val tickTime = startSec + (frac * durationSec).toLong()
            val x = when (i) {
                0 -> padLeft
                xTicks -> padLeft + chartW
                else -> padLeft + frac * chartW
            }

            // Small tick mark
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = Color.argb(60, 140, 180, 205)
            c.drawLine(x, zeroY, x, zeroY + 3.5f * d, paint)

            // Time label
            paint.style = Paint.Style.FILL
            paint.textSize = 9.5f * d
            paint.color = axisTextColor
            paint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                xTicks -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val timeStr = if (i == xTicks) "Now" else {
                val fmt = if (durationSec <= 300L) timeFmtSeconds else timeFmtShort
                Instant.ofEpochSecond(tickTime).atZone(zone).format(fmt)
            }
            c.drawText(timeStr, x, zeroY + 15f * d, paint)
        }

        // 4. Render Sense-Style Stepped Shaded Series Curves
        if (samples.isNotEmpty()) {
            fun getX(ts: Long): Float {
                val frac = ((ts - startSec).toFloat() / durationSec).coerceIn(0f, 1f)
                return padLeft + frac * chartW
            }

            fun getY(watts: Float): Float {
                val frac = (watts / yLimitW).coerceIn(0f, 1f)
                return zeroY - frac * chartH
            }

            // Iterate contiguous segments without allocating new sublists
            val gapLimitSec = max(15L, durationSec / 60)
            var segStart = 0
            for (i in samples.indices) {
                val isLast = (i == samples.size - 1)
                val isGap = (!isLast && samples[i + 1].timestampSec - samples[i].timestampSec > gapLimitSec)
                if (isLast || isGap) {
                    val segEnd = i

                    // 1) Solar Generation (Cyan, base: 0, top: productionW)
                    if (showSolar) {
                        drawSolar(c, d, padTop, zeroY, samples, segStart, segEnd, ::getX, ::getY)
                    }

                    // 2) Battery Discharge (Emerald Green, STACKED on top of Solar if Solar is visible, else from 0)
                    if (showBattery) {
                        drawBattery(c, d, padTop, zeroY, samples, segStart, segEnd, showSolar, ::getX, ::getY)
                    }

                    // 3) Grid Import & Export (Soft Purple for import, Slate Blue for export)
                    if (showGrid) {
                        drawGridImport(c, d, padTop, zeroY, samples, segStart, segEnd, ::getX, ::getY)
                        drawGridExport(c, d, padTop, zeroY, samples, segStart, segEnd, ::getX, ::getY)
                    }

                    // 4) Home Consumption (Sense Vibrant Orange, base: 0, top: consumptionW)
                    if (showHome) {
                        drawConsumption(c, d, padTop, zeroY, samples, segStart, segEnd, ::getX, ::getY)
                    }

                    segStart = i + 1
                }
            }

            // 5. Draw Right-Edge "Now" Live Indicator (Sense style thin vertical line)
            val nowX = padLeft + chartW
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = if (showHome) colorCons else if (showBattery) colorDisch else if (showSolar) colorProd else Color.argb(160, 200, 225, 240)
            c.drawLine(nowX, padTop, nowX, zeroY, paint)

        } else {
            // Empty state placeholder
            paint.style = Paint.Style.FILL
            paint.color = axisTextColor
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f * d
            c.drawText("Accumulating real-time data from IQ Gateway...", padLeft + chartW / 2f, padTop + chartH / 2f, paint)
        }

        // 6. Draw Interactive Scrubber Tooltip if active
        val currentScrubX = scrubX
        if (currentScrubX != null && samples.isNotEmpty()) {
            drawScrubber(c, d, padLeft, padRight, padTop, zeroY, chartW, currentScrubX, startSec, yLimitW)
        }
    }

    private fun drawSolar(
        c: Canvas,
        d: Float,
        padTop: Float,
        zeroY: Float,
        samples: List<RealtimePowerSample>,
        fromIndex: Int,
        toIndex: Int,
        getX: (Long) -> Float,
        getY: (Float) -> Float
    ) {
        if (fromIndex > toIndex) return
        var hasSolar = false
        for (i in fromIndex..toIndex) {
            if (samples[i].productionW > 5f) {
                hasSolar = true
                break
            }
        }
        if (!hasSolar) return

        val first = samples[fromIndex]
        val last = samples[toIndex]

        // 1. Shaded area using stepped path (Sense style)
        path.reset()
        path.moveTo(getX(first.timestampSec), zeroY)
        path.lineTo(getX(first.timestampSec), getY(first.productionW))

        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.productionW)
            val yCurr = getY(curr.productionW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        path.lineTo(getX(last.timestampSec), zeroY)
        path.close()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, padTop, 0f, zeroY,
            Color.argb(145, Color.red(colorProd), Color.green(colorProd), Color.blue(colorProd)),
            Color.argb(10, Color.red(colorProd), Color.green(colorProd), Color.blue(colorProd)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // 2. Clean hairline edge (no thick strokes)
        path.reset()
        path.moveTo(getX(first.timestampSec), getY(first.productionW))
        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.productionW)
            val yCurr = getY(curr.productionW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * d
        paint.color = Color.argb(190, Color.red(colorProd), Color.green(colorProd), Color.blue(colorProd))
        c.drawPath(path, paint)
    }

    private fun drawBattery(
        c: Canvas,
        d: Float,
        padTop: Float,
        zeroY: Float,
        samples: List<RealtimePowerSample>,
        fromIndex: Int,
        toIndex: Int,
        stackedOnSolar: Boolean,
        getX: (Long) -> Float,
        getY: (Float) -> Float
    ) {
        if (fromIndex > toIndex) return
        var hasDisch = false
        for (i in fromIndex..toIndex) {
            if (samples[i].dischargeW > 5f) {
                hasDisch = true
                break
            }
        }
        if (!hasDisch) return

        val first = samples[fromIndex]
        val last = samples[toIndex]

        if (stackedOnSolar) {
            // 1. Stacked area: top is yTotal (prod + disch), bottom is ySolar (prod)
            path.reset()
            path.moveTo(getX(first.timestampSec), getY(first.productionW + first.dischargeW))
            for (i in (fromIndex + 1)..toIndex) {
                val prev = samples[i - 1]
                val curr = samples[i]
                val xCurr = getX(curr.timestampSec)
                val yPrevTotal = getY(prev.productionW + prev.dischargeW)
                val yCurrTotal = getY(curr.productionW + curr.dischargeW)
                path.lineTo(xCurr, yPrevTotal)
                path.lineTo(xCurr, yCurrTotal)
            }
            path.lineTo(getX(last.timestampSec), getY(last.productionW))
            for (i in (toIndex - 1) downTo fromIndex) {
                val next = samples[i + 1]
                val curr = samples[i]
                val xCurr = getX(curr.timestampSec)
                val yNextSolar = getY(next.productionW)
                val yCurrSolar = getY(curr.productionW)
                path.lineTo(xCurr, yNextSolar)
                path.lineTo(xCurr, yCurrSolar)
            }
            path.close()

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, padTop, 0f, zeroY,
                Color.argb(155, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch)),
                Color.argb(25, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch)),
                Shader.TileMode.CLAMP
            )
            c.drawPath(path, paint)
            paint.shader = null

            // 2. Clean hairline edge on top (Total Produced/Stored)
            path.reset()
            path.moveTo(getX(first.timestampSec), getY(first.productionW + first.dischargeW))
            for (i in (fromIndex + 1)..toIndex) {
                val prev = samples[i - 1]
                val curr = samples[i]
                val xCurr = getX(curr.timestampSec)
                val yPrevTotal = getY(prev.productionW + prev.dischargeW)
                val yCurrTotal = getY(curr.productionW + curr.dischargeW)
                path.lineTo(xCurr, yPrevTotal)
                path.lineTo(xCurr, yCurrTotal)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.0f * d
            paint.color = Color.argb(210, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch))
            c.drawPath(path, paint)
        } else {
            // Unstacked area: base is zeroY, top is dischargeW
            path.reset()
            path.moveTo(getX(first.timestampSec), zeroY)
            path.lineTo(getX(first.timestampSec), getY(first.dischargeW))
            for (i in (fromIndex + 1)..toIndex) {
                val prev = samples[i - 1]
                val curr = samples[i]
                val xCurr = getX(curr.timestampSec)
                val yPrev = getY(prev.dischargeW)
                val yCurr = getY(curr.dischargeW)
                path.lineTo(xCurr, yPrev)
                path.lineTo(xCurr, yCurr)
            }
            path.lineTo(getX(last.timestampSec), zeroY)
            path.close()

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, padTop, 0f, zeroY,
                Color.argb(155, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch)),
                Color.argb(25, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch)),
                Shader.TileMode.CLAMP
            )
            c.drawPath(path, paint)
            paint.shader = null

            // Clean hairline edge on top
            path.reset()
            path.moveTo(getX(first.timestampSec), getY(first.dischargeW))
            for (i in (fromIndex + 1)..toIndex) {
                val prev = samples[i - 1]
                val curr = samples[i]
                val xCurr = getX(curr.timestampSec)
                val yPrev = getY(prev.dischargeW)
                val yCurr = getY(curr.dischargeW)
                path.lineTo(xCurr, yPrev)
                path.lineTo(xCurr, yCurr)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.0f * d
            paint.color = Color.argb(210, Color.red(colorDisch), Color.green(colorDisch), Color.blue(colorDisch))
            c.drawPath(path, paint)
        }
    }

    private fun drawGridExport(
        c: Canvas,
        d: Float,
        padTop: Float,
        zeroY: Float,
        samples: List<RealtimePowerSample>,
        fromIndex: Int,
        toIndex: Int,
        getX: (Long) -> Float,
        getY: (Float) -> Float
    ) {
        if (fromIndex > toIndex) return
        var hasExport = false
        for (i in fromIndex..toIndex) {
            if (samples[i].exportW > 5f) {
                hasExport = true
                break
            }
        }
        if (!hasExport) return

        val first = samples[fromIndex]
        val last = samples[toIndex]

        // 1. Shaded area using stepped path
        path.reset()
        path.moveTo(getX(first.timestampSec), zeroY)
        path.lineTo(getX(first.timestampSec), getY(first.exportW))

        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.exportW)
            val yCurr = getY(curr.exportW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        path.lineTo(getX(last.timestampSec), zeroY)
        path.close()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, padTop, 0f, zeroY,
            Color.argb(135, Color.red(colorExport), Color.green(colorExport), Color.blue(colorExport)),
            Color.argb(8, Color.red(colorExport), Color.green(colorExport), Color.blue(colorExport)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // 2. Clean hairline edge
        path.reset()
        path.moveTo(getX(first.timestampSec), getY(first.exportW))
        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.exportW)
            val yCurr = getY(curr.exportW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * d
        paint.color = Color.argb(190, Color.red(colorExport), Color.green(colorExport), Color.blue(colorExport))
        c.drawPath(path, paint)
    }

    private fun drawGridImport(
        c: Canvas,
        d: Float,
        padTop: Float,
        zeroY: Float,
        samples: List<RealtimePowerSample>,
        fromIndex: Int,
        toIndex: Int,
        getX: (Long) -> Float,
        getY: (Float) -> Float
    ) {
        if (fromIndex > toIndex) return
        var hasImport = false
        for (i in fromIndex..toIndex) {
            if (samples[i].importW > 5f) {
                hasImport = true
                break
            }
        }
        if (!hasImport) return

        val first = samples[fromIndex]
        val last = samples[toIndex]

        // 1. Shaded area using stepped path
        path.reset()
        path.moveTo(getX(first.timestampSec), zeroY)
        path.lineTo(getX(first.timestampSec), getY(first.importW))

        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.importW)
            val yCurr = getY(curr.importW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        path.lineTo(getX(last.timestampSec), zeroY)
        path.close()

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, padTop, 0f, zeroY,
            Color.argb(135, Color.red(colorImport), Color.green(colorImport), Color.blue(colorImport)),
            Color.argb(8, Color.red(colorImport), Color.green(colorImport), Color.blue(colorImport)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // 2. Clean hairline edge
        path.reset()
        path.moveTo(getX(first.timestampSec), getY(first.importW))
        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.importW)
            val yCurr = getY(curr.importW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * d
        paint.color = Color.argb(190, Color.red(colorImport), Color.green(colorImport), Color.blue(colorImport))
        c.drawPath(path, paint)
    }

    private fun drawConsumption(
        c: Canvas,
        d: Float,
        padTop: Float,
        zeroY: Float,
        samples: List<RealtimePowerSample>,
        fromIndex: Int,
        toIndex: Int,
        getX: (Long) -> Float,
        getY: (Float) -> Float
    ) {
        if (fromIndex > toIndex) return

        val first = samples[fromIndex]
        val last = samples[toIndex]

        // 1. Shaded area using stepped path (Sense style)
        path.reset()
        path.moveTo(getX(first.timestampSec), zeroY)
        path.lineTo(getX(first.timestampSec), getY(first.consumptionW))

        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.consumptionW)
            val yCurr = getY(curr.consumptionW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        path.lineTo(getX(last.timestampSec), zeroY)
        path.close()

        paint.style = Paint.Style.FILL
        // Vibrant Sense orange gradient fading smoothly to bottom
        paint.shader = LinearGradient(
            0f, padTop, 0f, zeroY,
            Color.argb(175, Color.red(colorCons), Color.green(colorCons), Color.blue(colorCons)),
            Color.argb(12, Color.red(colorCons), Color.green(colorCons), Color.blue(colorCons)),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // 2. Clean hairline edge on top (subtle 1.0dp definition, no busy thick strokes)
        path.reset()
        path.moveTo(getX(first.timestampSec), getY(first.consumptionW))
        for (i in (fromIndex + 1)..toIndex) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val xCurr = getX(curr.timestampSec)
            val yPrev = getY(prev.consumptionW)
            val yCurr = getY(curr.consumptionW)
            path.lineTo(xCurr, yPrev)
            path.lineTo(xCurr, yCurr)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.0f * d
        paint.color = Color.argb(225, Color.red(colorCons), Color.green(colorCons), Color.blue(colorCons))
        c.drawPath(path, paint)
    }

    private fun drawScrubber(
        c: Canvas,
        d: Float,
        padLeft: Float,
        padRight: Float,
        padTop: Float,
        zeroY: Float,
        chartW: Float,
        touchX: Float,
        startSec: Long,
        yLimitW: Float
    ) {
        val clampedX = touchX.coerceIn(padLeft, padLeft + chartW)
        val frac = (clampedX - padLeft) / chartW
        val targetTs = startSec + (frac * durationSec).toLong()

        // Find nearest sample
        val sample = samples.minByOrNull { abs(it.timestampSec - targetTs) } ?: return
        val sampleX = padLeft + (((sample.timestampSec - startSec).toFloat() / durationSec).coerceIn(0f, 1f)) * chartW

        // 1. Vertical Cursor Line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = Color.argb(180, 220, 240, 255)
        c.drawLine(sampleX, padTop, sampleX, zeroY, paint)

        // 2. Cursor intersection dot on highest visible curve
        fun getY(w: Float): Float = zeroY - (w / yLimitW).coerceIn(0f, 1f) * (zeroY - padTop)
        if (showHome) {
            paint.style = Paint.Style.FILL
            paint.color = colorCons
            c.drawCircle(sampleX, getY(sample.consumptionW), 3.5f * d, paint)
        } else if (showBattery && showSolar && (sample.productionW + sample.dischargeW > 5f)) {
            paint.style = Paint.Style.FILL
            paint.color = colorDisch
            c.drawCircle(sampleX, getY(sample.productionW + sample.dischargeW), 3.5f * d, paint)
        } else if (showBattery && sample.dischargeW > 5f) {
            paint.style = Paint.Style.FILL
            paint.color = colorDisch
            c.drawCircle(sampleX, getY(sample.dischargeW), 3.5f * d, paint)
        } else if (showSolar && sample.productionW > 5f) {
            paint.style = Paint.Style.FILL
            paint.color = colorProd
            c.drawCircle(sampleX, getY(sample.productionW), 3.5f * d, paint)
        } else if (showGrid && sample.importW > 5f) {
            paint.style = Paint.Style.FILL
            paint.color = colorImport
            c.drawCircle(sampleX, getY(sample.importW), 3.5f * d, paint)
        } else if (showGrid && sample.exportW > 5f) {
            paint.style = Paint.Style.FILL
            paint.color = colorExport
            c.drawCircle(sampleX, getY(sample.exportW), 3.5f * d, paint)
        }

        // 3. Floating Tooltip Pill (Sense style)
        val timeStr = Instant.ofEpochSecond(sample.timestampSec).atZone(zone).format(timeFmtScrub)
        val tipW = 340f * d
        val tipH = 46f * d
        val tipX = (sampleX - tipW / 2f).coerceIn(padLeft + 4f * d, padLeft + chartW - tipW - 4f * d)
        val tipY = padTop + 4f * d

        // Pill background
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 10, 22, 32)
        c.drawRoundRect(tipX, tipY, tipX + tipW, tipY + tipH, 8f * d, 8f * d, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * d
        paint.color = Color.rgb(45, 75, 95)
        c.drawRoundRect(tipX, tipY, tipX + tipW, tipY + tipH, 8f * d, 8f * d, paint)

        // Tooltip Text - Time
        paint.style = Paint.Style.FILL
        paint.textSize = 10f * d
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.rgb(235, 245, 250)
        paint.textAlign = Paint.Align.LEFT
        c.drawText(timeStr, tipX + 10f * d, tipY + 16f * d, paint)

        // Tooltip Metrics Row
        val yRow = tipY + 34f * d
        paint.textSize = 10f * d

        fun fmt(w: Float): String = if (w >= 1000f) String.format(Locale.US, "%.1f kW", w / 1000f) else "${w.toInt()} W"
        val dimText = Color.argb(90, 130, 155, 170)

        // Solar
        paint.color = if (showSolar) colorProd else dimText
        c.drawText("● ☀ ${fmt(sample.productionW)}", tipX + 10f * d, yRow, paint)

        // Battery
        paint.color = if (showBattery) colorDisch else dimText
        c.drawText("● 🔋 ${fmt(sample.dischargeW)}", tipX + 85f * d, yRow, paint)

        // Home
        paint.color = if (showHome) colorCons else dimText
        c.drawText("● ⌂ ${fmt(sample.consumptionW)}", tipX + 165f * d, yRow, paint)

        // Grid (Import / Export)
        val isExporting = sample.exportW > 10f
        paint.color = if (showGrid) (if (isExporting) colorExport else colorImport) else dimText
        val gridStr = if (sample.importW > 10f) {
            "● ⚡ ${fmt(sample.importW)}"
        } else if (isExporting) {
            "● ↔ ${fmt(sample.exportW)}"
        } else {
            "● ⚡ 0 W"
        }
        c.drawText(gridStr, tipX + 245f * d, yRow, paint)
    }
}
