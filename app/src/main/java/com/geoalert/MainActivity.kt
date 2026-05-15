package com.geoalert

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etLatitude: EditText
    private lateinit var etLongitude: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences

    private var locationService: LocationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as LocationService.LocalBinder
            locationService = localBinder.getService()
            serviceBound = true
            locationService?.setStatusCallback { status -> runOnUiThread { tvStatus.text = status } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermission()
            } else {
                startTracking()
            }
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startTracking()
        } else {
            Toast.makeText(this, getString(R.string.background_permission_info), Toast.LENGTH_LONG).show()
            startTracking()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        checkLocationPermissionsAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        etLatitude = findViewById(R.id.etLatitude)
        etLongitude = findViewById(R.id.etLongitude)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        etLatitude.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        etLongitude.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED

        loadSavedLocation()

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener { onStopClicked() }

        updateButtonStates(false)
    }

    override fun onStart() {
        super.onStart()
        if (LocationService.isRunning) {
            val intent = Intent(this, LocationService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            updateButtonStates(true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun onStartClicked() {
        val latText = etLatitude.text.toString().trim()
        val lonText = etLongitude.text.toString().trim()

        if (latText.isEmpty() || lonText.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_coordinates), Toast.LENGTH_SHORT).show()
            return
        }

        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()

        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            Toast.makeText(this, getString(R.string.invalid_coordinates), Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putFloat(KEY_LAT, lat.toFloat())
            .putFloat(KEY_LON, lon.toFloat())
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkLocationPermissionsAndStart()
    }

    private fun checkLocationPermissionsAndStart() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundGranted) {
                requestBackgroundPermission()
                return
            }
        }

        startTracking()
    }

    private fun requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.background_permission_title))
                .setMessage(getString(R.string.background_permission_message))
                .setPositiveButton(getString(R.string.grant)) { _, _ ->
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(getString(R.string.skip)) { _, _ -> startTracking() }
                .show()
        }
    }

    private fun startTracking() {
        if (!isGpsEnabled()) {
            showGpsDialog()
            return
        }

        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_LON, Float.NaN)

        if (lat.isNaN() || lon.isNaN()) {
            Toast.makeText(this, getString(R.string.enter_coordinates), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, LocationService::class.java).apply {
            putExtra(LocationService.EXTRA_LAT, lat.toDouble())
            putExtra(LocationService.EXTRA_LON, lon.toDouble())
        }

        ContextCompat.startForegroundService(this, intent)

        val bindIntent = Intent(this, LocationService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        updateButtonStates(true)
        tvStatus.text = getString(R.string.status_tracking_started)
    }

    private fun onStopClicked() {
        val intent = Intent(this, LocationService::class.java)
        stopService(intent)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        locationService = null
        updateButtonStates(false)
        tvStatus.text = getString(R.string.status_idle)
    }

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showGpsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.gps_disabled_title))
            .setMessage(getString(R.string.gps_disabled_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun loadSavedLocation() {
        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_LON, Float.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
            etLatitude.setText(lat.toString())
            etLongitude.setText(lon.toString())
        }
    }

    private fun updateButtonStates(tracking: Boolean) {
        btnStart.isEnabled = !tracking
        btnStop.isEnabled = tracking
        btnStart.alpha = if (tracking) 0.5f else 1.0f
        btnStop.alpha = if (!tracking) 0.5f else 1.0f
    }

    companion object {
        const val PREFS_NAME = "GeoAlertPrefs"
        const val KEY_LAT = "dest_lat"
        const val KEY_LON = "dest_lon"
    }
}
