package com.geoalert

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapPickerActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvCoords: TextView
    private lateinit var btnConfirm: Button
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var destinationMarker: Marker? = null
    private var selectedPoint: GeoPoint? = null

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            enableMyLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST init OSMDroid config before setContentView
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = packageName
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        val toolbar: Toolbar = findViewById(R.id.mapToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.map_picker_title)
        toolbar.setNavigationOnClickListener { finish() }

        mapView        = findViewById(R.id.mapView)
        tvCoords       = findViewById(R.id.tvSelectedCoords)
        btnConfirm     = findViewById(R.id.btnConfirmLocation)
        fabMyLocation  = findViewById(R.id.fabMyLocation)

        setupMap()
        setupMyLocation()
        setupTapListener()

        // If coming back with existing coords, center there
        val startLat = intent.getDoubleExtra(EXTRA_START_LAT, Double.NaN)
        val startLon = intent.getDoubleExtra(EXTRA_START_LON, Double.NaN)
        if (!startLat.isNaN() && !startLon.isNaN()) {
            val pt = GeoPoint(startLat, startLon)
            mapView.controller.setCenter(pt)
            mapView.controller.setZoom(13.0)
            placeDestinationMarker(pt)
        }

        btnConfirm.isEnabled = false
        btnConfirm.setOnClickListener {
            selectedPoint?.let { pt ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(RESULT_LAT, pt.latitude)
                    putExtra(RESULT_LON, pt.longitude)
                })
                finish()
            }
        }

        fabMyLocation.setOnClickListener { goToMyLocation() }
    }

    // ── Map setup ─────────────────────────────────────────────────────────────

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 19.0
        mapView.controller.setZoom(5.0)
        mapView.controller.setCenter(GeoPoint(22.5, 78.9)) // India default
    }

    private fun setupMyLocation() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)

        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            enableMyLocation()
        }
    }

    private fun enableMyLocation() {
        myLocationOverlay.enableMyLocation()
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                myLocationOverlay.myLocation?.let { pt ->
                    mapView.controller.animateTo(pt)
                    mapView.controller.setZoom(14.0)
                }
            }
        }
    }

    private fun setupTapListener() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                placeDestinationMarker(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(0, MapEventsOverlay(receiver))
    }

    private fun placeDestinationMarker(point: GeoPoint) {
        selectedPoint = point
        destinationMarker?.let { mapView.overlays.remove(it) }

        destinationMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.destination)
            snippet = getString(R.string.coords_format, point.latitude, point.longitude)
        }
        mapView.overlays.add(destinationMarker)
        mapView.invalidate()

        tvCoords.text = getString(R.string.coords_format, point.latitude, point.longitude)
        btnConfirm.isEnabled = true
    }

    private fun goToMyLocation() {
        val pt = myLocationOverlay.myLocation
        if (pt != null) {
            mapView.controller.animateTo(pt)
            mapView.controller.setZoom(15.0)
        } else {
            Toast.makeText(this, getString(R.string.locating), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        myLocationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }

    companion object {
        const val RESULT_LAT     = "result_lat"
        const val RESULT_LON     = "result_lon"
        const val EXTRA_START_LAT = "start_lat"
        const val EXTRA_START_LON = "start_lon"
    }
}
