package com.fajar.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

        // 1. Primary: Cloudflare Edge (50ms response, immune to IP blocklists, provides direct ASN & IP)
        try {
            val cfReq = Request.Builder()
                .url("https://speed.cloudflare.com/__down?bytes=0")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()
            client.newCall(cfReq).execute().use { res ->
                val headerIp = res.header("cf-meta-ip") ?: res.header("ip")
                val headerAsn = res.header("asn") ?: res.header("cf-meta-asn")
                val headerCity = res.header("city") ?: res.header("cf-meta-city")
                val headerCountry = res.header("country") ?: res.header("cf-meta-country")
                val headerColo = res.header("colo") ?: res.header("cf-meta-colo")
                val headerLat = (res.header("latitude") ?: res.header("cf-meta-latitude"))?.toDoubleOrNull()
                val headerLon = (res.header("longitude") ?: res.header("cf-meta-longitude"))?.toDoubleOrNull()

                if (!headerIp.isNullOrBlank()) ip = headerIp
                if (!headerColo.isNullOrBlank()) colo = headerColo
                if (headerLat != null) lat = headerLat
                if (headerLon != null) lon = headerLon

                if (!headerAsn.isNullOrBlank()) {
                    asn = "AS$headerAsn"
                    val asnNum = headerAsn.toIntOrNull()
                    val resolved = AsnResolver.resolveIsp(asnNum)
                    if (resolved != null) {
                        isp = resolved
                    } else {
                        isp = "Network AS$headerAsn"
                    }
                }

                if (!headerCity.isNullOrBlank()) {
                    location = if (!headerCountry.isNullOrBlank()) "$headerCity, $headerCountry" else headerCity
                }
            }
        } catch (_: Exception) {}

        // 2. Secondary enrichment: Only if ISP is unknown, query ipwho.is with short 2s timeout
        if (isp.startsWith("Network AS") || isp == "--") {
            try {
                val req = Request.Builder()
                    .url("https://ipwho.is/")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val quickClient = client.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build()

                quickClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty()
                        val json = JSONObject(body)
                        if (json.optBoolean("success", true)) {
                            if (ip == "--") ip = json.optString("ip", ip)
                            val city = json.optString("city", "")
                            val country = json.optString("country", "")
                            if (location == "--" && city.isNotBlank()) location = "$city, $country"
                            if (lat == null) lat = json.optDouble("latitude")
                            if (lon == null) lon = json.optDouble("longitude")

                            val conn = json.optJSONObject("connection")
                            if (conn != null) {
                                val orgIsp = conn.optString("isp", conn.optString("org", ""))
                                if (orgIsp.isNotBlank()) isp = orgIsp
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Final guard: Ensure ISP is never left as empty placeholder
        if (isp == "--" && asn != "--") {
            isp = "ISP $asn"
        } else if (isp == "--") {
            isp = "Koneksi Terhubung"
        }
        if (location == "--") {
            location = "Indonesia"
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
                            downloadMbps = smoothedSpeed,
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

        val totalTestTimeMs = 7000L
        val startTime = System.currentTimeMillis()
        val recordedSamples = mutableListOf<Double>()
        var smoothedSpeed = 0.0

        // Multi-chunk sequential upload: measures TRUE end-to-end round trip wire delivery.
        // Prevents TCP socket buffer dumps from giving fake 100+ Mbps numbers.
        val dummySmall = ByteArray(512 * 1024) { 0x5A.toByte() }  // 512KB warmup chunk
        val dummyMedium = ByteArray(1024 * 1024) { 0x5A.toByte() } // 1MB standard chunk
        val dummyLarge = ByteArray(2 * 1024 * 1024) { 0x5A.toByte() } // 2MB fast chunk

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        var round = 0

        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val elapsed = now - startTime
            if (elapsed >= totalTestTimeMs) break

            val payload = when {
                round == 0 -> dummySmall
                smoothedSpeed > 25.0 -> dummyLarge
                else -> dummyMedium
            }

            val reqBody = payload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(server.uploadUrl)
                .header("Origin", "https://speed.cloudflare.com")
                .header("Referer", "https://speed.cloudflare.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .post(reqBody)
                .build()

            val chunkStart = System.nanoTime()
            try {
                client.newCall(request).execute().use { response ->
                    response.body?.string()
                }
                val chunkDurationSec = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                if (chunkDurationSec > 0.02) {
                    val chunkMbps = (payload.size * 8.0) / (chunkDurationSec * 1_000_000.0)
                    smoothedSpeed = if (smoothedSpeed == 0.0) chunkMbps else (smoothedSpeed * 0.4 + chunkMbps * 0.6)

                    if (round > 0) { // Ignore first round (warmup TCP handshake)
                        recordedSamples.add(chunkMbps)
                    }

                    val currentElapsed = System.currentTimeMillis() - startTime
                    val progress = ((currentElapsed.toFloat() / totalTestTimeMs) * 100).toInt().coerceIn(0, 100)
                    state = state.copy(
                        currentSpeedMbps = smoothedSpeed,
                        uploadMbps = smoothedSpeed,
                        progress = progress
                    )
                    onProgress(state)
                }
            } catch (e: Exception) {
                if (!coroutineContext.isActive) throw e
            }
            round++
        }

        val finalSpeed = when {
            recordedSamples.isNotEmpty() -> {
                val sorted = recordedSamples.sorted()
                val cutIndex = (sorted.size * 0.15).toInt()
                sorted.subList(cutIndex, sorted.size).average()
            }
            smoothedSpeed > 0 -> smoothedSpeed
            else -> 0.0
        }

        state = state.copy(uploadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
        return state
    }
}
