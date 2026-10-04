package com.fajar.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
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
    val colo: String = "--",
    val progress: Int = 0,
    val errorMessage: String? = null
)

class SpeedTestEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun runSpeedTest(onProgress: (SpeedState) -> Unit) = withContext(Dispatchers.IO) {
        var state = SpeedState(stage = TestStage.INITIALIZING)
        onProgress(state)

        try {
            // 1. Fetch IP & Cloudflare edge metadata
            state = fetchMetadata(state)
            onProgress(state)

            // 2. Measure Ping & Jitter
            state = measurePingAndJitter(state, onProgress)
            onProgress(state)

            // 3. Download Speed Test
            state = measureDownloadSpeed(state, onProgress)
            onProgress(state)

            // 4. Upload Speed Test
            state = measureUploadSpeed(state, onProgress)
            onProgress(state)

            state = state.copy(
                stage = TestStage.COMPLETED,
                currentSpeedMbps = 0.0,
                progress = 100
            )
            onProgress(state)

        } catch (e: Exception) {
            if (coroutineContext.isActive) {
                state = state.copy(
                    stage = TestStage.ERROR,
                    currentSpeedMbps = 0.0,
                    errorMessage = e.message ?: "Test interrupted or network failed"
                )
                onProgress(state)
            }
        }
    }

    private fun fetchMetadata(currentState: SpeedState): SpeedState {
        val request = Request.Builder()
            .url("https://cloudflare.com/cdn-cgi/trace")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return currentState
                val body = response.body?.string().orEmpty()
                var ip = currentState.ip
                var colo = currentState.colo
                for (line in body.lineSequence()) {
                    if (line.startsWith("ip=")) ip = line.substringAfter("ip=").trim()
                    if (line.startsWith("colo=")) colo = line.substringAfter("colo=").trim()
                }
                currentState.copy(ip = ip, colo = colo)
            }
        } catch (_: IOException) {
            currentState
        }
    }

    private fun measurePingAndJitter(
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.PING, progress = 0)
        onProgress(state)

        val pingSamples = mutableListOf<Long>()
        val request = Request.Builder()
            .url("https://cloudflare.com/cdn-cgi/trace")
            .build()

        val totalRounds = 6
        for (i in 0 until totalRounds) {
            val start = System.nanoTime()
            try {
                client.newCall(request).execute().use { response ->
                    response.body?.string()
                }
                val durationMs = (System.nanoTime() - start) / 1_000_000
                if (i > 0) { // Discard warm-up round
                    pingSamples.add(durationMs)
                }
            } catch (_: IOException) {
                // Ignore single ping failure
            }

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
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.DOWNLOAD, progress = 0)
        onProgress(state)

        val request = Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=50000000") // 50MB payload
            .build()

        val buffer = ByteArray(64 * 1024)
        val testDurationMs = 8000L
        var totalBytes = 0L
        var smoothedSpeed = 0.0

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download test HTTP ${response.code}")
                val inputStream = response.body?.byteStream() ?: throw IOException("Empty response body")

                val startTime = System.currentTimeMillis()
                var lastSampleTime = startTime
                var lastSampleBytes = 0L

                while (coroutineContext.isActive) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytes += read

                    val now = System.currentTimeMillis()
                    val interval = now - lastSampleTime
                    if (interval >= 150) {
                        val bytesDiff = totalBytes - lastSampleBytes
                        val instantMbps = (bytesDiff * 8.0) / (interval * 1000.0)
                        smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.35 + instantMbps * 0.65)

                        val elapsed = now - startTime
                        val progress = ((elapsed.toFloat() / testDurationMs) * 100).toInt().coerceIn(0, 100)
                        state = state.copy(currentSpeedMbps = smoothedSpeed, progress = progress)
                        onProgress(state)

                        lastSampleTime = now
                        lastSampleBytes = totalBytes
                    }

                    if (now - startTime >= testDurationMs) {
                        break
                    }
                }

                val totalElapsed = System.currentTimeMillis() - startTime
                val avgSpeed = if (totalElapsed > 0) (totalBytes * 8.0) / (totalElapsed * 1000.0) else smoothedSpeed
                state = state.copy(downloadMbps = avgSpeed, currentSpeedMbps = avgSpeed, progress = 100)
            }
        } catch (e: Exception) {
            if (!coroutineContext.isActive) throw e
            // If download stream ended early, record what was achieved
            if (totalBytes > 0) {
                state = state.copy(downloadMbps = smoothedSpeed)
            } else {
                throw e
            }
        }

        return state
    }

    private suspend fun measureUploadSpeed(
        currentState: SpeedState,
        onProgress: (SpeedState) -> Unit
    ): SpeedState {
        var state = currentState.copy(stage = TestStage.UPLOAD, progress = 0)
        onProgress(state)

        val uploadDurationMs = 7000L
        val dummyPayload = ByteArray(64 * 1024) { 0x55.toByte() }
        var totalBytesUploaded = 0L
        var smoothedSpeed = 0.0

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
                    if (interval >= 150) {
                        val bytesDiff = totalBytesUploaded - lastSampleBytes
                        val instantMbps = (bytesDiff * 8.0) / (interval * 1000.0)
                        smoothedSpeed = if (smoothedSpeed == 0.0) instantMbps else (smoothedSpeed * 0.35 + instantMbps * 0.65)

                        val elapsed = now - startTime
                        val progress = ((elapsed.toFloat() / uploadDurationMs) * 100).toInt().coerceIn(0, 100)
                        state = state.copy(currentSpeedMbps = smoothedSpeed, progress = progress)
                        onProgress(state)

                        lastSampleTime = now
                        lastSampleBytes = totalBytesUploaded
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("https://speed.cloudflare.com/__up")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val finalSpeed = if (smoothedSpeed > 0) smoothedSpeed else 0.0
                state = state.copy(uploadMbps = finalSpeed, currentSpeedMbps = finalSpeed, progress = 100)
            }
        } catch (e: Exception) {
            if (!coroutineContext.isActive) throw e
            if (totalBytesUploaded > 0) {
                state = state.copy(uploadMbps = smoothedSpeed)
            } else {
                throw e
            }
        }

        return state
    }
}
