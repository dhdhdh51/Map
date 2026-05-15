package com.geoalert

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
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
    private lateinit var btnPickMap: Button
    private lateinit var btnPickRingtone: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRadiusValue: TextView
    private lateinit var sbRadius: SeekBar
    private lateinit var spinnerVibration: Spinner
    private lateinit var tvRingtoneName: TextView
    private lateinit var prefs: SharedPreferences

    private var locationService: LocationService? = null
    private var serviceBound = false
    private var selectedRingtoneUri: String? = null

    // km values mapped to SeekBar positions 0–7
    private val radiusOptions = floatArrayOf(0.5f, 1f, 2f, 3f, 5f, 10f, 15f, 20f)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            locationService = (binder as LocationService.LocalBinder).getService()
            serviceBound = true
            locationService?.setStatusCallback { s -> runOnUiThread { tvStatus.text = s } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requestBackgroundPermission()
            else startTracking()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startTracking() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkLocationPermissionsAndStart() }

    private val ringtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                selectedRingtoneUri = uri.toString()
                prefs.edit().putString(KEY_RINGTONE, selectedRingtoneUri).apply()
                showRingtoneName(uri)
            } else {
                selectedRingtoneUri = null
                prefs.edit().remove(KEY_RINGTONE).apply()
                tvRingtoneName.text = getString(R.string.ringtone_silent)
            }
        }
    }

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val lat = result.data?.getDoubleExtra(MapPickerActivity.RESULT_LAT, Double.NaN) ?: Double.NaN
            val lon = result.data?.getDoubleExtra(MapPickerActivity.RESULT_LON, Double.NaN) ?: Double.NaN
            if (!lat.isNaN() && !lon.isNaN()) {
                etLatitude.setText("%.6f".format(lat))
                etLongitude.setText("%.6f".format(lon))
                prefs.edit().putFloat(KEY_LAT, lat.toFloat()).putFloat(KEY_LON, lon.toFloat()).apply()
                Toast.makeText(this, getString(R.string.location_selected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        etLatitude       = findViewById(R.id.etLatitude)
        etLongitude      = findViewById(R.id.etLongitude)
        btnStart         = findViewById(R.id.btnStart)
        btnStop          = findViewById(R.id.btnStop)
        btnPickMap       = findViewById(R.id.btnPickMap)
        btnPickRingtone  = findViewById(R.id.btnPickRingtone)
        tvStatus         = findViewById(R.id.tvStatus)
        tvRadiusValue    = findViewById(R.id.tvRadiusValue)
        sbRadius         = findViewById(R.id.sbRadius)
        spinnerVibration = findViewById(R.id.spinnerVibration)
        tvRingtoneName   = findViewById(R.id.tvRingtoneName)

        etLatitude.inputType  = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        etLongitude.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED

        setupRadiusBar()
        setupVibrationSpinner()
        restorePrefs()

        btnStart.setOnClickListener        { onStartClicked() }
        btnStop.setOnClickListener         { onStopClicked() }
        btnPickMap.setOnClickListener      { mapPickerLauncher.launch(Intent(this, MapPickerActivity::class.java)) }
        btnPickRingtone.setOnClickListener { openRingtonePicker() }

        setTrackingUi(LocationService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        if (LocationService.isRunning) {
            bindService(Intent(this, LocationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            setTrackingUi(true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    // ── Radius ──────────────────────────────────────────────────────────────

    private fun setupRadiusBar() {
        sbRadius.max = radiusOptions.size - 1
        sbRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                refreshRadiusLabel(p)
                prefs.edit().putInt(KEY_RADIUS_IDX, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun refreshRadiusLabel(idx: Int) {
        val km = radiusOptions[idx]
        tvRadiusValue.text = if (km < 1f) "${(km * 1000).toInt()} m" else "${km.toInt()} km"
    }

    // ── Vibration spinner ────────────────────────────────────────────────────

    private fun setupVibrationSpinner() {
        ArrayAdapter.createFromResource(this, R.array.vibration_patterns, android.R.layout.simple_spinner_item)
            .also { adapter ->
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerVibration.adapter = adapter
            }
    }

    // ── Ringtone ─────────────────────────────────────────────────────────────

    private fun openRingtonePicker() {
        val current = selectedRingtoneUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,         RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,        getString(R.string.pick_ringtone))
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,  true)
        }
        ringtoneLauncher.launch(intent)
    }

    private fun showRingtoneName(uri: Uri) {
        tvRingtoneName.text = RingtoneManager.getRingtone(this, uri)?.getTitle(this)
            ?: getString(R.string.ringtone_default)
    }

    // ── Prefs ────────────────────────────────────────────────────────────────

    private fun restorePrefs() {
        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon = prefs.getFloat(KEY_LON, Float.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
            etLatitude.setText(lat.toString())
            etLongitude.setText(lon.toString())
        }

        val idx = prefs.getInt(KEY_RADIUS_IDX, 3)   // default 3 km
        sbRadius.progress = idx
        refreshRadiusLabel(idx)

        spinnerVibration.setSelection(prefs.getInt(KEY_VIBRATION, 1))

        val uriStr = prefs.getString(KEY_RINGTONE, null)
        if (uriStr != null) {
            selectedRingtoneUri = uriStr
            showRingtoneName(Uri.parse(uriStr))
        } else {
            tvRingtoneName.text = getString(R.string.ringtone_default)
        }
    }

    // ── Start / Stop ─────────────────────────────────────────────────────────

    private fun onStartClicked() {
        val latStr = etLatitude.text.toString().trim()
        val lonStr = etLongitude.text.toString().trim()
        if (latStr.isEmpty() || lonStr.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_coordinates), Toast.LENGTH_SHORT).show(); return
        }
        val lat = latStr.toDoubleOrNull()
        val lon = lonStr.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(this, getString(R.string.invalid_coordinates), Toast.LENGTH_SHORT).show(); return
        }
        prefs.edit()
            .putFloat(KEY_LAT,        lat.toFloat())
            .putFloat(KEY_LON,        lon.toFloat())
            .putInt(KEY_RADIUS_IDX,   sbRadius.progress)
            .putInt(KEY_VIBRATION,    spinnerVibration.selectedItemPosition)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        checkLocationPermissionsAndStart()
    }

    private fun checkLocationPermissionsAndStart() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestBackgroundPermission(); return
        }
        startTracking()
    }

    private fun requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.background_permission_title))
                .setMessage(getString(R.string.background_permission_message))
                .setPositiveButton(getString(R.string.grant))  { _, _ -> backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                .setNegativeButton(getString(R.string.skip))   { _, _ -> startTracking() }
                .show()
        }
    }

    private fun startTracking() {
        if (!isGpsEnabled()) { showGpsDialog(); return }

        val lat     = prefs.getFloat(KEY_LAT, Float.NaN)
        val lon     = prefs.getFloat(KEY_LON, Float.NaN)
        if (lat.isNaN() || lon.isNaN()) { Toast.makeText(this, getString(R.string.enter_coordinates), Toast.LENGTH_SHORT).show(); return }

        val radiusM = radiusOptions[sbRadius.progress] * 1000f
        val vibPat  = spinnerVibration.selectedItemPosition
        val ringUri = selectedRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString()

        val svcIntent = Intent(this, LocationService::class.java).apply {
            putExtra(LocationService.EXTRA_LAT,       lat.toDouble())
            putExtra(LocationService.EXTRA_LON,       lon.toDouble())
            putExtra(LocationService.EXTRA_RADIUS,    radiusM)
            putExtra(LocationService.EXTRA_RINGTONE,  ringUri)
            putExtra(LocationService.EXTRA_VIBRATION, vibPat)
        }
        ContextCompat.startForegroundService(this, svcIntent)
        bindService(Intent(this, LocationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        setTrackingUi(true)
        tvStatus.text = getString(R.string.status_tracking_started)
    }

    private fun onStopClicked() {
        stopService(Intent(this, LocationService::class.java))
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        locationService = null
        setTrackingUi(false)
        tvStatus.text = getString(R.string.status_idle)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showGpsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.gps_disabled_title))
            .setMessage(getString(R.string.gps_disabled_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setTrackingUi(tracking: Boolean) {
        btnStart.isEnabled    = !tracking
        btnStop.isEnabled     =  tracking
        btnPickMap.isEnabled  = !tracking
        btnStart.alpha        = if (tracking)  0.5f else 1.0f
        btnStop.alpha         = if (!tracking) 0.5f else 1.0f
        btnPickMap.alpha      = if (tracking)  0.5f else 1.0f
    }

    companion object {
        const val PREFS_NAME    = "GeoAlertPrefs"
        const val KEY_LAT       = "dest_lat"
        const val KEY_LON       = "dest_lon"
        const val KEY_RADIUS_IDX = "radius_idx"
        const val KEY_RINGTONE  = "ringtone_uri"
        const val KEY_VIBRATION = "vibration_pat"
    }
}
