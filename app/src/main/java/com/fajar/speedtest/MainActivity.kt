package com.fajar.speedtest

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fajar.speedtest.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = SpeedTestEngine()
    private var testJob: Job? = null
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnAction.setOnClickListener {
            if (isRunning) {
                stopTest()
            } else {
                startTest()
            }
        }
    }

    private fun startTest() {
        isRunning = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnAction.text = getString(R.string.stop_test)
        binding.btnAction.setBackgroundColor(getColor(R.color.border_stroke))

        // Reset display
        binding.tvPingVal.text = "-- ms"
        binding.tvJitterVal.text = "-- ms"
        binding.tvDownloadVal.text = "-- Mbps"
        binding.tvUploadVal.text = "-- Mbps"
        binding.tvLiveSpeed.text = "0.00"
        binding.progressBar.progress = 0

        testJob = lifecycleScope.launch {
            engine.runSpeedTest { state ->
                runOnUiThread {
                    updateUi(state)
                }
            }
            onTestFinished()
        }
    }

    private fun stopTest() {
        testJob?.cancel()
        testJob = null
        onTestFinished()
        binding.tvPhaseLabel.text = "STOPPED"
        binding.progressBar.progress = 0
    }

    private fun onTestFinished() {
        isRunning = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnAction.text = getString(R.string.start_test)
        binding.btnAction.setBackgroundColor(getColor(R.color.primary))
    }

    private fun updateUi(state: SpeedState) {
        binding.progressBar.progress = state.progress

        if (state.ip.isNotBlank() && state.ip != "--") {
            binding.tvIpVal.text = state.ip
        }
        if (state.colo.isNotBlank() && state.colo != "--") {
            binding.tvServerVal.text = "Cloudflare (${state.colo})"
        }

        if (state.pingMs > 0) {
            binding.tvPingVal.text = String.format(Locale.US, "%d ms", state.pingMs)
        }
        if (state.jitterMs > 0) {
            binding.tvJitterVal.text = String.format(Locale.US, "%d ms", state.jitterMs)
        }

        if (state.downloadMbps > 0) {
            binding.tvDownloadVal.text = String.format(Locale.US, "%.2f Mbps", state.downloadMbps)
        }
        if (state.uploadMbps > 0) {
            binding.tvUploadVal.text = String.format(Locale.US, "%.2f Mbps", state.uploadMbps)
        }

        when (state.stage) {
            TestStage.IDLE -> {
                binding.tvPhaseLabel.text = getString(R.string.status_ready)
                binding.tvLiveSpeed.text = "0.00"
            }
            TestStage.INITIALIZING -> {
                binding.tvPhaseLabel.text = getString(R.string.status_connecting)
                binding.tvLiveSpeed.text = "0.00"
            }
            TestStage.PING -> {
                binding.tvPhaseLabel.text = getString(R.string.status_testing_ping)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%d", state.pingMs)
                binding.tvSpeedUnit.text = "ms"
            }
            TestStage.DOWNLOAD -> {
                binding.tvPhaseLabel.text = getString(R.string.status_testing_download)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.currentSpeedMbps)
                binding.tvSpeedUnit.text = "Mbps"
            }
            TestStage.UPLOAD -> {
                binding.tvPhaseLabel.text = getString(R.string.status_testing_upload)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.currentSpeedMbps)
                binding.tvSpeedUnit.text = "Mbps"
            }
            TestStage.COMPLETED -> {
                binding.tvPhaseLabel.text = getString(R.string.status_completed)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.downloadMbps)
                binding.tvSpeedUnit.text = "Mbps"
            }
            TestStage.ERROR -> {
                binding.tvPhaseLabel.text = state.errorMessage ?: "ERROR"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
    }
}
