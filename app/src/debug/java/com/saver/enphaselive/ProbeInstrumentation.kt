package com.saver.enphaselive

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProbeInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val client = GatewayClient(
            hostProvider = { "envoy.local" },
            tokenProvider = { TokenStore(targetContext).read() },
            bundledCert = runCatching { targetContext.resources.openRawResource(R.raw.gateway_cert).use { it.readBytes() } }.getOrNull()
        )
        val output = JSONArray()
        if (args.getString("mode") == "sample") {
            repeat(12) {
                val start = System.currentTimeMillis()
                val row = runCatching {
                    val data = client.live()
                    JSONObject().put("received", System.currentTimeMillis()/1000).put("latency_ms", System.currentTimeMillis()-start)
                        .put("gateway_time", data.optJSONObject("meters")?.optLong("last_update"))
                        .put("pv", data.optJSONObject("meters")?.optJSONObject("pv")?.optLong("agg_p_mw"))
                }.getOrElse { JSONObject().put("error", it.message) }
                output.put(row)
                Thread.sleep(maxOf(0, 1000-(System.currentTimeMillis()-start)))
            }
        } else {
            val paths = args.getString("paths")?.split(',') ?: listOf("/ivp/livedata/status")
            for (path in paths) {
                val result = runCatching { client.readRaw(path) }
                output.put(JSONObject().put("path", path).put("result", result.getOrNull()).put("error", result.exceptionOrNull()?.message))
            }
        }
        File(targetContext.noBackupFilesDir,"probe.json").writeText(output.toString())
        finish(0, Bundle().apply { putString("result", "Probe completed; results stored privately") })
    }
}
