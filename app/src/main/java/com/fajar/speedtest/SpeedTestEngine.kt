package com.fajar.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

enum class TestStage {
    IDLE,
    INITIALIZING,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
    ERROR
}

data class SpeedState(
    val stage: TestStage = TestStage.IDLE,
    val currentSpeedMbps: Double = 0.0,
    val pingMs: Long = 0,
    val jitterMs: Long = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val ip: String = "--",
    val isp: String = "--",
    val asn: String = "--",
    val location: String = "--",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isGpsLocation: Boolean = false,
    val serverName: String = "--",
    val serverLocation: String = "--",
    val progress: Int = 0,
    val errorMessage: String? = null
)

class SpeedTestEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetchProviderInfo(): SpeedState = withContext(Dispatchers.IO) {
        var ip = "--"
        var isp = "--"
        var asn = "--"
        var location = "--"
        var lat: Double? = null
        var lon: Double? = null
        var colo = "CGK"

        // 1. Try ipwho.is (fast HTTPS, rich metadata)
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (json.optBoolean("success", true)) {
                        ip = json.optString("ip", ip)
                        val city = json.optString("city", "")
                        val country = json.optString("country", "")
                        location = if (city.isNotBlank()) "$city, $country" else country

                        lat = json.optDouble("latitude")
                        lon = json.optDouble("longitude")

                        val conn = json.optJSONObject("connection")
                        if (conn != null) {
                            isp = conn.optString("isp", conn.optString("org", isp))
                            val asnNum = conn.optInt("asn", 0)
                            if (asnNum > 0) asn = "AS$asnNum"
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Cloudflare trace fallback
        if (isp == "--" || ip == "--" || lat == null) {
            try {
                val cfReq = Request.Builder()
                    .url("https://speed.cloudflare.com/__down?bytes=0")
                    .build()
                client.newCall(cfReq).execute().use { res ->
                    val cfIp = res.header("cf-meta-ip")
                    val cfAsn = res.header("cf-meta-asn")
                    val cfCity = res.header("cf-meta-city")
                    val cfCountry = res.header("cf-meta-country")
                    val cfColo = res.header("cf-meta-colo")
                    val cfLat = res.header("cf-meta-latitude")?.toDoubleOrNull()
                    val cfLon = res.header("cf-meta-longitude")?.toDoubleOrNull()

                    if (!cfIp.isNullOrBlank()) ip = cfIp
                    if (!cfAsn.isNullOrBlank()) asn = "AS$cfAsn"
                    if (!cfColo.isNullOrBlank()) colo = cfColo
                    if (lat == null && cfLat != null) lat = cfLat
                    if (lon == null && cfLon != null) lon = cfLon

                    if (location == "--" && !cfCity.isNullOrBlank()) {
                        location = "$cfCity, ${cfCountry ?: ""}"
                    }
                    if (isp == "--" && !cfAsn.isNullOrBlank()) {
                        isp = "Network AS$cfAsn"
                    }
                }
            } catch (_: Exception) {}
        }

        SpeedState(
            ip = ip,
            isp = isp,
            asn = asn,
            location = location,
            latitude = lat,
            longitude = lon,
            isGpsLocation = false,
            serverName = "Cloudflare Edge ($colo)",
            serverLocation = location
        )
    }

    suspend fun runSpeedTest(
        server: SpeedServer,
        initialState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ) = withContext(Dispatchers.IO) {
        var state = initialState.copy(
            stage = TestStage.INITIALIZING,
            serverName = server.getDisplayNameWithDistance(),
            serverLocation = server.region,
            progress = 0
        )
        onProgress(state)

        try {
            // Refresh provider info if empty
            if (state.isp == "--" || state.ip == "--") {
                val fetched = fetchProviderInfo()
                state = state.copy(
                    ip = fetched.ip,
                    isp = fetched.isp,
                    asn = fetched.asn,
                    location = if (state.location == "--") fetched.location else state.location,
                    latitude = state.latitude ?: fetched.latitude,
                    longitude = state.longitude ?: fetched.longitude
                )
                onProgress(state)
            }

            // 1. Ping & Jitter
            state = measurePingAndJitter(server, state, onProgress)
            onProgress(state)

            // 2. Download Speed Test
            state = measureDownloadSpeed(server, state, onProgress)
            onProgress(state)

            // 3. Upload Speed Test
            state = measureUploadSpeed(server, state, onProgress)
            onProgress(state)

            state = state.copy(
                stage = TestStage.COMPLETED,
                currentSpeedMbps = state.downloadMbps,
                progress = 100
            )
            onProgress(state)

        } catch (e: Exception) {
            if (coroutineContext.isActive) {
                state = state.copy(
                    stage = TestStage.ERROR,
                    currentSpeedMbps = 0.0,
                    errorMessage = e.message ?: "Test interrupted"
                )
                onProgress(state)
            }
        }
    }

    private fun measurePingAndJitter(
        server: SpeedServer,
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.PING, progress = 0)
        onProgress(state)

        val pingSamples = mutableListOf<Long>()
        val request = Request.Builder()
            .url(server.pingUrl)
            .build()

        val totalRounds = 6
        for (i in 0 until totalRounds) {
            val start = System.nanoTime()
            try {
                client.newCall(request).execute().use { response ->
                    response.body?.string()
                }
                val durationMs = (System.nanoTime() - start) / 1_000_000
                if (i > 0) {
                    pingSamples.add(durationMs)
                }
            } catch (_: IOException) {}

            val progress = ((i + 1) * 100) / totalRounds
            val minPing = if (pingSamples.isNotEmpty()) pingSamples.minOrNull() ?: 0L else 0L
            val jitter = if (pingSamples.size > 1) {
                pingSamples.zipWithNext { a, b -> abs(a - b) }.average().toLong()
            } else 0L

            state = state.copy(pingMs = minPing, jitterMs = jitter, progress = progress)
            onProgress(state)
        }

        return state
    }

    private suspend fun measureDownloadSpeed(
        server: SpeedServer,
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.DOWNLOAD, progress = 0)
        onProgress(state)

        val request = Request.Builder()
            .url(server.downloadUrl)
            .build()

        val buffer = ByteArray(64 * 1024)
        val testDurationMs = 8000L
        var totalBytes = 0L
        var smoothedSpeed = 0.0
        val recordedSamples = mutableListOf<Double>()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val inputStream = response.body?.byteStream() ?: throw IOException("Empty response")

                val startTime = System.currentTimeMillis()
                var lastSampleTime = startTime
                var lastSampleBytes = 0L

                while (coroutineContext.isActive) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytes += read

                    val now = System.currentTimeMillis()
                    val interval = now - lastSampleTime
                    if (interval >= 120) {
                        val bytesDiff = totalBytes - lastSampleBytes
                        val instantMbps = (bytesDiff * 8.0) / (interval * 1000.0)
                        smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.35 + instantMbps * 0.65)

                        if (now - startTime > 800) {
                            recordedSamples.add(smoothedSpeed)
                        }

                        val elapsed = now - startTime
                        val progress = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                        state = state.copy(
                            currentSpeedMbps = smoothedSpeed,
                            downloadMbps = smoothedSpeed, // Live update to download box!
                            progress = progress
                        )
                        onProgress(state)

                        lastSampleTime = now
                        lastSampleBytes = totalBytes
                    }

                    if (now - startTime >= testDurationMs) break
                }

                val totalElapsed = System.currentTimeMillis() - startTime
                val finalSpeed = when {
                    recordedSamples.isNotEmpty() -> {
                        val sorted = recordedSamples.sorted()
                        val cutIndex = (sorted.size * 0.15).toInt()
                        sorted.subList(cutIndex, sorted.size).average()
                    }
                    totalElapsed > 0 -> (totalBytes * 8.0) / (totalElapsed * 1000.0)
                    else -> smoothedSpeed
                }
                state = state.copy(downloadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
            }
        } catch (e: Exception) {
            if (!coroutineContext.isActive) throw e
            val finalSpeed = if (recordedSamples.isNotEmpty()) recordedSamples.average() else smoothedSpeed
            state = state.copy(downloadMbps = finalSpeed, currentSpeedMbps = finalSpeed)
        }

        return state
    }

    private suspend fun measureUploadSpeed(
        server: SpeedServer,
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.UPLOAD, progress = 0)
        onProgress(state)

        val uploadDurationMs = 7000L
        val dummyPayload = ByteArray(64 * 1024) { 0x5A.toByte() }
        var totalBytesUploaded = 0L
        var smoothedSpeed = 0.0
        val recordedSamples = mutableListOf<Double>()

        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                val startTime = System.currentTimeMillis()
                var lastSampleTime = startTime
                var lastSampleBytes = 0L

                while (true) {
                    val now = System.currentTimeMillis()
                    if (now - startTime >= uploadDurationMs) break

                    sink.write(dummyPayload)
                    sink.flush()
                    totalBytesUploaded += dummyPayload.size

                    val interval = now - lastSampleTime
                    if (interval >= 120) {
                        val bytesDiff = totalBytesUploaded - lastSampleBytes
                        val instantMbps = (bytesDiff * 8.0) / (interval * 1000.0)
                        smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.35 + instantMbps * 0.65)

                        if (now - startTime > 800) {
                            recordedSamples.add(smoothedSpeed)
                        }

                        val elapsed = now - startTime
                        val progress = ((elapsed.toFloat() / uploadDurationMs) * 100).toInt().coerceIn(0, 100)
                        state = state.copy(
                            currentSpeedMbps = smoothedSpeed,
                            uploadMbps = smoothedSpeed, // Live update to upload box!
                            progress = progress
                        )
                        onProgress(state)

                        lastSampleTime = now
                        lastSampleBytes = totalBytesUploaded
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(server.uploadUrl)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val finalSpeed = when {
                    recordedSamples.isNotEmpty() -> {
                        // Drop lowest 15% ramp-up outliers, average steady sustained rate
                        val sorted = recordedSamples.sorted()
                        val cutIndex = (sorted.size * 0.15).toInt()
                        sorted.subList(cutIndex, sorted.size).average()
                    }
                    smoothedSpeed > 0 -> smoothedSpeed
                    else -> 0.0
                }
                state = state.copy(uploadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
            }
        } catch (e: Exception) {
            if (!coroutineContext.isActive) throw e
            val finalSpeed = if (recordedSamples.isNotEmpty()) {
                recordedSamples.average()
            } else if (smoothedSpeed > 0) smoothedSpeed else 0.0
            state = state.copy(uploadMbps = finalSpeed, currentSpeedMbps = finalSpeed)
        }

        return state
    }
}
