package com.saver.enphaselive

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object HistoryParser {
    val metrics = listOf("production", "consumption", "import", "export", "charge", "discharge")
    var zone: ZoneId = ZoneId.systemDefault()
    fun month(date: String, responses: Map<String, JSONObject>): JSONArray {
        val month = YearMonth.parse(date)
        val result = JSONArray()
        for (day in 1..month.lengthOfMonth()) {
            val target = month.atDay(day)
            val bar = JSONObject().put("label", day.toString()).put("date", target.toString())
            for (metric in metrics) {
                val response = responses[metric]
                val start = response?.optString("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val offset = start?.let { java.time.temporal.ChronoUnit.DAYS.between(it, target).toInt() } ?: -1
                val values = response?.optJSONArray(metric)
                val value = if (values != null && offset >= 0 && offset < values.length() && !values.isNull(offset)) values.optDouble(offset, Double.NaN) else Double.NaN
                bar.put(metric, if (value.isFinite() && value >= 0) value/1000 else JSONObject.NULL)
            }
            result.put(bar)
        }
        return result
    }
    fun day(date: String, responses: Map<String, JSONObject>): JSONArray {
        val target = LocalDate.parse(date)
        val bins = linkedMapOf<Int, JSONObject>()
        // One bar per local hour. Missing channels stay null, including unsupported battery endpoints.
        for (hour in 0..23) {
            val obj = JSONObject().put("label", "%02d".format(hour)).put("date", date)
            metrics.forEach { obj.put(it, JSONObject.NULL) }
            bins[hour] = obj
        }
        for ((metric, response) in responses) {
            val intervals = response.optJSONArray("intervals") ?: continue
            val items = ArrayList<JSONObject>()
            for (i in 0 until intervals.length()) {
                val element = intervals.opt(i)
                if (element is JSONArray) {
                    for (j in 0 until element.length()) {
                        element.optJSONObject(j)?.let { items.add(it) }
                    }
                } else if (element is JSONObject) {
                    items.add(element)
                }
            }
            for (interval in items) {
                val end = interval.optLong("end_at", 0)
                if (end <= 0) continue
                val time = Instant.ofEpochSecond(end - 1).atZone(zone)
                if (time.toLocalDate() != target) continue
                val value = when (metric) {
                    "production" -> interval.optDouble("wh_del", interval.optDouble("enwh", Double.NaN))
                    "consumption" -> interval.optDouble("enwh", interval.optDouble("wh_del", Double.NaN))
                    "import" -> interval.optDouble("wh_imported", interval.optDouble("enwh", Double.NaN))
                    "export" -> interval.optDouble("wh_exported", interval.optDouble("enwh", Double.NaN))
                    "charge", "discharge" -> interval.optJSONObject(metric)?.optDouble("enwh", Double.NaN) ?: interval.optDouble("enwh", Double.NaN)
                    else -> interval.optDouble("enwh", Double.NaN)
                }
                if (!value.isFinite() || value < 0) continue
                val bin = bins.getValue(time.hour)
                val current = if (bin.isNull(metric)) 0.0 else bin.optDouble(metric, 0.0)
                bin.put(metric, current + value / 1000.0)
            }
        }
        return JSONArray(bins.values.toList())
    }
}
