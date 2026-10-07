package com.saver.enphaselive

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryParserTest {

    @Before
    fun setup() {
        HistoryParser.zone = ZoneId.of("UTC")
    }

    @Test
    fun testDayParsingBinsIntoHours() {
        val date = "2026-10-06"
        val startOfTargetDayEpoch = LocalDate.parse(date).atStartOfDay(ZoneId.of("UTC")).toEpochSecond()

        // 1:00 UTC interval (ends at 1:00:00, which is hour 0:45-1:00, end_at - 1 is in hour 0)
        val interval1 = JSONObject().apply {
            put("end_at", startOfTargetDayEpoch + 3600) // 01:00 UTC -> end_at - 1 is 00:59:59 (hour 0)
            put("wh_del", 250.0)
        }
        // 2:00 UTC interval (ends at 2:00:00 -> hour 1)
        val interval2 = JSONObject().apply {
            put("end_at", startOfTargetDayEpoch + 7200) // 02:00 UTC -> end_at - 1 is 01:59:59 (hour 1)
            put("wh_del", 500.0)
        }

        val intervalsArray = JSONArray().apply {
            put(interval1)
            put(interval2)
        }
        val prodResponse = JSONObject().put("intervals", intervalsArray)

        val result = HistoryParser.day(date, mapOf("production" to prodResponse))
        assertEquals(24, result.length())

        val hour0 = result.getJSONObject(0)
        assertEquals("00", hour0.getString("label"))
        assertEquals(0.250, hour0.getDouble("production"), 0.001)

        val hour1 = result.getJSONObject(1)
        assertEquals("01", hour1.getString("label"))
        assertEquals(0.500, hour1.getDouble("production"), 0.001)

        val hour2 = result.getJSONObject(2)
        assertTrue(hour2.isNull("production"))
    }

    @Test
    fun testMonthParsingReturnsCorrectDayCount() {
        val monthDate = "2026-10"
        val prodResponse = JSONObject().apply {
            put("start_date", "2026-10-01")
            put("production", JSONArray().apply {
                put(10000.0) // Day 1: 10 kWh
                put(15000.0) // Day 2: 15 kWh
            })
        }

        val result = HistoryParser.month(monthDate, mapOf("production" to prodResponse))
        assertEquals(31, result.length()) // October has 31 days

        val day1 = result.getJSONObject(0)
        assertEquals("1", day1.getString("label"))
        assertEquals("2026-10-01", day1.getString("date"))
        assertEquals(10.0, day1.getDouble("production"), 0.001)

        val day2 = result.getJSONObject(1)
        assertEquals("2", day2.getString("label"))
        assertEquals(15.0, day2.getDouble("production"), 0.001)

        val day3 = result.getJSONObject(2)
        assertTrue(day3.isNull("production"))
    }

    @Test
    fun testEmptyResponsesProduceNullMetrics() {
        val result = HistoryParser.day("2026-10-06", emptyMap())
        assertEquals(24, result.length())
        for (i in 0 until 24) {
            val bar = result.getJSONObject(i)
            assertTrue(bar.isNull("production"))
            assertTrue(bar.isNull("consumption"))
            assertTrue(bar.isNull("import"))
            assertTrue(bar.isNull("export"))
        }
    }
}
