package com.saver.enphaselive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RealtimeDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddSampleAndDownsampling() {
        val dir = tempFolder.newFolder("nobackup")
        val store = RealtimeDataStore(storageDir = dir)

        val nowSec = System.currentTimeMillis() / 1000L
        for (i in 0 until 100) {
            store.addSample(
                productionW = 2000f + i * 10f,
                consumptionW = 1000f,
                dischargeW = 0f,
                exportW = 1000f,
                importW = 0f,
                timestampSec = nowSec - 100 + i
            )
        }

        val latest = store.getLatestSample()
        assertNotNull(latest)
        assertEquals(nowSec - 1, latest!!.timestampSec)
        assertEquals(2990f, latest.productionW, 0.1f)

        // Request 50 target points for 200 seconds window
        val downsampled = store.getSamplesForWindow(durationSec = 200L, targetPoints = 50)
        assertEquals(50, downsampled.size)
    }

    @Test
    fun testPruningOlderThan24Hours() {
        val dir = tempFolder.newFolder("nobackup_prune")
        val store = RealtimeDataStore(storageDir = dir)

        val nowSec = System.currentTimeMillis() / 1000L
        // Add sample 25 hours in the past
        store.addSample(100f, 100f, 0f, 0f, 0f, timestampSec = nowSec - 25 * 3600L)
        // Add current sample
        store.addSample(200f, 200f, 0f, 0f, 0f, timestampSec = nowSec)

        val samples = store.getSamplesForWindow(durationSec = 48 * 3600L, targetPoints = 100)
        // The 25-hour-old sample should have been pruned by addSample
        assertEquals(1, samples.size)
        assertEquals(nowSec, samples[0].timestampSec)
    }

    @Test
    fun testDiskFlushAndReload() {
        val dir = tempFolder.newFolder("nobackup_disk")
        val store = RealtimeDataStore(storageDir = dir)

        val ts = System.currentTimeMillis() / 1000L
        store.addSample(4500f, 1200f, 500f, 2800f, 0f, timestampSec = ts)
        store.flushToDiskSync()

        // Create new store instance from same directory
        val reloadedStore = RealtimeDataStore(storageDir = dir)
        val latest = reloadedStore.getLatestSample()
        assertNotNull(latest)
        assertEquals(ts, latest!!.timestampSec)
        assertEquals(4500f, latest.productionW, 0.1f)
        assertEquals(1200f, latest.consumptionW, 0.1f)
    }
}
