package com.saver.enphaselive

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max

data class RealtimePowerSample(
    val timestampSec: Long,
    val productionW: Float,
    val consumptionW: Float,
    val dischargeW: Float,
    val exportW: Float,
    val importW: Float = 0f
)

/**
 * Stores up to 24 hours of 1-second real-time power telemetry.
 * Thread-safe with in-memory caching and periodic atomic disk persistence.
 */
class RealtimeDataStore(private val context: Context? = null, storageDir: File? = null) {
    private val lock = ReentrantReadWriteLock()
    private val samples = ArrayList<RealtimePowerSample>(86400)
    private val baseDir = storageDir ?: context?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
    private val file = File(baseDir, "realtime_history.bin")
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var lastSaveTime = 0L
    private var dirty = false

    companion object {
        const val MAX_RETENTION_SEC = 24 * 3600L // 24 hours
        private const val MAGIC: Byte = 0x45 // 'E'
        private const val VERSION: Byte = 2
    }

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!file.exists() || file.length() < 2) return
        val nowSec = System.currentTimeMillis() / 1000L
        val minTimeSec = nowSec - MAX_RETENTION_SEC

        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inStream ->
                val magic = inStream.readByte()
                val version = inStream.readByte()
                if (magic != MAGIC || (version.toInt() != 1 && version.toInt() != 2)) return@use

                val count = inStream.readInt()
                val loaded = ArrayList<RealtimePowerSample>(count)
                for (i in 0 until count) {
                    val ts = inStream.readLong()
                    val prod = inStream.readFloat()
                    val cons = inStream.readFloat()
                    val disch = inStream.readFloat()
                    val exp = inStream.readFloat()
                    val imp = if (version.toInt() >= 2) inStream.readFloat() else 0f
                    if (ts >= minTimeSec) {
                        loaded.add(RealtimePowerSample(ts, prod, cons, disch, exp, imp))
                    }
                }
                lock.write {
                    samples.clear()
                    samples.addAll(loaded)
                    samples.sortBy { it.timestampSec }
                }
            }
        }
    }

    fun addSample(
        productionW: Float,
        consumptionW: Float,
        dischargeW: Float,
        exportW: Float,
        importW: Float = 0f,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ) {
        val sample = RealtimePowerSample(
            timestampSec = timestampSec,
            productionW = max(0f, productionW),
            consumptionW = max(0f, consumptionW),
            dischargeW = max(0f, dischargeW),
            exportW = max(0f, exportW),
            importW = max(0f, importW)
        )

        val minTimeSec = timestampSec - MAX_RETENTION_SEC
        lock.write {
            // Avoid duplicate consecutive timestamps
            if (samples.isNotEmpty() && samples.last().timestampSec == timestampSec) {
                samples[samples.size - 1] = sample
            } else {
                samples.add(sample)
            }

            // Prune points older than 24h efficiently
            var pruneCount = 0
            while (pruneCount < samples.size && samples[pruneCount].timestampSec < minTimeSec) {
                pruneCount++
            }
            if (pruneCount > 0) {
                samples.subList(0, pruneCount).clear()
            }
            dirty = true
        }

        // Flush to disk asynchronously every 15 seconds
        val nowMs = System.currentTimeMillis()
        if (dirty && nowMs - lastSaveTime > 15000L) {
            flushToDisk()
        }
    }

    /**
     * Asynchronously writes current samples to disk in a background thread.
     */
    fun flushToDisk() {
        if (!dirty) return
        val snapshot = lock.read { ArrayList(samples) }
        lastSaveTime = System.currentTimeMillis()
        dirty = false
        ioExecutor.execute {
            writeSnapshotToDisk(snapshot)
        }
    }

    /**
     * Synchronously writes current samples to disk (for onPause / onDestroy).
     */
    fun flushToDiskSync() {
        val snapshot = lock.read { ArrayList(samples) }
        lastSaveTime = System.currentTimeMillis()
        dirty = false
        writeSnapshotToDisk(snapshot)
    }

    private fun writeSnapshotToDisk(snapshot: List<RealtimePowerSample>) {
        val tmpFile = File(baseDir, "realtime_history.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { out ->
                out.writeByte(MAGIC.toInt())
                out.writeByte(VERSION.toInt())
                out.writeInt(snapshot.size)
                for (s in snapshot) {
                    out.writeLong(s.timestampSec)
                    out.writeFloat(s.productionW)
                    out.writeFloat(s.consumptionW)
                    out.writeFloat(s.dischargeW)
                    out.writeFloat(s.exportW)
                    out.writeFloat(s.importW)
                }
            }
            if (tmpFile.exists()) {
                if (file.exists()) file.delete()
                tmpFile.renameTo(file)
            }
        }
    }

    /**
     * Returns downsampled samples for the specified time window (durationSec).
     * If durationSec = 3600 (1hr), returns high density samples.
     * Downsamples to targetPoints (approx 400-800) to keep rendering at 60 FPS.
     */
    fun getSamplesForWindow(durationSec: Long, targetPoints: Int = 500): List<RealtimePowerSample> {
        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = nowSec - durationSec

        val window = lock.read {
            samples.filter { it.timestampSec >= startSec }
        }

        if (window.size <= targetPoints) {
            return window
        }

        // Downsample by bucketing
        val step = window.size.toDouble() / targetPoints
        val result = ArrayList<RealtimePowerSample>(targetPoints)

        for (i in 0 until targetPoints) {
            val fromIndex = (i * step).toInt()
            val toIndex = minOf(window.size, ((i + 1) * step).toInt())
            if (fromIndex >= toIndex) continue

            var sumTs = 0L
            var sumProd = 0f
            var sumCons = 0f
            var sumDisch = 0f
            var sumExp = 0f
            var sumImp = 0f
            val count = toIndex - fromIndex

            for (j in fromIndex until toIndex) {
                val s = window[j]
                sumTs += s.timestampSec
                sumProd += s.productionW
                sumCons += s.consumptionW
                sumDisch += s.dischargeW
                sumExp += s.exportW
                sumImp += s.importW
            }

            result.add(
                RealtimePowerSample(
                    timestampSec = sumTs / count,
                    productionW = sumProd / count,
                    consumptionW = sumCons / count,
                    dischargeW = sumDisch / count,
                    exportW = sumExp / count,
                    importW = sumImp / count
                )
            )
        }

        return result
    }

    fun getLatestSample(): RealtimePowerSample? = lock.read { samples.lastOrNull() }

    /**
     * Estimates active Level 2 EV charger power draw (in Watts) from local 1-second consumption history.
     *
     * Because the IQ EV Charger 2 is a Wi-Fi device whose power draw passes through the local IQ Gateway's
     * site consumption CTs (`meters.load`), an EV charging session produces a sustained multi-kW step-up
     * above the pre-charging household baseline load (e.g., 1.9 kW household -> 13.3 kW total = 11.4 kW EV).
     */
    fun estimateEvPowerW(currentConsW: Float, nowSec: Long = System.currentTimeMillis() / 1000L): Float {
        if (currentConsW < 4500f) return 0f

        return lock.read {
            if (samples.isEmpty()) {
                return@read if (currentConsW >= 6000f) (currentConsW - 1800f).coerceAtMost(11440f) else 0f
            }

            // 1. Verify high consumption is sustained (debounce brief 1-5s appliance startup transients)
            var recentHighCount = 0
            var recentTotalCount = 0
            for (i in samples.size - 1 downTo 0) {
                val s = samples[i]
                if (nowSec - s.timestampSec > 6L) break
                recentTotalCount++
                if (s.consumptionW >= 4500f) recentHighCount++
            }
            if (recentTotalCount >= 3 && recentHighCount < recentTotalCount) {
                return@read 0f
            }

            // 2. Walk backward to find where the current high-load plateau (>= 4200 W) started
            var plateauStartIdx = samples.size - 1
            while (plateauStartIdx > 0) {
                val prev = samples[plateauStartIdx - 1]
                if (nowSec - prev.timestampSec > 6 * 3600L) break
                if (prev.consumptionW < 4200f) break
                plateauStartIdx--
            }
            val plateauStartSec = samples[plateauStartIdx].timestampSec

            // 3. Determine pre-step household baseline load (prefer 3-minute window right before the step-up)
            val preStepValues = ArrayList<Float>(180)
            if (plateauStartIdx > 0) {
                for (i in plateauStartIdx - 1 downTo 0) {
                    val s = samples[i]
                    val dt = plateauStartSec - s.timestampSec
                    if (dt > 185L) break
                    if (dt >= 3L && s.consumptionW in 100f..4000f) {
                        preStepValues.add(s.consumptionW)
                    }
                }
            }

            val baselineW: Float = if (preStepValues.size >= 5) {
                preStepValues.sort()
                preStepValues[preStepValues.size / 2]
            } else {
                // Fallback: median of non-EV quiet household samples (< 4000 W) over the last 6 hours
                val quietValues = ArrayList<Float>(256)
                val step = max(1, samples.size / 500)
                var i = samples.size - 1
                while (i >= 0) {
                    val s = samples[i]
                    if (nowSec - s.timestampSec > 6 * 3600L) break
                    if (s.consumptionW in 100f..4000f) {
                        quietValues.add(s.consumptionW)
                    }
                    i -= step
                }
                if (quietValues.isNotEmpty()) {
                    quietValues.sort()
                    quietValues[quietValues.size / 2]
                } else {
                    1800f
                }
            }

            val rawDeltaW = currentConsW - baselineW
            if (rawDeltaW < 3500f) return@read 0f

            // 4. Reject temporary household spikes on top of an active EV charge by checking the 25th percentile
            // of the plateau over the last 3 minutes (once the plateau has been stable for >= 20 seconds).
            val plateauDurationSec = nowSec - plateauStartSec
            val effectiveDeltaW = if (plateauDurationSec >= 20L) {
                val recentPlateau = ArrayList<Float>(180)
                for (i in samples.size - 1 downTo plateauStartIdx) {
                    val s = samples[i]
                    if (nowSec - s.timestampSec > 180L) break
                    if (s.timestampSec >= plateauStartSec + 5L && s.consumptionW >= 4500f) {
                        recentPlateau.add(s.consumptionW)
                    }
                }
                if (recentPlateau.size >= 10) {
                    recentPlateau.sort()
                    val floorConsW = recentPlateau[recentPlateau.size / 4]
                    val floorDeltaW = floorConsW - baselineW
                    if (floorDeltaW >= 3500f) minOf(rawDeltaW, floorDeltaW) else rawDeltaW
                } else {
                    rawDeltaW
                }
            } else {
                rawDeltaW
            }

            if (effectiveDeltaW >= 3500f) effectiveDeltaW.coerceAtMost(11440f) else 0f
        }
    }

    /**
     * Returns the timestamp (in epoch seconds) of the most recent sustained Level 2 EV charging
     * plateau (>= 6000 W sustained for >= 15 seconds) in the persisted 24-hour history, or 0L if none.
     */
    fun lastEvChargingTimestampSec(nowSec: Long = System.currentTimeMillis() / 1000L): Long {
        return lock.read {
            if (samples.isEmpty()) return@read 0L
            var runEndSec = 0L
            var runStartSec = 0L
            var runCount = 0
            for (i in samples.size - 1 downTo 0) {
                val s = samples[i]
                if (nowSec - s.timestampSec > MAX_RETENTION_SEC) break
                if (s.consumptionW >= 6000f) {
                    if (runCount == 0) {
                        runEndSec = s.timestampSec
                    }
                    runStartSec = s.timestampSec
                    runCount++
                    if (runCount >= 10 && runEndSec - runStartSec >= 15L) {
                        return@read runEndSec
                    }
                } else {
                    runCount = 0
                }
            }
            0L
        }
    }
}
