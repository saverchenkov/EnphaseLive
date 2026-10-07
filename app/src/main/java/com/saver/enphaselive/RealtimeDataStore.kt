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
}
