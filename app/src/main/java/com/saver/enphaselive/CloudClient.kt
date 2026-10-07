package com.saver.enphaselive

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import android.util.Base64

/** Cloud history is independent of the one-second local live connection. */
class CloudClient(private val context: Context) {
    private val credentials = SecureJsonStore(context, "cloud")
    private val usage = context.getSharedPreferences("cloud_usage", Context.MODE_PRIVATE)
    private var lastRequest = 0L
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    init {
        val imported = File(context.filesDir, "cloud.json")
        if (imported.exists()) { credentials.save(JSONObject(imported.readText())); imported.delete() }
    }
    fun configured() = runCatching { credentials.read() != null }.getOrDefault(false)
    fun save(config: JSONObject) { credentials.save(config) }
    fun getCredentials(): JSONObject? = credentials.read()

    fun getSystemId(): String = credentials.read()?.let {
        it.optString("system_id", it.optString("site_id", "")).ifEmpty { "1708379" }
    } ?: "1708379"

    fun setSystemId(id: String) {
        val config = credentials.read() ?: JSONObject()
        config.put("system_id", id.trim())
        credentials.save(config)
    }

    private fun discoverSystemId(key: String, token: String): String {
        return runCatching {
            val conn = URL("https://api.enphaseenergy.com/api/v4/systems?key=${encode(key)}").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val systems = json.optJSONArray("systems")
                    if (systems != null && systems.length() > 0) {
                        systems.getJSONObject(0).optString("system_id", "")
                    } else ""
                } else ""
            } finally { conn.disconnect() }
        }.getOrDefault("")
    }

    private fun reserveCall() {
        val month = YearMonth.now(zone).toString()
        val count = if (usage.getString("month", "") == month) usage.getInt("count", 0) else 0
        require(count < 850) { "Monthly cloud budget reached; cached history is available" }
        val wait = 6500-(SystemClock.elapsedRealtime()-lastRequest)
        if (wait > 0) Thread.sleep(wait)
        lastRequest = SystemClock.elapsedRealtime()
        usage.edit().putString("month", month).putInt("count", count+1).apply()
    }
    private fun accessToken(config: JSONObject): String {
        val token = config.optString("access_token")
        if (token.isNotBlank() && config.optLong("expires_at", Long.MAX_VALUE) > System.currentTimeMillis()/1000+300) return token
        val refresh = config.optString("refresh_token")
        require(refresh.isNotBlank()) { "Authorize the cloud application in Settings" }
        reserveCall()
        val connection = URL("https://api.enphaseenergy.com/oauth/token").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
            val basic = Base64.encodeToString((config.getString("client_id")+":"+config.getString("client_secret")).toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $basic")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(("grant_type=refresh_token&refresh_token="+encode(refresh)).toByteArray()) }
            if (connection.responseCode != 200) error("Cloud authorization needs to be renewed")
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            config.put("access_token", result.getString("access_token"))
            config.put("refresh_token", result.optString("refresh_token", refresh))
            config.put("expires_at", System.currentTimeMillis()/1000+result.optLong("expires_in", 86400))
            credentials.save(config)
            return config.getString("access_token")
        } finally { connection.disconnect() }
    }
    private fun request(path: String, params: Map<String, String>, maxAge: Long, force: Boolean = false): JSONObject {
        val config = credentials.read() ?: error("Connect Enphase history in Settings")
        val key = config.optString("api_key")
        require(key.isNotBlank()) { "History requires an API v4 application key" }
        val query = params.entries.joinToString("&") { encode(it.key)+"="+encode(it.value) }
        val id = MessageDigest.getInstance("SHA-256").digest((path+query+key).toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "cloud_cache").apply { mkdirs() }
        val cache = File(directory, "$id.json")
        if (!force && cache.exists() && System.currentTimeMillis()-cache.lastModified() < maxAge) {
            val cached = JSONObject(cache.readText())
            if (cached.has("_error")) throw IllegalStateException(cached.getString("_error"))
            return cached
        }
        return try {
            val token = accessToken(config)
            reserveCall()
            val querySuffix = if (query.isEmpty()) "" else "&$query"

            var sysId = config.optString("system_id", config.optString("site_id", ""))
            if (sysId.isBlank()) {
                val discovered = discoverSystemId(key, token)
                if (discovered.isNotBlank()) {
                    sysId = discovered
                    config.put("system_id", sysId)
                    credentials.save(config)
                } else {
                    sysId = "1708379" // fallback to pre-existing system ID
                }
            }

            val connection = URL("https://api.enphaseenergy.com/api/v4/systems/$sysId/$path?key=${encode(key)}$querySuffix").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code == 401) error("Cloud authorization expired or rejected. Replace the access token in Settings.")
                if (code == 403) {
                    val err = "This API plan or authorization does not permit $path"
                    cache.writeText(JSONObject().put("_error", err).put("_fetched_at", System.currentTimeMillis()/1000).toString())
                    error(err)
                }
                if (code == 429) error("Enphase cloud rate limit reached; use cached history and retry later")
                if (code != 200) error("Cloud history returned HTTP $code")
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = JSONObject(raw)
                parsed.put("_fetched_at", System.currentTimeMillis()/1000)
                cache.writeText(parsed.toString())
                return parsed
            } finally { connection.disconnect() }
        } catch (error: Exception) {
            if (cache.exists()) {
                val cached = JSONObject(cache.readText())
                if (!cached.has("_error")) {
                    cached.put("_cache_warning", error.message ?: "Showing cached data")
                    return cached
                }
            }
            throw error
        }
    }
    private val memoryHistoryCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val parsedCacheDir = File(context.noBackupFilesDir, "parsed_history").apply { mkdirs() }

    fun isPriorPeriod(period: String, date: String): Boolean = Companion.isPriorPeriod(period, date, LocalDate.now(zone))

    /**
     * Determines whether a cached period was fetched after the period ended.
     * Prevents partial mid-day or mid-month snapshots from being permanently cached forever.
     */
    fun isPeriodComplete(period: String, date: String, timestampMs: Long): Boolean = Companion.isPeriodComplete(period, date, timestampMs, zone)

    fun getCachedHistory(period: String, date: String): JSONObject? {
        val key = "${period}_$date"
        val isPrior = isPriorPeriod(period, date)

        memoryHistoryCache[key]?.let { cached ->
            val fetchedAt = cached.optLong("_fetched_at", 0) * 1000L
            val complete = isPrior && isPeriodComplete(period, date, fetchedAt)
            if (isPrior && !complete) {
                // Incomplete prior period cached mid-day: invalidate so we fetch full completed period
                memoryHistoryCache.remove(key)
            } else if (complete || (!isPrior && System.currentTimeMillis() - fetchedAt < 2 * 60 * 60 * 1000L + 30 * 60 * 1000L)) {
                return cached
            }
        }

        val file = File(parsedCacheDir, "$key.json")
        if (file.exists()) {
            val complete = isPrior && isPeriodComplete(period, date, file.lastModified())
            if (complete) {
                return runCatching {
                    val obj = JSONObject(file.readText())
                    memoryHistoryCache[key] = obj
                    obj
                }.getOrNull()
            }
            if (isPrior) {
                // Prior period file exists but was fetched mid-day before the period completed.
                // Invalidate so we fetch the complete 24 hours now that the period is finished!
                return null
            }
            val maxAge = 2 * 60 * 60 * 1000L + 30 * 60 * 1000L // 2.5 hours for current day
            if (System.currentTimeMillis() - file.lastModified() < maxAge) {
                return runCatching {
                    val obj = JSONObject(file.readText())
                    memoryHistoryCache[key] = obj
                    obj
                }.getOrNull()
            }
        }
        return null
    }

    fun hasCachedHistory(period: String, date: String): Boolean = getCachedHistory(period, date) != null

    private fun saveCachedHistory(period: String, date: String, data: JSONObject) {
        val key = "${period}_$date"
        memoryHistoryCache[key] = data
        runCatching {
            File(parsedCacheDir, "$key.json").writeText(data.toString())
        }
    }

    fun history(period: String, date: String, force: Boolean = false): JSONObject {
        val isPrior = isPriorPeriod(period, date)
        if (!force) {
            getCachedHistory(period, date)?.let { return it }
        }
        val responses = mutableMapOf<String, JSONObject>()
        val warnings = mutableListOf<String>()
        val endpoints = if (period == "month") linkedMapOf(
            "production" to "energy_lifetime", "consumption" to "consumption_lifetime", "import" to "energy_import_lifetime", "export" to "energy_export_lifetime", "charge" to "battery_lifetime"
        ) else linkedMapOf(
            "production" to "telemetry/production_meter", "consumption" to "telemetry/consumption_meter", "import" to "energy_import_telemetry", "export" to "energy_export_telemetry", "charge" to "telemetry/battery"
        )
        val today = LocalDate.now(zone)
        val selected = if (period == "month") YearMonth.parse(date).atDay(1) else LocalDate.parse(date)
        val current = if (period == "month") YearMonth.from(today) == YearMonth.from(selected) else selected == today
        val ttl = if (current) 2 * 60 * 60 * 1000L + 30 * 60 * 1000L else 365 * 24 * 60 * 60 * 1000L
        val params = if (period == "month") mapOf("start_date" to selected.toString(), "end_date" to minOf(YearMonth.from(selected).atEndOfMonth(), today).toString())
            else mapOf("start_at" to selected.atStartOfDay(zone).toEpochSecond().toString(), "granularity" to "day")

        val shouldForce = force || isPrior
        for ((metric, path) in endpoints) {
            try {
                val response = request(path, params, ttl, force = shouldForce)
                responses[metric] = response
                if (response.has("_cache_warning")) warnings.add(response.getString("_cache_warning"))
                if (metric == "charge") responses["discharge"] = response
            } catch (error: Exception) {
                if (error.message?.contains("authorization", true) == true || !configured()) throw error
                warnings.add(error.message ?: "$metric unavailable")
            }
        }
        val bars = if (period == "month") HistoryParser.month(date, responses) else HistoryParser.day(date, responses)
        val nowMs = System.currentTimeMillis()
        val isComplete = isPrior && isPeriodComplete(period, date, nowMs)
        val result = JSONObject().put("period", period).put("date", date).put("bars", bars)
            .put("source", if (isComplete) "Enphase cloud · cached permanently" else "Enphase cloud · cached")
            .put("_fetched_at", nowMs / 1000)
            .put("updated_at", responses.values.minOfOrNull { it.optLong("_fetched_at", 0) } ?: (nowMs / 1000))
            .put("warning", warnings.distinct().joinToString(" · "))
        saveCachedHistory(period, date, result)
        return result
    }
    fun charger(): JSONObject = request("latest_telemetry", emptyMap(), 30 * 60 * 1000L)
    fun callCount(): Int = if (usage.getString("month", "") == YearMonth.now(zone).toString()) usage.getInt("count", 0) else 0

    companion object {
        fun isPriorPeriod(period: String, date: String, today: LocalDate = LocalDate.now()): Boolean {
            return try {
                if (period == "month") {
                    YearMonth.parse(date) < YearMonth.from(today)
                } else {
                    LocalDate.parse(date) < today
                }
            } catch (_: Exception) { false }
        }

        fun isPeriodComplete(period: String, date: String, timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
            return try {
                if (period == "month") {
                    val endOfMonthMs = YearMonth.parse(date).plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond() * 1000L
                    timestampMs >= endOfMonthMs
                } else {
                    val endOfDayMs = LocalDate.parse(date).plusDays(1).atStartOfDay(zone).toEpochSecond() * 1000L
                    timestampMs >= endOfDayMs
                }
            } catch (_: Exception) { false }
        }
    }
}
