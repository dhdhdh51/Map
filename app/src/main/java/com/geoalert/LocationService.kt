package com.geoalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var statusCallback: ((String) -> Unit)? = null

    private var destLat: Double = 0.0
    private var destLon: Double = 0.0
    private var alarmTriggered = false

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destLat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        destLon = intent?.getDoubleExtra(EXTRA_LON, 0.0) ?: 0.0
        alarmTriggered = false

        isRunning = true
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startLocationUpdates()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        stopAlarm()
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun processLocation(location: Location) {
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, destLat, destLon, results)
        val distanceMeters = results[0]

        if (distanceMeters <= RADIUS_METERS) {
            updateStatus(getString(R.string.status_inside_radius))
            if (!alarmTriggered) {
                alarmTriggered = true
                triggerAlarm()
            }
        } else {
            updateStatus(getString(R.string.status_outside_radius))
            if (alarmTriggered) {
                alarmTriggered = false
                stopAlarm()
            }
        }
    }

    private fun updateStatus(status: String) {
        statusCallback?.invoke(status)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(status))
    }

    private fun triggerAlarm() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    .build()
            )
            setDataSource(applicationContext, alarmUri)
            isLooping = true
            prepare()
            start()
        }

        startVibration()
        sendAlarmNotification()
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 800, 400, 800, 400)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopAlarm() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun sendAlarmNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(getString(R.string.alarm_message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 800, 400, 800))
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun buildForegroundNotification(status: String = getString(R.string.status_tracking_started)): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Geo Alert Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active GPS tracking status"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Geo Alert Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Triggered when inside destination radius"
                enableVibration(true)
                enableLights(true)
            }

            nm.createNotificationChannel(foregroundChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        var isRunning = false

        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"

        private const val FOREGROUND_CHANNEL_ID = "geo_alert_foreground"
        private const val ALERT_CHANNEL_ID = "geo_alert_alarm"
        private const val NOTIFICATION_ID = 1001
        private const val ALARM_NOTIFICATION_ID = 1002
        private const val UPDATE_INTERVAL_MS = 5000L
        private const val RADIUS_METERS = 3000f
    }
}
