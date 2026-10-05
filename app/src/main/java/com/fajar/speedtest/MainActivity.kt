package com.fajar.speedtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var providerJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null
    private var isRunning = false

    private var serverList: List<SpeedServer> = ServerCatalog.getServersWithDistance(null, null)
    private var currentServer: SpeedServer = serverList[0]
    private var currentState: SpeedState = SpeedState()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchGpsLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateNetworkBadge()
        registerNetworkCallback()
        setupListeners()
        requestLocationPermission()
        loadInitialProviderInfo()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread { updateNetworkBadge() }
                }
                override fun onLost(network: Network) {
                    runOnUiThread { updateNetworkBadge() }
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    runOnUiThread { updateNetworkBadge() }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
                networkCallback = null
            }
        } catch (_: Exception) {}
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

        binding.btnReset.setOnClickListener {
            resetAllMetrics()
        }

        binding.btnTestAgain.setOnClickListener {
            startTest()
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

    private fun requestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            fetchGpsLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGpsLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        var bestLocation: Location? = null
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            bestLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }
        if (bestLocation == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            bestLocation = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }

        if (bestLocation != null) {
            applyLocationCoordinates(bestLocation.latitude, bestLocation.longitude, isGps = true)
        } else {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                removeLocationUpdates()
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        removeLocationUpdates()
                        applyLocationCoordinates(loc.latitude, loc.longitude, isGps = true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                locationListener = listener
                try {
                    lm.requestLocationUpdates(provider, 1000L, 10f, listener, mainLooper)
                } catch (_: Exception) {}
            }
        }
    }

    private fun removeLocationUpdates() {
        try {
            locationListener?.let {
                val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(it)
                locationListener = null
            }
        } catch (_: Exception) {}
    }

    private fun applyLocationCoordinates(lat: Double, lon: Double, isGps: Boolean) {
        currentState = currentState.copy(
            latitude = lat,
            longitude = lon,
            isGpsLocation = isGps
        )
        serverList = ServerCatalog.getServersWithDistance(lat, lon)

        val nearestConcrete = serverList.filter { it.id != "auto" }.minByOrNull { it.distanceKm ?: Double.MAX_VALUE }
        if (currentServer.id == "auto" && nearestConcrete != null) {
            val dist = nearestConcrete.distanceKm?.let {
                if (it < 10) String.format(Locale.US, "%.1f km", it) else String.format(Locale.US, "%.0f km", it)
            } ?: ""
            currentServer = currentServer.copy(
                distanceKm = nearestConcrete.distanceKm,
                name = "Auto (${nearestConcrete.name})",
                region = "Jarak Terdekat: $dist"
            )
        } else {
            serverList.find { it.id == currentServer.id }?.let {
                currentServer = it
            }
        }

        runOnUiThread {
            binding.tvServerVal.text = currentServer.getDisplayNameWithDistance()
            binding.tvServerLocation.text = currentServer.region
            val locTag = if (isGps) "GPS Akurat" else "IP Geo"
            val detail = buildString {
                if (currentState.asn.isNotBlank() && currentState.asn != "--") append(currentState.asn)
                if (currentState.location.isNotBlank() && currentState.location != "--") {
                    if (isNotEmpty()) append(" • ")
                    append("${currentState.location} ($locTag)")
                }
            }
            if (detail.isNotBlank()) {
                binding.tvIspDetail.text = detail
            }
        }
    }

    private fun loadInitialProviderInfo() {
        providerJob?.cancel()
        // Fast operator display from telephony if cellular
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val carrier = tm?.networkOperatorName
            if (!carrier.isNullOrBlank()) {
                binding.tvIspVal.text = carrier
                currentState = currentState.copy(isp = carrier)
            }
        } catch (_: Exception) {}

        providerJob = lifecycleScope.launch {
            try {
                val providerState = engine.fetchProviderInfo()
                currentState = currentState.copy(
                    ip = providerState.ip,
                    isp = providerState.isp,
                    asn = providerState.asn,
                    location = providerState.location,
                    latitude = currentState.latitude ?: providerState.latitude,
                    longitude = currentState.longitude ?: providerState.longitude
                )

                if (!currentState.isGpsLocation && currentState.latitude != null && currentState.longitude != null) {
                    applyLocationCoordinates(currentState.latitude!!, currentState.longitude!!, isGps = false)
                }

                runOnUiThread {
                    updateProviderUi(currentState)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (binding.tvIspVal.text == "Mendeteksi Provider…" || binding.tvIspVal.text.isBlank()) {
                        binding.tvIspVal.text = "Koneksi Terhubung"
                        binding.tvIspDetail.text = "Koneksi Aktif"
                    }
                }
            }
        }
    }

    private fun showServerSelectionDialog() {
        val serverOptions = serverList.map {
            "${it.getDisplayNameWithDistance()}\n${it.region}"
        }.toTypedArray()

        val currentIndex = serverList.indexOfFirst { it.id == currentServer.id }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_select_server)
            .setSingleChoiceItems(serverOptions, currentIndex) { dialog, which ->
                currentServer = serverList[which]
                binding.tvServerVal.text = currentServer.getDisplayNameWithDistance()
                binding.tvServerLocation.text = currentServer.region
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTest() {
        isRunning = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Show single button in stop state
        binding.layoutCompletedButtons.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.stop_test)
        binding.btnAction.setBackgroundColor(getColor(R.color.border_stroke))
        binding.btnChangeServer.isEnabled = false

        binding.layoutLiveMeter.visibility = View.VISIBLE
        binding.layoutCompletedHero.visibility = View.GONE
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
        engine.cancel()
        testJob?.cancel()
        testJob = null
        onTestFinished()
        binding.layoutLiveMeter.visibility = View.VISIBLE
        binding.layoutCompletedHero.visibility = View.GONE
        binding.layoutCompletedButtons.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.start_test)
        binding.btnAction.setBackgroundColor(getColor(R.color.primary))
        binding.tvPhaseLabel.text = "STOPPED"
        binding.progressBar.progress = 0
    }

    private fun onTestFinished() {
        isRunning = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnChangeServer.isEnabled = true

        if (currentState.stage == TestStage.COMPLETED) {
            binding.btnAction.visibility = View.GONE
            binding.layoutCompletedButtons.visibility = View.VISIBLE
        } else {
            binding.layoutCompletedButtons.visibility = View.GONE
            binding.btnAction.visibility = View.VISIBLE
            binding.btnAction.text = getString(R.string.start_test)
            binding.btnAction.setBackgroundColor(getColor(R.color.primary))
        }
    }

    private fun resetAllMetrics() {
        engine.cancel()
        testJob?.cancel()
        testJob = null
        isRunning = false

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.layoutLiveMeter.visibility = View.VISIBLE
        binding.layoutCompletedHero.visibility = View.GONE

        binding.layoutCompletedButtons.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.text = getString(R.string.start_test)
        binding.btnAction.setBackgroundColor(getColor(R.color.primary))
        binding.btnChangeServer.isEnabled = true

        binding.tvPhaseLabel.text = getString(R.string.status_ready)
        binding.tvLiveSpeed.text = "0.00"
        binding.tvSpeedUnit.text = "Mbps"
        binding.tvPingVal.text = "-- ms"
        binding.tvJitterVal.text = "-- ms"
        binding.tvDownloadVal.text = "-- Mbps"
        binding.tvUploadVal.text = "-- Mbps"
        binding.progressBar.progress = 0
        binding.graphView.clear()

        // Fresh network & provider lookup on reset
        binding.tvIspVal.text = "Mendeteksi Provider…"
        binding.tvIspDetail.text = "Memperbarui info jaringan…"
        binding.tvIpVal.text = "--"

        currentState = currentState.copy(
            stage = TestStage.IDLE,
            pingMs = 0,
            jitterMs = 0,
            downloadMbps = 0.0,
            uploadMbps = 0.0,
            currentSpeedMbps = 0.0,
            progress = 0,
            errorMessage = null,
            ip = "--",
            isp = "--",
            asn = "--"
        )

        updateNetworkBadge()

        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            fetchGpsLocation()
        }

        loadInitialProviderInfo()
    }

    private fun updateProviderUi(state: SpeedState) {
        if (state.ip.isNotBlank() && state.ip != "--") {
            binding.tvIpVal.text = state.ip
        }
        if (state.isp.isNotBlank() && state.isp != "--") {
            binding.tvIspVal.text = state.isp
        }
        val locTag = if (state.isGpsLocation) "GPS Akurat" else "IP Geo"
        val detail = buildString {
            if (state.asn.isNotBlank() && state.asn != "--") append(state.asn)
            if (state.location.isNotBlank() && state.location != "--") {
                if (isNotEmpty()) append(" • ")
                append("${state.location} ($locTag)")
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
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = getString(R.string.status_ready)
                binding.tvLiveSpeed.text = "0.00"
                binding.tvSpeedUnit.text = "Mbps"
            }
            TestStage.INITIALIZING -> {
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = getString(R.string.status_connecting)
                binding.tvLiveSpeed.text = "0.00"
                binding.tvSpeedUnit.text = "Mbps"
            }
            TestStage.PING -> {
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = getString(R.string.status_testing_ping)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%d", state.pingMs)
                binding.tvSpeedUnit.text = "ms"
            }
            TestStage.DOWNLOAD -> {
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = getString(R.string.status_testing_download)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.currentSpeedMbps)
                binding.tvSpeedUnit.text = "Mbps"
                binding.graphView.addPoint(state.currentSpeedMbps.toFloat())
            }
            TestStage.UPLOAD -> {
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = getString(R.string.status_testing_upload)
                binding.tvLiveSpeed.text = String.format(Locale.US, "%.2f", state.currentSpeedMbps)
                binding.tvSpeedUnit.text = "Mbps"
                binding.graphView.addPoint(state.currentSpeedMbps.toFloat())
            }
            TestStage.COMPLETED -> {
                binding.layoutLiveMeter.visibility = View.GONE
                binding.layoutCompletedHero.visibility = View.VISIBLE
                binding.tvPhaseLabel.text = "BENCHMARK COMPLETED"
                binding.tvHeroDownloadVal.text = String.format(Locale.US, "%.1f", state.downloadMbps)
                binding.tvHeroUploadVal.text = String.format(Locale.US, "%.1f", state.uploadMbps)
            }
            TestStage.ERROR -> {
                binding.layoutLiveMeter.visibility = View.VISIBLE
                binding.layoutCompletedHero.visibility = View.GONE
                binding.tvPhaseLabel.text = state.errorMessage ?: "ERROR"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
        testJob?.cancel()
        providerJob?.cancel()
        unregisterNetworkCallback()
        removeLocationUpdates()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
