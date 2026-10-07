package com.saver.enphaselive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CloudClientCacheTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun testIsPriorPeriodDay() {
        val today = LocalDate.of(2026, 10, 7)

        assertTrue(CloudClient.isPriorPeriod("day", "2026-10-06", today))
        assertTrue(CloudClient.isPriorPeriod("day", "2026-10-01", today))
        assertFalse(CloudClient.isPriorPeriod("day", "2026-10-07", today))
        assertFalse(CloudClient.isPriorPeriod("day", "2026-10-08", today))
    }

    @Test
    fun testIsPriorPeriodMonth() {
        val today = LocalDate.of(2026, 10, 7)

        assertTrue(CloudClient.isPriorPeriod("month", "2026-09", today))
        assertFalse(CloudClient.isPriorPeriod("month", "2026-10", today))
        assertFalse(CloudClient.isPriorPeriod("month", "2026-11", today))
    }

    @Test
    fun testIsPeriodCompleteDay() {
        val date = "2026-10-06"
        val startOfNextDayMs = LocalDate.of(2026, 10, 7).atStartOfDay(zone).toEpochSecond() * 1000L

        // Cache created on Oct 6 at 14:30 UTC -> incomplete
        val midDayMs = LocalDate.of(2026, 10, 6).atTime(14, 30).atZone(zone).toEpochSecond() * 1000L
        assertFalse("Mid-day cache on Oct 6 should not be complete", CloudClient.isPeriodComplete("day", date, midDayMs, zone))

        // Cache created on Oct 6 at 23:59:59 UTC -> incomplete
        val endDayMinusOneMs = startOfNextDayMs - 1000L
        assertFalse("Cache before midnight should not be complete", CloudClient.isPeriodComplete("day", date, endDayMinusOneMs, zone))

        // Cache created on Oct 7 at 00:00:00 UTC -> complete
        assertTrue("Cache at start of next day should be complete", CloudClient.isPeriodComplete("day", date, startOfNextDayMs, zone))

        // Cache created on Oct 7 at 06:30:00 UTC -> complete
        val morningNextDayMs = LocalDate.of(2026, 10, 7).atTime(6, 30).atZone(zone).toEpochSecond() * 1000L
        assertTrue("Cache on morning of next day should be complete", CloudClient.isPeriodComplete("day", date, morningNextDayMs, zone))
    }

    @Test
    fun testIsPeriodCompleteMonth() {
        val monthDate = "2026-09"
        val startOfNextMonthMs = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toEpochSecond() * 1000L

        // Cache created on Sep 15 -> incomplete
        val midMonthMs = LocalDate.of(2026, 9, 15).atStartOfDay(zone).toEpochSecond() * 1000L
        assertFalse("Mid-month cache should not be complete", CloudClient.isPeriodComplete("month", monthDate, midMonthMs, zone))

        // Cache created on Sep 30 23:00 -> incomplete
        val lateMonthMs = LocalDate.of(2026, 9, 30).atTime(23, 0).atZone(zone).toEpochSecond() * 1000L
        assertFalse("Cache on last day of month before month ends should not be complete", CloudClient.isPeriodComplete("month", monthDate, lateMonthMs, zone))

        // Cache created on Oct 1 -> complete
        assertTrue("Cache in next month should be complete", CloudClient.isPeriodComplete("month", monthDate, startOfNextMonthMs, zone))
    }
}
