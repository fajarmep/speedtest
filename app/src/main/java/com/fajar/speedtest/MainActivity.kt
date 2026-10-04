package com.fajar.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fajar.speedtest.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = SpeedTestEngine()
    private var testJob: Job? = null
    private var isRunning = false

    private var currentServer: SpeedServer = ServerCatalog.servers[0]
    private var currentState: SpeedState = SpeedState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateNetworkBadge()
        setupListeners()
        loadInitialProviderInfo()
    }

    private fun updateNetworkBadge() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)

        val badgeText = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "● WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "● CELLULAR"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "● ETHERNET"
            else -> "● ONLINE"
        }
        binding.tvNetworkBadge.text = badgeText
    }

    private fun setupListeners() {
        binding.btnAction.setOnClickListener {
            if (isRunning) {
                stopTest()
            } else {
                startTest()
            }
        }

        binding.btnChangeServer.setOnClickListener {
            showServerSelectionDialog()
        }

        binding.cardServerInfo.setOnClickListener {
            if (!isRunning) {
                showServerSelectionDialog()
            }
        }
    }

    private fun loadInitialProviderInfo() {
        lifecycleScope.launch {
            val providerState = engine.fetchProviderInfo()
            currentState = currentState.copy(
                ip = providerState.ip,
                isp = providerState.isp,
                asn = providerState.asn,
                location = providerState.location
            )
            runOnUiThread {
                updateProviderUi(currentState)
            }
        }
    }

    private fun showServerSelectionDialog() {
        val serverOptions = ServerCatalog.servers.map { "${it.name}\n${it.region}" }.toTypedArray()
        val currentIndex = ServerCatalog.servers.indexOfFirst { it.id == currentServer.id }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_select_server)
            .setSingleChoiceItems(serverOptions, currentIndex) { dialog, which ->
                currentServer = ServerCatalog.servers[which]
                binding.tvServerVal.text = currentServer.name
                binding.tvServerLocation.text = currentServer.region
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTest() {
        isRunning = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnAction.text = getString(R.string.stop_test)
        binding.btnChangeServer.isEnabled = false
        binding.graphView.clear()

        // Reset metrics
        binding.tvPingVal.text = "-- ms"
        binding.tvJitterVal.text = "-- ms"
        binding.tvDownloadVal.text = "-- Mbps"
        binding.tvUploadVal.text = "-- Mbps"
        binding.tvLiveSpeed.text = "0.00"
        binding.progressBar.progress = 0

        testJob = lifecycleScope.launch {
            engine.runSpeedTest(currentServer, currentState) { state ->
                currentState = state
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
        binding.btnChangeServer.isEnabled = true
    }

    private fun updateProviderUi(state: SpeedState) {
        if (state.ip.isNotBlank() && state.ip != "--") {
            binding.tvIpVal.text = state.ip
        }
        if (state.isp.isNotBlank() && state.isp != "--") {
            binding.tvIspVal.text = state.isp
        }
        val detail = buildString {
            if (state.asn.isNotBlank() && state.asn != "--") append(state.asn)
            if (state.location.isNotBlank() && state.location != "--") {
                if (isNotEmpty()) append(" • ")
                append(state.location)
            }
        }
        if (detail.isNotBlank()) {
            binding.tvIspDetail.text = detail
        }
    }

    private fun updateUi(state: SpeedState) {
        updateProviderUi(state)
        binding.progressBar.progress = state.progress

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
                binding.graphView.addPoint(state.currentSpeedMbps.toFloat())
            }
            TestStage.UPLOAD -> {
                binding.tvPhaseLabel.text = getString(R.string.status_testing_upload)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.currentSpeedMbps)
                binding.tvSpeedUnit.text = "Mbps"
                binding.graphView.addPoint(state.currentSpeedMbps.toFloat())
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
