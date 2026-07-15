package com.gpsupdater.app.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.gpsupdater.app.MainActivity
import com.gpsupdater.app.R
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.enterprise.EnterpriseReportGenerator
import com.gpsupdater.app.enterprise.SimulationOutcome
import com.gpsupdater.app.enterprise.SimulationRunRepository
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RoutePosition
import com.gpsupdater.app.routes.RouteRepository
import com.gpsupdater.app.routes.RouteSimulator
import com.gpsupdater.app.routes.SimulationRoute

class MockLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private var coordinates = Coordinates.DEFAULT
    private var activeRoute: SimulationRoute? = null
    private var routeSimulator: RouteSimulator? = null
    private var lastRouteUpdateNanos = 0L
    private var lastNotificationProgress = -1
    private var providersPrepared = false
    private var cleanupCompleted = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationUpdate = object : Runnable {
        override fun run() {
            try {
                if (updateLocation()) handler.postDelayed(this, UPDATE_INTERVAL_MS)
            } catch (_: SecurityException) {
                reportError(getString(R.string.service_failed))
                stopSelf()
            } catch (_: IllegalArgumentException) {
                reportError(getString(R.string.service_failed))
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMocking()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_ROUTE) {
            val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
            val route = routeId?.let { RouteRepository(this).find(it) }
            if (route == null) {
                reportError(getString(R.string.route_not_found))
                stopSelf()
                return START_NOT_STICKY
            }
            startRouteMocking(route, recordHistory = true)
        } else if (intent?.action == ACTION_START) {
            val requestedCoordinates = coordinatesFrom(intent)
            if (requestedCoordinates == null) {
                reportError(getString(R.string.invalid_coordinates))
                stopSelf()
                return START_NOT_STICKY
            }
            startMocking(
                requestedCoordinates,
                intent.getStringExtra(EXTRA_LABEL),
                recordHistory = intent.getBooleanExtra(EXTRA_RECORD_HISTORY, true),
            )
        } else if (MockLocationPreferences.isActive(this)) {
            val route = MockLocationPreferences.activeRouteId(this)
                ?.let { RouteRepository(this).find(it) }
            if (route != null) startRouteMocking(route, recordHistory = false)
            else startMocking(
                MockLocationPreferences.coordinates(this),
                label = null,
                recordHistory = false,
            )
        } else {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    private fun startMocking(
        newCoordinates: Coordinates,
        label: String?,
        recordHistory: Boolean,
    ) {
        cleanupCompleted = false
        val replacedRoute = activeRoute != null
        activeRoute = null
        routeSimulator = null
        lastRouteUpdateNanos = 0L
        coordinates = newCoordinates
        MockLocationPreferences.saveCoordinates(this, coordinates)
        startForegroundCompat(buildNotification(coordinates, starting = true))

        try {
            if (!providersPrepared) prepareProviders()
            acquireWakeLock()
            handler.removeCallbacks(locationUpdate)
            injectLocation(coordinates)
            handler.postDelayed(locationUpdate, UPDATE_INTERVAL_MS)
            MockLocationPreferences.setActive(this, true)
            if (recordHistory) {
                LocationHistoryRepository(this).record(
                    label.orEmpty(),
                    coordinates,
                    HistoryMode.STATIC,
                )
                SimulationRunRepository(this).begin(
                    label.orEmpty(),
                    HistoryMode.STATIC,
                    coordinates,
                    routeId = null,
                )
            } else if (replacedRoute) {
                SimulationRunRepository(this).finishActive(
                    SimulationOutcome.STOPPED,
                    coordinates,
                )
                SimulationRunRepository(this).begin(
                    "Joystick",
                    HistoryMode.STATIC,
                    coordinates,
                    routeId = null,
                )
            }
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(coordinates, starting = false),
            )
            broadcastStatus(STATUS_RUNNING)
        } catch (_: SecurityException) {
            reportError(getString(R.string.service_failed))
            stopSelf()
        } catch (_: IllegalArgumentException) {
            reportError(getString(R.string.service_failed))
            stopSelf()
        }
    }

    private fun startRouteMocking(route: SimulationRoute, recordHistory: Boolean) {
        cleanupCompleted = false
        activeRoute = route
        routeSimulator = RouteSimulator(route)
        val initialPosition = routeSimulator?.current() ?: return
        coordinates = initialPosition.coordinates
        lastRouteUpdateNanos = SystemClock.elapsedRealtimeNanos()
        lastNotificationProgress = -1
        MockLocationPreferences.saveCoordinates(this, coordinates)
        startForegroundCompat(buildNotification(coordinates, starting = true, route = route))

        try {
            if (!providersPrepared) prepareProviders()
            acquireWakeLock()
            handler.removeCallbacks(locationUpdate)
            injectLocation(initialPosition)
            handler.postDelayed(locationUpdate, UPDATE_INTERVAL_MS)
            MockLocationPreferences.setRouteActive(this, route.id)
            if (recordHistory) {
                LocationHistoryRepository(this).record(
                    route.name,
                    coordinates,
                    HistoryMode.ROUTE,
                    route.id,
                )
                SimulationRunRepository(this).begin(
                    route.name,
                    HistoryMode.ROUTE,
                    coordinates,
                    route.id,
                )
            }
            updateRouteNotification(initialPosition)
            broadcastStatus(STATUS_ROUTE_RUNNING, routeId = route.id, progress = 0.0)
        } catch (_: SecurityException) {
            reportError(getString(R.string.service_failed))
            stopSelf()
        } catch (_: IllegalArgumentException) {
            reportError(getString(R.string.service_failed))
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareProviders() {
        prepareProvider(LocationManager.GPS_PROVIDER, requiresSatellite = true)
        runCatching {
            prepareProvider(LocationManager.NETWORK_PROVIDER, requiresNetwork = true)
        }
        providersPrepared = true
    }

    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    private fun prepareProvider(
        provider: String,
        requiresNetwork: Boolean = false,
        requiresSatellite: Boolean = false,
    ) {
        runCatching { locationManager.removeTestProvider(provider) }
        locationManager.addTestProvider(
            provider,
            requiresNetwork,
            requiresSatellite,
            false,
            false,
            true,
            true,
            true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE,
        )
        locationManager.setTestProviderEnabled(provider, true)
    }

    private fun updateLocation(): Boolean {
        val simulator = routeSimulator
        if (simulator == null) {
            injectLocation(coordinates)
            return true
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val deltaSeconds = (nowNanos - lastRouteUpdateNanos).coerceAtLeast(0L) / 1_000_000_000.0
        lastRouteUpdateNanos = nowNanos
        val position = simulator.advance(deltaSeconds)
        coordinates = position.coordinates
        MockLocationPreferences.saveCoordinates(this, coordinates)
        injectLocation(position)
        val route = activeRoute
        broadcastStatus(
            STATUS_ROUTE_RUNNING,
            routeId = route?.id,
            progress = position.progress,
        )
        updateRouteNotification(position)
        if (position.isComplete) {
            SimulationRunRepository(this).finishActive(
                SimulationOutcome.COMPLETED,
                coordinates,
            )
            MockLocationPreferences.setActive(this, false)
            broadcastStatus(STATUS_ROUTE_COMPLETED, routeId = route?.id, progress = 1.0)
            stopSelf()
            return false
        }
        return true
    }

    private fun injectLocation(position: RoutePosition) {
        injectLocation(
            position.coordinates,
            position.speedMetersPerSecond,
            position.bearingDegrees,
        )
    }

    private fun injectLocation(
        coordinates: Coordinates,
        speedMetersPerSecond: Float = 0f,
        bearingDegrees: Float = 0f,
    ) {
        val now = System.currentTimeMillis()
        val elapsedRealtime = SystemClock.elapsedRealtimeNanos()
        setProviderLocation(
            LocationManager.GPS_PROVIDER,
            coordinates,
            now,
            elapsedRealtime,
            speedMetersPerSecond,
            bearingDegrees,
        )
        runCatching {
            setProviderLocation(
                LocationManager.NETWORK_PROVIDER,
                coordinates,
                now,
                elapsedRealtime,
                speedMetersPerSecond,
                bearingDegrees,
            )
        }
    }

    private fun setProviderLocation(
        provider: String,
        coordinates: Coordinates,
        time: Long,
        elapsedRealtimeNanos: Long,
        speedMetersPerSecond: Float,
        bearingDegrees: Float,
    ) {
        val location = Location(provider).apply {
            latitude = coordinates.latitude
            longitude = coordinates.longitude
            altitude = 0.0
            accuracy = LOCATION_ACCURACY_METERS
            verticalAccuracyMeters = LOCATION_ACCURACY_METERS
            speed = speedMetersPerSecond
            speedAccuracyMetersPerSecond = 0.1f
            bearing = bearingDegrees
            bearingAccuracyDegrees = 1f
            this.time = time
            this.elapsedRealtimeNanos = elapsedRealtimeNanos
            extras = Bundle()
        }
        locationManager.setTestProviderLocation(provider, location)
    }

    private fun stopMocking() {
        if (cleanupCompleted) return
        cleanupCompleted = true
        handler.removeCallbacks(locationUpdate)
        activeRoute = null
        routeSimulator = null
        lastRouteUpdateNanos = 0L
        if (providersPrepared) {
            runCatching { locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) }
            runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            runCatching { locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false) }
            runCatching { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER) }
            providersPrepared = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val wasActive = MockLocationPreferences.isActive(this)
        MockLocationPreferences.setActive(this, false)
        if (wasActive) {
            SimulationRunRepository(this).finishActive(
                SimulationOutcome.STOPPED,
                coordinates,
            )
        }
        runCatching { EnterpriseReportGenerator(this).writeLatest() }
        if (wasActive) broadcastStatus(STATUS_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:mock-location",
        ).apply { acquire() }
    }

    private fun coordinatesFrom(intent: Intent): Coordinates? = runCatching {
        Coordinates(
            intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
            intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
        )
    }.getOrNull()

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_title)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(
        coordinates: Coordinates,
        starting: Boolean,
        route: SimulationRoute? = null,
        routeProgress: Int = 0,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = if (starting) {
            getString(R.string.notification_starting)
        } else if (route != null) {
            getString(R.string.notification_route_progress, route.name, routeProgress)
        } else {
            "${coordinates.formattedLatitude()}, ${coordinates.formattedLongitude()}"
        }
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_stop),
                stopIntent,
            )
        if (route != null) builder.setProgress(100, routeProgress, starting)
        return builder.build()
    }

    private fun updateRouteNotification(position: RoutePosition) {
        val route = activeRoute ?: return
        val progress = (position.progress * 100).toInt().coerceIn(0, 100)
        if (progress == lastNotificationProgress) return
        lastNotificationProgress = progress
        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification(
                position.coordinates,
                starting = false,
                route = route,
                routeProgress = progress,
            ),
        )
    }

    private fun reportError(message: String) {
        SimulationRunRepository(this).finishActive(SimulationOutcome.FAILED, coordinates)
        MockLocationPreferences.setActive(this, false)
        broadcastStatus(STATUS_ERROR, message)
    }

    private fun broadcastStatus(
        status: String,
        message: String? = null,
        routeId: String? = null,
        progress: Double? = null,
    ) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
        message?.let { intent.putExtra(EXTRA_MESSAGE, it) }
        routeId?.let { intent.putExtra(EXTRA_ROUTE_ID, it) }
        progress?.let { intent.putExtra(EXTRA_ROUTE_PROGRESS, it) }
        sendBroadcast(intent)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_STATUS = "com.gpsupdater.app.action.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        const val STATUS_ROUTE_RUNNING = "route_running"
        const val STATUS_ROUTE_COMPLETED = "route_completed"
        const val EXTRA_ROUTE_PROGRESS = "route_progress"

        private const val ACTION_START = "com.gpsupdater.app.action.START"
        private const val ACTION_STOP = "com.gpsupdater.app.action.STOP"
        private const val ACTION_START_ROUTE = "com.gpsupdater.app.action.START_ROUTE"
        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_RECORD_HISTORY = "record_history"
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val NOTIFICATION_CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val LOCATION_ACCURACY_METERS = 3f

        fun startIntent(
            context: Context,
            coordinates: Coordinates,
            label: String? = null,
        ): Intent =
            Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LATITUDE, coordinates.latitude)
                .putExtra(EXTRA_LONGITUDE, coordinates.longitude)
                .putExtra(EXTRA_LABEL, label)

        fun stopIntent(context: Context): Intent =
            Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)

        fun updateIntent(context: Context, coordinates: Coordinates): Intent =
            startIntent(context, coordinates).putExtra(EXTRA_RECORD_HISTORY, false)

        fun startRouteIntent(context: Context, routeId: String): Intent =
            Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START_ROUTE)
                .putExtra(EXTRA_ROUTE_ID, routeId)
    }
}
