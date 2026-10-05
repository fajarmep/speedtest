package com.fajar.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        })
        .retryOnConnectionFailure(true)
        .build()

    fun cancel() {
        try {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        } catch (_: Exception) {}
    }

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
                    }
                }

                if (!headerCity.isNullOrBlank()) {
                    location = if (!headerCountry.isNullOrBlank()) "$headerCity, $headerCountry" else headerCity
                }
            }
        } catch (_: Exception) {}

        val cleanAsnNum = asn.removePrefix("AS").toIntOrNull()

        // 2. Secondary Enrichment: FreeIPAPI (fast, zero key, provides clean company name)
        if (isp == "--" || isp.isBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://freeipapi.com/api/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
                val quickClient = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                quickClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val org = json.optString("asnOrganization", "").trim()
                        if (org.isNotBlank() && !org.equals("null", ignoreCase = true)) {
                            isp = org
                        }
                        if (ip == "--") ip = json.optString("ipAddress", ip)
                        val city = json.optString("cityName", "")
                        val country = json.optString("countryName", "")
                        if (location == "--" && city.isNotBlank()) location = "$city, $country"
                        if (lat == null) lat = json.optDouble("latitude")
                        if (lon == null) lon = json.optDouble("longitude")
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Third Enrichment: Official APNIC RDAP Registry (Authoritative for all AS3xxxx & Asia-Pacific ASNs)
        if ((isp == "--" || isp.isBlank()) && cleanAsnNum != null) {
            try {
                val req = Request.Builder()
                    .url("https://rdap.apnic.net/autnum/$cleanAsnNum")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val quickClient = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                quickClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val rawName = json.optString("name", "").trim()
                        if (rawName.isNotBlank()) {
                            isp = AsnResolver.cleanApnicName(rawName)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Fourth Enrichment: IPWhois (Generous 4s timeout)
        if (isp == "--" || isp.isBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://ipwho.is/")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val quickClient = client.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                quickClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val conn = json.optJSONObject("connection")
                        if (conn != null) {
                            val orgIsp = conn.optString("isp", conn.optString("org", "")).trim()
                            if (orgIsp.isNotBlank()) isp = orgIsp
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

            // 2. Download Speed Test (Multi-stream + 90th percentile)
            state = measureDownloadSpeed(server, state, onProgress)
            onProgress(state)

            // 3. Upload Speed Test (Multi-stream + 90th percentile)
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
                    response.body?.source()?.skip(Long.MAX_VALUE)
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
    ): SpeedState = withContext(Dispatchers.IO) {
        var state = currentState.copy(stage = TestStage.DOWNLOAD, progress = 0)
        onProgress(state)

        val testDurationMs = 8000L
        val warmupDurationMs = 1200L // Ookla standard: discard first 1.2s ramp-up
        val totalBytes = AtomicLong(0L)
        val isRunning = AtomicBoolean(true)

        val parallelStreams = 3 // Multi-stream saturation
        val downloadJobs = (0 until parallelStreams).map {
            launch {
                val buffer = ByteArray(64 * 1024)
                while (isRunning.get() && isActive) {
                    val request = Request.Builder()
                        .url(server.downloadUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                        .build()
                    try {
                        client.newCall(request).execute().use { response ->
                            val stream = response.body?.byteStream() ?: return@use
                            while (isRunning.get() && isActive) {
                                val read = stream.read(buffer)
                                if (read == -1) break
                                totalBytes.addAndGet(read.toLong())
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val startTime = System.currentTimeMillis()
        var lastTime = startTime
        var lastBytes = 0L
        var smoothedSpeed = 0.0
        val sliceSamples = mutableListOf<Double>()

        while (isActive) {
            delay(100)
            val now = System.currentTimeMillis()
            val elapsed = now - startTime
            if (elapsed >= testDurationMs) break

            val currentBytes = totalBytes.get()
            val intervalMs = now - lastTime
            if (intervalMs >= 100) {
                val bytesDiff = currentBytes - lastBytes
                val instantMbps = (bytesDiff * 8.0) / (intervalMs * 1000.0)
                smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.35 + instantMbps * 0.65)

                if (elapsed >= warmupDurationMs && instantMbps > 0.0) {
                    sliceSamples.add(instantMbps)
                }

                val progress = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                state = state.copy(
                    currentSpeedMbps = smoothedSpeed,
                    downloadMbps = smoothedSpeed,
                    progress = progress
                )
                onProgress(state)

                lastTime = now
                lastBytes = currentBytes
            }
        }

        isRunning.set(false)
        downloadJobs.forEach { it.cancel() }

        val finalSpeed = calculateOokla90thPercentile(sliceSamples, smoothedSpeed)
        state = state.copy(downloadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
        state
    }

    private suspend fun measureUploadSpeed(
        server: SpeedServer,
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState = withContext(Dispatchers.IO) {
        var state = currentState.copy(stage = TestStage.UPLOAD, progress = 0)
        onProgress(state)

        val testDurationMs = 7000L
        val startTime = System.currentTimeMillis()
        val recordedSamples = mutableListOf<Double>()
        var smoothedSpeed = 0.0

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val payloadWarmup = ByteArray(256 * 1024) { 0x5A.toByte() }
        val payloadSmall = ByteArray(512 * 1024) { 0x5A.toByte() }
        val payloadMed = ByteArray(1024 * 1024) { 0x5A.toByte() }
        val payloadLarge = ByteArray(2 * 1024 * 1024) { 0x5A.toByte() }

        var round = 0

        while (isActive) {
            val now = System.currentTimeMillis()
            val elapsed = now - startTime
            if (elapsed >= testDurationMs) break

            // Select payload size adaptively to ensure responsive, steady sampling (~200-300ms per chunk)
            val payload = when {
                round == 0 -> payloadWarmup
                smoothedSpeed < 18.0 -> payloadSmall
                smoothedSpeed < 70.0 -> payloadMed
                else -> payloadLarge
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
                    response.body?.source()?.skip(Long.MAX_VALUE)
                }
                val chunkDurationSec = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                if (chunkDurationSec > 0.02) {
                    val chunkMbps = (payload.size * 8.0) / (chunkDurationSec * 1_000_000.0)
                    smoothedSpeed = if (smoothedSpeed == 0.0) chunkMbps else (smoothedSpeed * 0.35 + chunkMbps * 0.65)

                    if (round > 0) { // Discard warmup chunk (round 0)
                        recordedSamples.add(chunkMbps)
                    }

                    val currentElapsed = System.currentTimeMillis() - startTime
                    val progress = ((currentElapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
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

        val finalSpeed = calculateOokla90thPercentile(recordedSamples, smoothedSpeed)
        state = state.copy(uploadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
        state
    }

    /**
     * Standard Ookla Methodology:
     * Discards ramp-up, sorts steady state rate slices, and takes 90th percentile
     * (or trimmed 80th-92nd sustained window to filter single micro-spikes).
     */
    private fun calculateOokla90thPercentile(samples: List<Double>, fallback: Double): Double {
        if (samples.isEmpty()) return fallback
        val sorted = samples.sorted()
        return if (sorted.size >= 10) {
            val fromIndex = (sorted.size * 0.80).toInt()
            val toIndex = (sorted.size * 0.92).toInt().coerceAtLeast(fromIndex + 1).coerceAtMost(sorted.size)
            sorted.subList(fromIndex, toIndex).average()
        } else {
            val idx = (sorted.size * 0.90).toInt().coerceIn(0, sorted.lastIndex)
            sorted[idx]
        }
    }
}
