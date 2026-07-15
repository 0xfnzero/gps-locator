package com.gpsupdater.app.routes

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.ActivityRouteEditorBinding
import com.gpsupdater.app.databinding.DialogWaypointBinding
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.geocoding.MapServiceConfiguration
import com.gpsupdater.app.product.EntitlementRepository
import com.gpsupdater.app.product.ProductFeature
import com.gpsupdater.app.product.ProUpgradeActivity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

class RouteEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRouteEditorBinding
    private val repository by lazy { RouteRepository(this) }
    private val entitlementRepository by lazy { EntitlementRepository(this) }
    private val waypoints = mutableListOf<RouteWaypoint>()
    private val routeOverlays = mutableListOf<Overlay>()
    private var editingRouteId: String? = null
    private var editingCreatedAt = System.currentTimeMillis()
    private var pendingExportContent: String? = null

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Empty document")
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                .orEmpty()
                .ifBlank { getString(R.string.new_route_name) }
            LocationDocumentCodec.import(content, fallbackName)
        }.onSuccess { route ->
            repository.save(route)
            loadRoute(route)
            showMessage(getString(R.string.route_imported))
        }.onFailure {
            showMessage(getString(R.string.route_import_failed))
        }
    }

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val content = pendingExportContent
        pendingExportContent = null
        if (uri == null || content == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Could not open document")
        }.onSuccess {
            showMessage(getString(R.string.route_exported))
        }.onFailure {
            showMessage(getString(R.string.route_export_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        binding = ActivityRouteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()
        configureMap()
        configureControls()
        configureFloatingControls()
        binding.routeNameInput.setText(getString(R.string.new_route_name))
        renderProductAccess()
        updateEditorState()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        renderProductAccess()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(12) + bars.top
            }
            binding.controlPanel.updatePadding(bottom = dp(18) + bars.bottom)
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(318) + bars.bottom
            }
            insets
        }
    }

    private fun configureMap() {
        val initial = MockLocationPreferences.coordinates(this)
        binding.map.apply {
            setTileSource(MapServiceConfiguration.tileSource())
            setMultiTouchControls(true)
            isTilesScaledToDpi = false
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(initial.latitude, initial.longitude))
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                        controller.animateTo(point)
                        return true
                    }

                    override fun longPressHelper(point: GeoPoint): Boolean {
                        controller.animateTo(point)
                        return true
                    }
                }),
            )
        }
        binding.attributionText.text = BuildConfig.MAP_ATTRIBUTION
    }

    private fun configureControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.savedRoutesButton.setOnClickListener { showSavedRoutes() }
        binding.sampleRouteButton.setOnClickListener { loadSampleRoute() }
        binding.addWaypointButton.setOnClickListener { showAddWaypointDialog() }
        binding.undoButton.setOnClickListener {
            if (waypoints.isNotEmpty()) {
                waypoints.removeAt(waypoints.lastIndex)
                updateEditorState()
            }
        }
        binding.speedSlider.addOnChangeListener { _, value, _ ->
            binding.speedText.text = getString(R.string.route_speed, value.toInt())
        }
        binding.saveStartButton.setOnClickListener { saveAndStart() }
    }

    private fun configureFloatingControls() {
        binding.controlPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bottom - top + dp(16)
            }
        }
    }

    private fun showAddWaypointDialog() {
        val waypointBinding = DialogWaypointBinding.inflate(LayoutInflater.from(this))
        waypointBinding.nameInput.setText(
            getString(R.string.waypoint_default_name, waypoints.size + 1),
        )
        waypointBinding.stopInput.setText("0")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_waypoint)
            .setView(waypointBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = waypointBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    waypointBinding.nameLayout.error = getString(R.string.favorite_name_required)
                    return@setOnClickListener
                }
                val stopSeconds = waypointBinding.stopInput.text?.toString()?.toLongOrNull()
                if (stopSeconds == null || stopSeconds !in 0..86_400) {
                    waypointBinding.stopLayout.error = getString(R.string.invalid_stop_duration)
                    return@setOnClickListener
                }
                val center = binding.map.mapCenter
                waypoints += RouteWaypoint(
                    name = name,
                    coordinates = Coordinates(
                        center.latitude,
                        center.longitude,
                    ),
                    stopDurationSeconds = stopSeconds,
                )
                dialog.dismiss()
                updateEditorState()
            }
        }
        dialog.show()
    }

    private fun loadSampleRoute() {
        val center = binding.map.mapCenter.let { Coordinates(it.latitude, it.longitude) }
        val names = listOf(
            getString(R.string.sample_route_start),
            getString(R.string.sample_route_checkpoint_a),
            getString(R.string.sample_route_checkpoint_b),
            getString(R.string.sample_route_finish),
        )
        editingRouteId = null
        editingCreatedAt = System.currentTimeMillis()
        waypoints.clear()
        waypoints.addAll(SampleRouteFactory.createWaypoints(center, names))
        binding.routeNameInput.setText(R.string.sample_route_name)
        binding.speedSlider.value = SAMPLE_ROUTE_SPEED_KPH
        binding.loopSwitch.isChecked = false
        updateEditorState()
        zoomToWaypoints()
        showMessage(getString(R.string.sample_route_loaded))
    }

    private fun saveAndStart() {
        val routeName = binding.routeNameInput.text?.toString()?.trim().orEmpty()
        if (routeName.isBlank()) {
            binding.routeNameLayout.error = getString(R.string.route_name_required)
            return
        }
        val route = runCatching {
            SimulationRoute(
                id = editingRouteId ?: java.util.UUID.randomUUID().toString(),
                name = routeName,
                waypoints = waypoints.toList(),
                speedKph = binding.speedSlider.value.toDouble(),
                loop = binding.loopSwitch.isChecked,
                createdAt = editingCreatedAt,
            )
        }.getOrElse {
            showMessage(getString(R.string.route_requires_points))
            return
        }
        if (!requirePro(ProductFeature.ROUTE_SIMULATION, R.string.route_preview_ready_message)) {
            return
        }
        repository.save(route)
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_ROUTE_ID, route.id),
        )
        finish()
    }

    private fun updateEditorState() {
        binding.waypointCountText.text = resources.getQuantityString(
            R.plurals.waypoint_count,
            waypoints.size,
            waypoints.size,
        )
        binding.speedText.text = getString(R.string.route_speed, binding.speedSlider.value.toInt())
        binding.undoButton.isEnabled = waypoints.isNotEmpty()
        binding.sampleRouteButton.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
        renderRoute()
    }

    private fun renderRoute() {
        binding.map.overlays.removeAll(routeOverlays.toSet())
        routeOverlays.clear()
        if (waypoints.size >= 2) {
            routeOverlays += Polyline(binding.map).apply {
                setPoints(waypoints.map { GeoPoint(it.coordinates.latitude, it.coordinates.longitude) })
                outlinePaint.color = getColor(R.color.forest_700)
                outlinePaint.strokeWidth = dp(5).toFloat()
            }
        }
        waypoints.forEachIndexed { index, waypoint ->
            routeOverlays += Marker(binding.map).apply {
                position = GeoPoint(waypoint.coordinates.latitude, waypoint.coordinates.longitude)
                title = "${index + 1}. ${waypoint.name}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        }
        binding.map.overlays.addAll(routeOverlays)
        binding.map.invalidate()
    }

    private fun showSavedRoutes() {
        val routes = repository.all()
        if (routes.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.saved_routes)
                .setMessage(R.string.saved_routes_empty)
                .setNeutralButton(R.string.import_route) { _, _ -> importRoute() }
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val labels = routes.map { route ->
            val details = resources.getQuantityString(
                R.plurals.route_details,
                route.waypoints.size,
                route.waypoints.size,
                route.speedKph,
            )
            "${route.name}\n$details"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_routes)
            .setItems(labels) { _, index -> showRouteActions(routes[index]) }
            .setNeutralButton(R.string.import_route) { _, _ -> importRoute() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showRouteActions(route: SimulationRoute) {
        val options = arrayOf(
            getString(R.string.start_route),
            getString(R.string.load_route),
            getString(R.string.export_gpx),
            getString(R.string.export_kml),
            getString(R.string.delete_route),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(route.name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        if (requirePro(ProductFeature.ROUTE_SIMULATION)) {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_ROUTE_ID, route.id))
                            finish()
                        }
                    }

                    1 -> loadRoute(route)
                    2 -> exportRoute(route, LocationDocumentFormat.GPX)
                    3 -> exportRoute(route, LocationDocumentFormat.KML)
                    4 -> confirmDeleteRoute(route)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importRoute() {
        if (!requirePro(ProductFeature.FILE_IMPORT_EXPORT)) return
        importDocumentLauncher.launch(
            arrayOf(
                "application/gpx+xml",
                "application/vnd.google-earth.kml+xml",
                "application/xml",
                "text/xml",
                "text/plain",
            ),
        )
    }

    private fun exportRoute(route: SimulationRoute, format: LocationDocumentFormat) {
        if (!requirePro(ProductFeature.FILE_IMPORT_EXPORT)) return
        pendingExportContent = LocationDocumentCodec.export(route, format)
        val extension = if (format == LocationDocumentFormat.GPX) "gpx" else "kml"
        val safeName = route.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "route" }
        exportDocumentLauncher.launch("$safeName.$extension")
    }

    private fun loadRoute(route: SimulationRoute) {
        editingRouteId = route.id
        editingCreatedAt = route.createdAt
        waypoints.clear()
        waypoints.addAll(route.waypoints)
        binding.routeNameInput.setText(route.name)
        binding.speedSlider.value = route.speedKph.toFloat().coerceIn(1f, 160f)
        binding.loopSwitch.isChecked = route.loop
        updateEditorState()
        zoomToWaypoints()
    }

    private fun zoomToWaypoints() {
        val points = waypoints.map { GeoPoint(it.coordinates.latitude, it.coordinates.longitude) }
        if (points.size < 2) return
        binding.map.post {
            binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, dp(64))
        }
    }

    private fun confirmDeleteRoute(route: SimulationRoute) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_route)
            .setMessage(getString(R.string.delete_route_message, route.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.delete(route.id)
                if (editingRouteId == route.id) {
                    editingRouteId = null
                    editingCreatedAt = System.currentTimeMillis()
                }
            }
            .show()
    }

    private fun renderProductAccess() {
        val canRunRoutes = entitlementRepository.canUse(ProductFeature.ROUTE_SIMULATION)
        binding.previewText.visibility = if (canRunRoutes) View.GONE else View.VISIBLE
        binding.saveStartButton.setText(
            if (canRunRoutes) R.string.save_and_start_route
            else R.string.unlock_and_start_route,
        )
    }

    private fun requirePro(
        feature: ProductFeature,
        message: Int = R.string.pro_required_message,
    ): Boolean {
        if (entitlementRepository.canUse(feature)) return true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_required)
            .setMessage(message)
            .setNegativeButton(R.string.continue_editing, null)
            .setPositiveButton(R.string.view_pro) { _, _ ->
                startActivity(Intent(this, ProUpgradeActivity::class.java))
            }
            .show()
        return false
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.controlPanel, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.controlPanel)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ROUTE_ID = "route_id"
        private const val SAMPLE_ROUTE_SPEED_KPH = 24f
    }
}
