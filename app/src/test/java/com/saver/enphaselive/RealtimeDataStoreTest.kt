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

    @Test
    fun testEstimateEvPowerW_DetectsStepJumpAndSplitsHouseholdLoad() {
        val dir = tempFolder.newFolder("nobackup_ev")
        val store = RealtimeDataStore(storageDir = dir)
        val baseTs = 1792170000L

        // 1. 10 minutes of normal ~1.9 kW household baseline load
        for (i in 0 until 600) {
            store.addSample(
                productionW = 6300f,
                consumptionW = 1900f,
                dischargeW = 0f,
                exportW = 4400f,
                importW = 0f,
                timestampSec = baseTs + i
            )
        }
        assertEquals(0f, store.estimateEvPowerW(1900f, baseTs + 599), 0.1f)

        // 2. Brief 2-second spike to 6.0 kW (e.g. motor inrush) should be debounced
        store.addSample(6300f, 6000f, 0f, 300f, 0f, timestampSec = baseTs + 600)
        store.addSample(6300f, 6000f, 0f, 300f, 0f, timestampSec = baseTs + 601)
        assertEquals(0f, store.estimateEvPowerW(6000f, baseTs + 601), 0.1f)

        // 3. Sustained EV charging step jump to 13.3 kW (1.9 kW house + 11.4 kW EV)
        for (i in 602..630) {
            store.addSample(
                productionW = 6300f,
                consumptionW = 13300f,
                dischargeW = 7000f,
                exportW = 0f,
                importW = 0f,
                timestampSec = baseTs + i
            )
        }
        val estimatedEvW = store.estimateEvPowerW(13300f, baseTs + 630)
        assertEquals(11400f, estimatedEvW, 50f)

        // 4. Temporary 1.5 kW microwave turning on during EV charge (total 14.8 kW) keeps EV at 11.4 kW
        for (i in 631..640) {
            store.addSample(6300f, 14800f, 8500f, 0f, 0f, timestampSec = baseTs + i)
        }
        val estimatedDuringApplianceW = store.estimateEvPowerW(14800f, baseTs + 640)
        assertEquals(11400f, estimatedDuringApplianceW, 50f)

        // 5. EV stops charging (load drops back to 1.9 kW) -> immediately returns 0 W,
        //    while lastEvChargingTimestampSec remembers the recent charging session
        store.addSample(6300f, 1900f, 0f, 4400f, 0f, timestampSec = baseTs + 641)
        assertEquals(0f, store.estimateEvPowerW(1900f, baseTs + 641), 0.1f)
        assertEquals(baseTs + 640L, store.lastEvChargingTimestampSec(baseTs + 641))
    }
}
