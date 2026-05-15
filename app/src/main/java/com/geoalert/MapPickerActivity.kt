package com.geoalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var btnConfirm: Button
    private lateinit var tvCoords: TextView
    private var selected: LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        btnConfirm = findViewById(R.id.btnConfirmLocation)
        tvCoords   = findViewById(R.id.tvSelectedCoords)
        btnConfirm.isEnabled = false

        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment)
            .getMapAsync(this)

        btnConfirm.setOnClickListener {
            selected?.let { pos ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(RESULT_LAT, pos.latitude)
                    putExtra(RESULT_LON, pos.longitude)
                })
                finish()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled  = true
        map.uiSettings.isMyLocationButtonEnabled = false

        // Open on India by default; adjust if user saved coords exist
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(22.5, 78.9), 4f))

        map.setOnMapClickListener { latLng ->
            map.clear()
            map.addMarker(MarkerOptions().position(latLng).title(getString(R.string.destination)))
            selected = latLng
            btnConfirm.isEnabled = true
            tvCoords.text = getString(R.string.coords_format,
                latLng.latitude, latLng.longitude)
        }
    }

    companion object {
        const val RESULT_LAT = "result_lat"
        const val RESULT_LON = "result_lon"
    }
}
