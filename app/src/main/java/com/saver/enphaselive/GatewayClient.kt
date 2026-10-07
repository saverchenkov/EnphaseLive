package com.saver.enphaselive

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Communicates with the local Enphase Envoy Gateway over HTTPS.
 * Supports configurable gateway host/IP, token authentication, and
 * Trust-on-First-Use (TOFU) SHA-256 certificate pinning for secure MITM protection.
 */
class GatewayClient(
    private val hostProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val bundledCert: ByteArray? = null,
    private val pinnedFingerprintProvider: () -> String? = { null },
    private val onPinFingerprint: (String) -> Unit = {}
) {
    companion object {
        fun sha256Fingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }
    }

    private fun isCertTrusted(cert: X509Certificate): Boolean {
        // 1. Check against bundled certificate bytes if provided
        if (bundledCert != null && bundledCert.contentEquals(cert.encoded)) {
            return true
        }

        val fp = sha256Fingerprint(cert)
        val pinned = pinnedFingerprintProvider()

        // 2. Check against user-pinned fingerprint
        if (!pinned.isNullOrBlank()) {
            return pinned.equals(fp, ignoreCase = true)
        }

        // 3. Trust-on-First-Use (TOFU): Record and pin this certificate fingerprint
        onPinFingerprint(fp)
        return true
    }

    private val context: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(@android.annotation.SuppressLint("CustomX509TrustManager") object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificate authentication not supported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Certificate chain is empty")
                val serverCert = chain[0]
                if (!isCertTrusted(serverCert)) {
                    val fp = sha256Fingerprint(serverCert)
                    val pinned = pinnedFingerprintProvider()
                    throw CertificateException("Gateway certificate fingerprint mismatch. Pinned: $pinned, Got: $fp")
                }
            }
        }), null)
    }

    internal fun readRaw(path: String, body: String? = null): String {
        val credential = tokenProvider() ?: error("Add your gateway token in Settings")
        val host = hostProvider().trim().ifEmpty { "envoy.local" }
        val connection = URL("https://$host$path").openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = context.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, session ->
                val certs = session.peerCertificates
                if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                    isCertTrusted(certs[0] as X509Certificate)
                } else {
                    false
                }
            }
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            when (val code = connection.responseCode) {
                200 -> return connection.inputStream.bufferedReader().use { it.readText() }
                401, 403 -> error("Gateway token expired or rejected. Replace it in Settings.")
                else -> error("Gateway returned HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, body: String? = null) = JSONObject(readRaw(path, body))

    fun live(): JSONObject {
        val data = request("/ivp/livedata/status")
        if (data.optJSONObject("connection")?.optString("sc_stream") == "disabled") {
            request("/ivp/livedata/stream", "{\"enable\":1}")
            return request("/ivp/livedata/status")
        }
        return data
    }
}
