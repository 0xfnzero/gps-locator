package com.gpsupdater.app

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gpsupdater.app.databinding.ActivityMainBinding
import com.gpsupdater.app.databinding.DialogFavoritesBinding
import com.gpsupdater.app.databinding.DialogJoystickBinding
import com.gpsupdater.app.databinding.DialogCloudSyncBinding
import com.gpsupdater.app.databinding.DialogSaveFavoriteBinding
import com.gpsupdater.app.databinding.ItemFavoriteBinding
import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.geocoding.GeocodingResult
import com.gpsupdater.app.geocoding.NominatimGeocoder
import com.gpsupdater.app.geocoding.RegionSearchDialog
import com.gpsupdater.app.geocoding.MapServiceConfiguration
import com.gpsupdater.app.history.LocationHistoryEntry
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.location.MockLocationAccess
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.location.MockLocationSetupPolicy
import com.gpsupdater.app.location.MockLocationSetupStep
import com.gpsupdater.app.location.MockLocationService
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.product.EntitlementRepository
import com.gpsupdater.app.product.ProductFeature
import com.gpsupdater.app.product.ProductTier
import com.gpsupdater.app.product.ProBillingManager
import com.gpsupdater.app.product.ProUpgradeActivity
import com.gpsupdater.app.privacy.LocalTestDataCleaner
import com.gpsupdater.app.routes.RouteEditorActivity
import com.gpsupdater.app.routes.GeoMath
import com.gpsupdater.app.sync.CloudCipher
import com.gpsupdater.app.sync.CloudSyncClient
import com.gpsupdater.app.sync.CloudSyncConfiguration
import com.gpsupdater.app.sync.CloudSyncSettings
import com.gpsupdater.app.templates.TeamTemplateBundle
import com.gpsupdater.app.templates.TeamTemplateBundleCodec
import com.gpsupdater.app.templates.TeamTemplateBundleRepository
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import java.text.DateFormat
import java.io.ByteArrayOutputStream
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val favoriteRepository by lazy { FavoriteLocationRepository(this) }
    private val geocoder by lazy { NominatimGeocoder() }
    private val entitlementRepository by lazy { EntitlementRepository(this) }
    private val historyRepository by lazy { LocationHistoryRepository(this) }
    private val cloudSyncSettings by lazy { CloudSyncSettings(this) }
    private val teamTemplateRepository by lazy { TeamTemplateBundleRepository(this) }
    private var cloudSyncClient: CloudSyncClient? = null
    private var entitlementSyncManager: ProBillingManager? = null
    private var receiverRegistered = false
    private var pendingPermissionAction: PermissionAction? = null
    private var pendingRouteId: String? = null
    private var selectedPlaceName: String? = null
    private var pendingLocalDataClear = false
    private var pendingTeamTemplateFavoriteIds: Set<String>? = null
    private var pendingTeamTemplateRouteIds: Set<String>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        when (pendingPermissionAction) {
            PermissionAction.START_MOCK -> {
                val coordinates = selectedCoordinates()
                if (hasLocationPermission() && coordinates != null) {
                    startMockService(coordinates)
                } else {
                    showMessage(getString(R.string.location_permission_required))
                }
            }

            PermissionAction.CENTER_ON_DEVICE -> {
                if (hasLocationPermission()) centerOnDeviceLocation()
                else showMessage(getString(R.string.location_permission_required))
            }

            PermissionAction.START_ROUTE -> {
                val routeId = pendingRouteId
                if (hasLocationPermission() && routeId != null) {
                    startRouteService(routeId)
                } else {
                    showMessage(getString(R.string.location_permission_required))
                }
            }

            null -> Unit
        }
        pendingPermissionAction = null
        pendingRouteId = null
    }

    private val routeEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(RouteEditorActivity.EXTRA_ROUTE_ID)
                ?.let(::startSelectedRoute)
        }
    }

    private val enterpriseLicenseLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val document = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Empty license")
        }.getOrNull()
        val result = document?.let(entitlementRepository::activateEnterpriseLicense)
        result?.onSuccess { license ->
            showMessage(getString(R.string.enterprise_license_active, license.organization))
            updateProductEntryPoints()
        }?.onFailure {
            showMessage(getString(R.string.enterprise_license_invalid))
        } ?: showMessage(getString(R.string.enterprise_license_invalid))
    }

    private val teamTemplateImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val document = readDocument(uri, TeamTemplateBundleCodec.MAX_DOCUMENT_BYTES)
            TeamTemplateBundleCodec.decode(document)
        }.onSuccess { bundle ->
            showTeamTemplateImportPreview(bundle)
        }.onFailure {
            showMessage(getString(R.string.team_templates_import_failed))
        }
    }

    private val teamTemplateExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val favoriteIds = pendingTeamTemplateFavoriteIds
        val routeIds = pendingTeamTemplateRouteIds
        pendingTeamTemplateFavoriteIds = null
        pendingTeamTemplateRouteIds = null
        if (uri == null) return@registerForActivityResult
        if (favoriteIds == null || routeIds == null) {
            showMessage(getString(R.string.team_templates_export_failed))
            return@registerForActivityResult
        }
        runCatching {
            val bundle = teamTemplateRepository.export(favoriteIds, routeIds)
            check(bundle.favorites.isNotEmpty() || bundle.routes.isNotEmpty())
            val document = TeamTemplateBundleCodec.encode(bundle)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(document) }
                ?: error("Could not open template output")
        }.onSuccess {
            showMessage(getString(R.string.team_templates_exported))
        }.onFailure {
            showMessage(getString(R.string.team_templates_export_failed))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(MockLocationService.EXTRA_STATUS)) {
                MockLocationService.STATUS_RUNNING -> {
                    updateServiceState(ServiceState.RUNNING)
                    showMessage(
                        if (binding.startButton.text == getString(R.string.update_mock)) {
                            getString(R.string.mock_updated)
                        } else {
                            getString(R.string.mock_started)
                        },
                    )
                }

                MockLocationService.STATUS_STOPPED -> {
                    updateServiceState(ServiceState.STOPPED)
                    if (pendingLocalDataClear) clearLocalTestData()
                }
                MockLocationService.STATUS_ROUTE_RUNNING -> updateServiceState(ServiceState.RUNNING)
                MockLocationService.STATUS_ROUTE_COMPLETED -> {
                    updateServiceState(ServiceState.STOPPED)
                    showMessage(getString(R.string.route_completed))
                }
                MockLocationService.STATUS_ERROR -> {
                    updateServiceState(ServiceState.ERROR)
                    showMessage(
                        intent.getStringExtra(MockLocationService.EXTRA_MESSAGE)
                            ?: getString(R.string.service_failed),
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTeamTemplateFavoriteIds = savedInstanceState
            ?.getStringArrayList(STATE_TEAM_TEMPLATE_FAVORITE_IDS)
            ?.toSet()
        pendingTeamTemplateRouteIds = savedInstanceState
            ?.getStringArrayList(STATE_TEAM_TEMPLATE_ROUTE_IDS)
            ?.toSet()
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE),
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureSystemBars()
        configureMap()
        configureInputs()
        configureActions()
        configureFloatingControls()
        updateServiceState(
            if (MockLocationPreferences.isActive(this)) ServiceState.RUNNING
            else ServiceState.STOPPED,
        )
        updateProductEntryPoints()
        synchronizeProEntitlement()
        updateMockLocationSetup()
        maybeShowSetupGuide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingTeamTemplateFavoriteIds?.let {
            outState.putStringArrayList(STATE_TEAM_TEMPLATE_FAVORITE_IDS, ArrayList(it))
        }
        pendingTeamTemplateRouteIds?.let {
            outState.putStringArrayList(STATE_TEAM_TEMPLATE_ROUTE_IDS, ArrayList(it))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(MockLocationService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        updateServiceState(
            if (MockLocationPreferences.isActive(this)) ServiceState.RUNNING
            else ServiceState.STOPPED,
        )
        updateProductEntryPoints()
        updateMockLocationSetup()
    }

    override fun onPause() {
        selectedCoordinates()?.let { MockLocationPreferences.saveCoordinates(this, it) }
        binding.map.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        entitlementSyncManager?.close()
        entitlementSyncManager = null
        cloudSyncClient?.close()
        geocoder.close()
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun configureMap() {
        val initialCoordinates = MockLocationPreferences.coordinates(this)
        binding.map.apply {
            setTileSource(MapServiceConfiguration.tileSource())
            setMultiTouchControls(true)
            isTilesScaledToDpi = false
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(initialCoordinates.latitude, initialCoordinates.longitude))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    clearSelectedPlaceName()
                    updateInputsFromMap()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    updateInputsFromMap()
                    return false
                }
            })
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
        showCoordinates(initialCoordinates)
        binding.attributionText.text = BuildConfig.MAP_ATTRIBUTION
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(12) + systemBars.top
            }
            binding.controlPanel.updatePadding(bottom = dp(18) + systemBars.bottom)
            binding.myLocationButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(290) + systemBars.bottom
            }
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(294) + systemBars.bottom
            }
            windowInsets
        }
    }

    private fun configureInputs() {
        binding.longitudeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputCoordinates()
                true
            } else {
                false
            }
        }
        binding.latitudeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.longitudeInput.requestFocus()
                true
            } else {
                false
            }
        }
        binding.latitudeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !binding.longitudeInput.hasFocus()) applyInputCoordinates(false)
        }
        binding.longitudeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !binding.latitudeInput.hasFocus()) applyInputCoordinates(false)
        }
    }

    private fun configureActions() {
        binding.startButton.setOnClickListener { startSelectedLocation() }
        binding.stopButton.setOnClickListener {
            startService(MockLocationService.stopIntent(this))
            MockLocationPreferences.setActive(this, false)
            updateServiceState(ServiceState.STOPPED)
            showMessage(getString(R.string.mock_stopped))
        }
        binding.developerSettingsButton.setOnClickListener { showAppSettings() }
        binding.regionSearchButton.setOnClickListener { showRegionSearch() }
        binding.routeButton.setOnClickListener { openRouteEditor() }
        binding.favoritesButton.setOnClickListener { showFavorites() }
        binding.favoriteCurrentButton.setOnClickListener { showSaveFavorite() }
        binding.proButton.setOnClickListener { openProUpgrade() }
        binding.setupMockLocationButton.setOnClickListener { showMockLocationDialog() }
        binding.myLocationButton.setOnClickListener {
            if (hasLocationPermission()) {
                centerOnDeviceLocation()
            } else {
                requestPermissions(PermissionAction.CENTER_ON_DEVICE)
            }
        }
    }

    private fun startSelectedLocation() {
        val coordinates = selectedCoordinates(showError = true) ?: return
        hideKeyboard()
        binding.latitudeInput.clearFocus()
        binding.longitudeInput.clearFocus()

        if (!MockLocationAccess.isEnabled(this)) {
            showMockLocationDialog()
            return
        }
        if (!hasLocationPermission()) {
            requestPermissions(PermissionAction.START_MOCK)
            return
        }
        startMockService(coordinates)
    }

    private fun startMockService(coordinates: Coordinates) {
        updateServiceState(ServiceState.STARTING)
        ContextCompat.startForegroundService(
            this,
            MockLocationService.startIntent(this, coordinates, selectedPlaceName),
        )
    }

    private fun showMockLocationDialog() {
        val step = currentMockLocationSetupStep()
        if (step == MockLocationSetupStep.READY) return
        val title = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.enable_developer_options_title
        } else {
            R.string.mock_permission_title
        }
        val message = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.enable_developer_options_message
        } else {
            R.string.mock_permission_message
        }
        val action = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.open_about_phone
        } else {
            R.string.go_to_settings
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> openDeveloperOptions(step) }
            .show()
    }

    private fun updateMockLocationSetup() {
        when (currentMockLocationSetupStep()) {
            MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS -> {
                binding.setupMockLocationButton.visibility = View.VISIBLE
                binding.setupMockLocationButton.setText(R.string.enable_developer_options)
            }
            MockLocationSetupStep.SELECT_MOCK_LOCATION_APP -> {
                binding.setupMockLocationButton.visibility = View.VISIBLE
                binding.setupMockLocationButton.setText(R.string.select_mock_location_app)
            }
            MockLocationSetupStep.READY -> binding.setupMockLocationButton.visibility = View.GONE
        }
    }

    private fun currentMockLocationSetupStep(): MockLocationSetupStep =
        MockLocationSetupPolicy.resolve(
            developerOptionsEnabled = Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1,
            mockLocationEnabled = MockLocationAccess.isEnabled(this),
        )

    private fun updateProductEntryPoints() {
        binding.proButton.visibility = if (
            entitlementRepository.canUse(ProductFeature.ROUTE_SIMULATION)
        ) View.GONE else View.VISIBLE
    }

    private fun synchronizeProEntitlement() {
        if (BuildConfig.DEBUG || entitlementSyncManager != null) return
        entitlementSyncManager = ProBillingManager(this, entitlementRepository) { state ->
            updateProductEntryPoints()
            if (state.ownershipChecked) {
                binding.root.post {
                    entitlementSyncManager?.close()
                    entitlementSyncManager = null
                }
            }
        }.also { it.connect(loadProductDetails = false) }
    }

    private fun maybeShowSetupGuide() {
        if (BuildConfig.DEBUG || currentMockLocationSetupStep() == MockLocationSetupStep.READY) return
        val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_SETUP_GUIDE_SHOWN, false)) return
        preferences.edit().putBoolean(KEY_SETUP_GUIDE_SHOWN, true).apply()
        binding.root.post {
            if (!isFinishing && currentMockLocationSetupStep() != MockLocationSetupStep.READY) {
                showMockLocationDialog()
            }
        }
    }

    private fun configureFloatingControls() {
        binding.controlPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            binding.myLocationButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(12)
            }
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(16)
            }
        }
    }

    private fun openDeveloperOptions(
        step: MockLocationSetupStep = currentMockLocationSetupStep(),
    ) {
        val action = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            Settings.ACTION_DEVICE_INFO_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        }
        val intent = Intent(action)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showAppSettings() {
        val options = arrayOf(
            getString(R.string.pro_title),
            getString(R.string.routes),
            getString(R.string.history),
            getString(R.string.joystick),
            getString(R.string.cloud_sync),
            getString(R.string.team_templates),
            getString(R.string.language),
            getString(R.string.enterprise_license),
            getString(R.string.privacy_and_data),
            getString(R.string.about_and_support),
            getString(R.string.open_developer_options),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_settings)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> openProUpgrade()
                    1 -> openRouteEditor()
                    2 -> showHistory()
                    3 -> showJoystick()
                    4 -> showCloudSync()
                    5 -> showTeamTemplates()
                    6 -> showLanguageDialog()
                    7 -> showEnterpriseLicense()
                    8 -> showPrivacyAndData()
                    9 -> showAboutAndSupport()
                    10 -> openDeveloperOptions()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showPrivacyAndData() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_and_data)
            .setMessage(R.string.privacy_and_data_summary)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.clear_local_test_data) { _, _ ->
                confirmClearLocalTestData()
            }
        configuredPrivacyPolicyUri()?.let { policyUri ->
            builder.setPositiveButton(R.string.open_privacy_policy) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, policyUri)) }
                    .onFailure { showMessage(getString(R.string.privacy_policy_open_failed)) }
            }
        }
        builder.show()
    }

    private fun confirmClearLocalTestData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_local_test_data_title)
            .setMessage(R.string.clear_local_test_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> requestLocalTestDataClear() }
            .show()
    }

    private fun requestLocalTestDataClear() {
        if (MockLocationPreferences.isActive(this)) {
            pendingLocalDataClear = true
            startService(MockLocationService.stopIntent(this))
        } else {
            clearLocalTestData()
        }
    }

    private fun clearLocalTestData() {
        pendingLocalDataClear = false
        runCatching { LocalTestDataCleaner(this).clear() }
            .onSuccess {
                moveMapTo(Coordinates.DEFAULT, FAVORITE_ZOOM, null)
                updateServiceState(ServiceState.STOPPED)
                showMessage(getString(R.string.local_test_data_cleared))
            }
            .onFailure {
                showMessage(getString(R.string.local_test_data_clear_failed))
            }
    }

    private fun configuredPrivacyPolicyUri(): Uri? {
        val uri = Uri.parse(BuildConfig.PRIVACY_POLICY_URL)
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()
        }
    }

    private fun showAboutAndSupport() {
        val edition = getString(
            when (entitlementRepository.currentTier()) {
                ProductTier.FREE -> R.string.edition_free
                ProductTier.PRO -> R.string.edition_pro
                ProductTier.ENTERPRISE -> R.string.edition_enterprise
            },
        )
        val supportAddress = configuredSupportEmail()
        val message = listOf(
            getString(R.string.about_version, BuildConfig.VERSION_NAME),
            getString(R.string.about_edition, edition),
            if (supportAddress == null) {
                getString(R.string.support_contact_unavailable)
            } else {
                getString(R.string.support_contact, supportAddress)
            },
        ).joinToString("\n\n")
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_and_support)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
        supportAddress?.let { email ->
            builder.setPositiveButton(R.string.contact_support) { _, _ ->
                val mailUri = Uri.Builder()
                    .scheme("mailto")
                    .opaquePart(email)
                    .appendQueryParameter(
                        "subject",
                        getString(R.string.support_email_subject, BuildConfig.VERSION_NAME),
                    )
                    .build()
                runCatching { startActivity(Intent(Intent.ACTION_SENDTO, mailUri)) }
                    .onFailure { showMessage(getString(R.string.support_email_open_failed)) }
            }
        }
        builder.show()
    }

    private fun configuredSupportEmail(): String? = BuildConfig.SUPPORT_EMAIL
        .trim()
        .takeIf { it.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(it).matches() }

    private fun importEnterpriseLicense() {
        enterpriseLicenseLauncher.launch(arrayOf("application/json", "text/plain"))
    }

    private fun showEnterpriseLicense() {
        val status = entitlementRepository.enterpriseStatus()
        val message: String
        val action: Int
        when {
            status == null -> {
                message = getString(R.string.enterprise_license_missing_message)
                action = R.string.import_license
            }

            !status.isActive() -> {
                message = getString(
                    R.string.enterprise_license_status_expired,
                    status.organization,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(status.expiresAt)),
                )
                action = R.string.renew_license
            }

            status.expiresAt == 0L -> {
                message = getString(
                    R.string.enterprise_license_status_perpetual,
                    status.organization,
                )
                action = R.string.replace_license
            }

            else -> {
                message = getString(
                    R.string.enterprise_license_status_active,
                    status.organization,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(status.expiresAt)),
                )
                action = R.string.replace_license
            }
        }
        val deviceBinding = status?.deviceId?.let {
            "\n${getString(R.string.enterprise_license_device_binding, it)}"
        }.orEmpty()
        val currentDeviceId = entitlementRepository.currentDeviceId()
        val currentDevice = if (status?.deviceId == null && currentDeviceId.isNotBlank()) {
            "\n${getString(R.string.enterprise_license_current_device, currentDeviceId)}"
        } else {
            ""
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enterprise_license)
            .setMessage(message + deviceBinding + currentDevice)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(action) { _, _ -> importEnterpriseLicense() }
        if (currentDeviceId.isNotBlank()) {
            builder.setNeutralButton(R.string.copy_device_id) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.device_id), currentDeviceId),
                )
                showMessage(getString(R.string.device_id_copied))
            }
        }
        builder.show()
    }

    private fun showCloudSync() {
        if (!entitlementRepository.canUse(ProductFeature.CLOUD_SYNC)) {
            showProRequired()
            return
        }
        val syncBinding = DialogCloudSyncBinding.inflate(layoutInflater)
        val saved = cloudSyncSettings.load()
        syncBinding.endpointInput.setText(saved.endpoint)
        syncBinding.syncKeyInput.setText(saved.syncKey)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_sync)
            .setView(syncBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        fun configuration(): CloudSyncConfiguration? {
            val endpoint = syncBinding.endpointInput.text?.toString()?.trim().orEmpty()
            val syncKey = syncBinding.syncKeyInput.text?.toString()?.trim().orEmpty()
            syncBinding.endpointLayout.error = null
            syncBinding.syncKeyLayout.error = null
            if (!endpoint.startsWith("https://")) {
                syncBinding.endpointLayout.error = getString(R.string.sync_endpoint_required)
                return null
            }
            if (syncKey.length < 20) {
                syncBinding.syncKeyLayout.error = getString(R.string.sync_key_required)
                return null
            }
            return CloudSyncConfiguration(endpoint.trimEnd('/'), syncKey)
        }

        fun setBusy(busy: Boolean) {
            syncBinding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            syncBinding.backupButton.isEnabled = !busy
            syncBinding.restoreButton.isEnabled = !busy
            syncBinding.generateKeyButton.isEnabled = !busy
            syncBinding.deleteBackupButton.isEnabled = !busy
        }

        fun sync(restore: Boolean) {
            val configuration = configuration() ?: return
            cloudSyncSettings.save(configuration)
            setBusy(true)
            syncBinding.statusText.text = ""
            val client = cloudSyncClient ?: CloudSyncClient(this).also { cloudSyncClient = it }
            val callback: (Result<Unit>) -> Unit = { result ->
                if (dialog.isShowing) {
                    setBusy(false)
                    syncBinding.statusText.text = if (result.isSuccess) {
                        getString(if (restore) R.string.restore_complete else R.string.backup_complete)
                    } else {
                        getString(R.string.cloud_sync_failed)
                    }
                }
            }
            if (restore) client.restore(configuration, callback)
            else client.backup(configuration, callback)
        }

        syncBinding.generateKeyButton.setOnClickListener {
            syncBinding.syncKeyInput.setText(CloudCipher.generateSyncKey())
            syncBinding.syncKeyInput.selectAll()
        }
        syncBinding.backupButton.setOnClickListener { sync(restore = false) }
        syncBinding.restoreButton.setOnClickListener { sync(restore = true) }
        syncBinding.deleteBackupButton.setOnClickListener {
            val configuration = configuration() ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_cloud_backup)
                .setMessage(R.string.delete_cloud_backup_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    cloudSyncSettings.save(configuration)
                    setBusy(true)
                    val client = cloudSyncClient ?: CloudSyncClient(this).also {
                        cloudSyncClient = it
                    }
                    client.deleteBackup(configuration) { result ->
                        if (dialog.isShowing) {
                            setBusy(false)
                            syncBinding.statusText.text = getString(
                                if (result.isSuccess) R.string.cloud_backup_deleted
                                else R.string.cloud_sync_failed,
                            )
                        }
                    }
                }
                .show()
        }
        dialog.show()
    }

    private fun showTeamTemplates() {
        if (!entitlementRepository.canUse(ProductFeature.FILE_IMPORT_EXPORT)) {
            showProRequired()
            return
        }
        val options = arrayOf(
            getString(R.string.import_team_templates),
            getString(R.string.export_team_templates),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.team_templates)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> teamTemplateImportLauncher.launch(arrayOf("application/json", "text/plain"))
                    1 -> showTeamTemplateExportSelection()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showTeamTemplateExportSelection() {
        val available = teamTemplateRepository.export()
        val items = buildList {
            available.favorites.forEach { favorite ->
                add(
                    TeamTemplateSelectionItem(
                        id = favorite.id,
                        type = TeamTemplateItemType.FAVORITE,
                        label = getString(
                            R.string.team_template_location_option,
                            favorite.name,
                            favorite.coordinates.formattedLatitude(),
                            favorite.coordinates.formattedLongitude(),
                        ),
                    ),
                )
            }
            available.routes.forEach { route ->
                val waypointCount = resources.getQuantityString(
                    R.plurals.waypoint_count,
                    route.waypoints.size,
                    route.waypoints.size,
                )
                add(
                    TeamTemplateSelectionItem(
                        id = route.id,
                        type = TeamTemplateItemType.ROUTE,
                        label = getString(
                            R.string.team_template_route_option,
                            route.name,
                            waypointCount,
                        ),
                    ),
                )
            }
        }
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_team_templates)
                .setMessage(R.string.team_templates_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        val checked = BooleanArray(items.size) { true }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_team_templates)
            .setMultiChoiceItems(items.map { it.label }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_team_templates) { _, _ ->
                val selected = items.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    showMessage(getString(R.string.team_template_selection_required))
                    return@setPositiveButton
                }
                pendingTeamTemplateFavoriteIds = selected
                    .filter { it.type == TeamTemplateItemType.FAVORITE }
                    .map { it.id }
                    .toSet()
                pendingTeamTemplateRouteIds = selected
                    .filter { it.type == TeamTemplateItemType.ROUTE }
                    .map { it.id }
                    .toSet()
                teamTemplateExportLauncher.launch(getString(R.string.team_template_file_name))
            }
            .show()
    }

    private fun showTeamTemplateImportPreview(bundle: TeamTemplateBundle) {
        if (bundle.favorites.isEmpty() && bundle.routes.isEmpty()) {
            showMessage(getString(R.string.team_templates_empty))
            return
        }
        val preview = teamTemplateRepository.previewImport(bundle)
        val favoriteCount = resources.getQuantityString(
            R.plurals.team_template_saved_location_count,
            preview.favoriteCount,
            preview.favoriteCount,
        )
        val routeCount = resources.getQuantityString(
            R.plurals.team_template_route_count,
            preview.routeCount,
            preview.routeCount,
        )
        val message = getString(
            R.string.team_templates_import_preview,
            favoriteCount,
            formatTeamTemplateReplacementCount(preview.favoriteReplacementCount),
            routeCount,
            formatTeamTemplateReplacementCount(preview.routeReplacementCount),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_import_team_templates)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_team_templates) { _, _ ->
                teamTemplateRepository.merge(bundle)
                showTeamTemplateImportSuccess(bundle)
            }
            .show()
    }

    private fun formatTeamTemplateReplacementCount(count: Int): String = if (count == 0) {
        getString(R.string.team_template_no_replacements)
    } else {
        resources.getQuantityString(
            R.plurals.team_template_replacement_count,
            count,
            count,
        )
    }

    private fun showTeamTemplateImportSuccess(bundle: TeamTemplateBundle) {
        val savedLocationCount = resources.getQuantityString(
            R.plurals.team_template_saved_location_count,
            bundle.favorites.size,
            bundle.favorites.size,
        )
        val routeCount = resources.getQuantityString(
            R.plurals.team_template_route_count,
            bundle.routes.size,
            bundle.routes.size,
        )
        showMessage(
            getString(
                R.string.team_templates_imported,
                savedLocationCount,
                routeCount,
            ),
        )
    }

    private fun readDocument(uri: Uri, maximumBytes: Int): String {
        val input = contentResolver.openInputStream(uri) ?: error("Could not open document")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= maximumBytes) { "Document is too large" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun openProUpgrade() {
        startActivity(Intent(this, ProUpgradeActivity::class.java))
    }

    private fun showJoystick() {
        if (!entitlementRepository.canUse(ProductFeature.JOYSTICK)) {
            showProRequired()
            return
        }
        val joystickBinding = DialogJoystickBinding.inflate(layoutInflater)
        val steps = intArrayOf(1, 5, 10, 50, 100)
        val labels = steps.map { getString(R.string.distance_meters, it) }
        joystickBinding.stepInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels),
        )
        joystickBinding.stepInput.setText(labels[2], false)

        fun selectedStep(): Double {
            val index = labels.indexOf(joystickBinding.stepInput.text?.toString())
            return steps.getOrElse(index) { steps[2] }.toDouble()
        }

        fun move(bearing: Double) {
            val current = selectedCoordinates(showError = true) ?: return
            val moved = GeoMath.destination(current, selectedStep(), bearing)
            moveMapTo(moved, binding.map.zoomLevelDouble, null)
            joystickBinding.coordinateText.text = getString(
                R.string.coordinates_format,
                moved.formattedLatitude(),
                moved.formattedLongitude(),
            )
            if (MockLocationPreferences.isActive(this)) {
                ContextCompat.startForegroundService(
                    this,
                    MockLocationService.updateIntent(this, moved),
                )
            }
        }

        selectedCoordinates()?.let { coordinates ->
            joystickBinding.coordinateText.text = getString(
                R.string.coordinates_format,
                coordinates.formattedLatitude(),
                coordinates.formattedLongitude(),
            )
        }
        joystickBinding.northButton.setOnClickListener { move(0.0) }
        joystickBinding.eastButton.setOnClickListener { move(90.0) }
        joystickBinding.southButton.setOnClickListener { move(180.0) }
        joystickBinding.westButton.setOnClickListener { move(270.0) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.joystick)
            .setView(joystickBinding.root)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHistory() {
        if (!entitlementRepository.canUse(ProductFeature.LOCATION_HISTORY)) {
            showProRequired()
            return
        }
        val entries = historyRepository.all()
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history)
                .setMessage(R.string.history_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = entries.map { entry ->
            "${entry.name}\n${getString(
                R.string.history_details,
                dateFormat.format(Date(entry.createdAt)),
                entry.coordinates.formattedLatitude(),
                entry.coordinates.formattedLongitude(),
            )}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history)
            .setItems(labels) { _, index -> showHistoryActions(entries[index]) }
            .setNeutralButton(R.string.clear_history) { _, _ -> confirmClearHistory() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHistoryActions(entry: LocationHistoryEntry) {
        val options = arrayOf(
            getString(R.string.use_history),
            getString(R.string.save_history_favorite),
            getString(R.string.delete_history),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        if (entry.routeId != null) {
                            startSelectedRoute(entry.routeId)
                        } else {
                            moveMapTo(entry.coordinates, FAVORITE_ZOOM, entry.name)
                            binding.root.post { startSelectedLocation() }
                        }
                    }

                    1 -> {
                        favoriteRepository.save(entry.name, getString(R.string.history), entry.coordinates)
                        showMessage(getString(R.string.favorite_saved, entry.name))
                    }

                    2 -> {
                        historyRepository.delete(entry.id)
                        showMessage(getString(R.string.favorite_deleted))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> historyRepository.clear() }
            .show()
    }

    private fun openRouteEditor() {
        routeEditorLauncher.launch(Intent(this, RouteEditorActivity::class.java))
    }

    private fun startSelectedRoute(routeId: String) {
        if (!MockLocationAccess.isEnabled(this)) {
            showMockLocationDialog()
            return
        }
        if (!hasLocationPermission()) {
            pendingRouteId = routeId
            requestPermissions(PermissionAction.START_ROUTE)
            return
        }
        startRouteService(routeId)
    }

    private fun startRouteService(routeId: String) {
        updateServiceState(ServiceState.STARTING)
        ContextCompat.startForegroundService(
            this,
            MockLocationService.startRouteIntent(this, routeId),
        )
    }

    private fun showProRequired() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_required)
            .setMessage(R.string.pro_required_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.view_pro) { _, _ -> openProUpgrade() }
            .show()
    }

    private fun showLanguageDialog() {
        val languages = listOf(
            "" to R.string.follow_system,
            "zh-CN" to R.string.simplified_chinese,
            "en" to R.string.english,
            "ja" to R.string.japanese,
            "es" to R.string.spanish,
        )
        val options = languages.map { getString(it.second) }.toTypedArray()
        val currentLanguage = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .lowercase()
        val selectedIndex = languages.indexOfFirst { (tag, _) ->
            tag.isNotEmpty() && currentLanguage.startsWith(tag.substringBefore('-').lowercase())
        }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(options, selectedIndex) { dialog, index ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languages[index].first),
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestPermissions(action: PermissionAction) {
        pendingPermissionAction = action
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun centerOnDeviceLocation() {
        val locationManager = getSystemService(LocationManager::class.java)
        val location = locationManager.getProviders(true)
            .asSequence()
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filterNot { it.isFromMockProvider }
            .maxByOrNull { it.time }

        if (location == null) {
            showMessage(getString(R.string.current_location_unavailable))
            return
        }
        val coordinates = Coordinates(location.latitude, location.longitude)
        moveMapTo(coordinates, binding.map.zoomLevelDouble, null)
    }

    private fun applyInputCoordinates(showError: Boolean = true): Boolean {
        val coordinates = selectedCoordinates(showError) ?: return false
        moveMapTo(coordinates, binding.map.zoomLevelDouble, null)
        MockLocationPreferences.saveCoordinates(this, coordinates)
        binding.latitudeLayout.error = null
        binding.longitudeLayout.error = null
        hideKeyboard()
        return true
    }

    private fun selectedCoordinates(showError: Boolean = false): Coordinates? {
        val coordinates = Coordinates.parse(
            binding.latitudeInput.text?.toString().orEmpty(),
            binding.longitudeInput.text?.toString().orEmpty(),
        )
        if (coordinates == null && showError) {
            binding.latitudeLayout.error = getString(R.string.invalid_coordinates)
            binding.longitudeLayout.error = " "
        }
        return coordinates
    }

    private fun updateInputsFromMap() {
        if (binding.latitudeInput.hasFocus() || binding.longitudeInput.hasFocus()) return
        val center = binding.map.mapCenter
        showCoordinates(Coordinates(center.latitude, center.longitude))
    }

    private fun showCoordinates(coordinates: Coordinates) {
        binding.latitudeInput.setText(coordinates.formattedLatitude())
        binding.longitudeInput.setText(coordinates.formattedLongitude())
        binding.latitudeLayout.error = null
        binding.longitudeLayout.error = null
    }

    private fun showRegionSearch() {
        RegionSearchDialog(this, geocoder) { result -> selectGeocodingResult(result) }.show()
    }

    private fun selectGeocodingResult(result: GeocodingResult) {
        moveMapTo(result.coordinates, result.zoomLevel, result.name)
        showMessage(getString(R.string.selected_region, result.name))
    }

    private fun moveMapTo(coordinates: Coordinates, zoom: Double, placeName: String?) {
        showCoordinates(coordinates)
        binding.map.controller.setZoom(zoom.coerceIn(binding.map.minZoomLevel, binding.map.maxZoomLevel))
        binding.map.controller.animateTo(GeoPoint(coordinates.latitude, coordinates.longitude))
        setSelectedPlaceName(placeName)
        if (placeName != null) {
            binding.root.postDelayed({ setSelectedPlaceName(placeName) }, MAP_ANIMATION_MS)
        }
    }

    private fun setSelectedPlaceName(name: String?) {
        selectedPlaceName = name
        binding.screenSubtitle.text = name ?: getString(R.string.screen_subtitle)
    }

    private fun clearSelectedPlaceName() {
        if (selectedPlaceName != null) setSelectedPlaceName(null)
    }

    private fun showSaveFavorite() {
        val coordinates = selectedCoordinates(showError = true) ?: return
        val existingFavorite = favoriteRepository.all().any { it.coordinates == coordinates }
        if (!existingFavorite && favoriteRepository.all().size >= entitlementRepository.favoriteLimit()) {
            showMessage(getString(R.string.favorite_limit_reached))
            showProRequired()
            return
        }
        val favoriteBinding = DialogSaveFavoriteBinding.inflate(layoutInflater)
        favoriteBinding.groupLayout.visibility = if (
            entitlementRepository.canUse(ProductFeature.FAVORITE_GROUPS)
        ) View.VISIBLE else View.GONE
        val fallbackName = getString(
            R.string.coordinate_favorite_name,
            coordinates.formattedLatitude(),
            coordinates.formattedLongitude(),
        )
        favoriteBinding.nameInput.setText(selectedPlaceName ?: fallbackName)
        favoriteBinding.nameInput.selectAll()
        favoriteBinding.coordinateText.text = getString(
            R.string.coordinates_format,
            coordinates.formattedLatitude(),
            coordinates.formattedLongitude(),
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_current)
            .setView(favoriteBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = favoriteBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    favoriteBinding.nameLayout.error = getString(R.string.favorite_name_required)
                    return@setOnClickListener
                }
                favoriteRepository.save(
                    name,
                    favoriteBinding.groupInput.text?.toString().orEmpty(),
                    coordinates,
                )
                setSelectedPlaceName(name)
                dialog.dismiss()
                showMessage(getString(R.string.favorite_saved, name))
            }
        }
        dialog.show()
    }

    private fun showFavorites() {
        val favoritesBinding = DialogFavoritesBinding.inflate(layoutInflater)
        favoritesBinding.searchLayout.visibility = if (
            entitlementRepository.canUse(ProductFeature.FAVORITE_GROUPS)
        ) View.VISIBLE else View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorites)
            .setView(favoritesBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        fun renderFavorites() {
            val query = favoritesBinding.searchInput.text?.toString()?.trim().orEmpty()
            val favorites = favoriteRepository.all().filter { favorite ->
                query.isBlank() || favorite.name.contains(query, ignoreCase = true) ||
                    favorite.group.contains(query, ignoreCase = true) ||
                    favorite.coordinates.formattedLatitude().contains(query) ||
                    favorite.coordinates.formattedLongitude().contains(query)
            }
            favoritesBinding.emptyText.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
            favoritesBinding.favoritesScroll.visibility =
                if (favorites.isEmpty()) View.GONE else View.VISIBLE
            favoritesBinding.favoritesContainer.removeAllViews()
            favorites.forEachIndexed { index, favorite ->
                val itemBinding = ItemFavoriteBinding.inflate(
                    layoutInflater,
                    favoritesBinding.favoritesContainer,
                    false,
                )
                itemBinding.nameText.text = favorite.name
                itemBinding.groupText.text = favorite.group
                itemBinding.groupText.visibility =
                    if (favorite.group.isBlank()) View.GONE else View.VISIBLE
                itemBinding.coordinateText.text = getString(
                    R.string.coordinates_format,
                    favorite.coordinates.formattedLatitude(),
                    favorite.coordinates.formattedLongitude(),
                )
                itemBinding.useButton.contentDescription =
                    getString(R.string.use_favorite, favorite.name)
                itemBinding.deleteButton.contentDescription =
                    getString(R.string.delete_favorite, favorite.name)
                itemBinding.root.setOnClickListener {
                    useFavorite(favorite, startImmediately = false)
                    dialog.dismiss()
                }
                itemBinding.useButton.setOnClickListener {
                    useFavorite(favorite, startImmediately = true)
                    dialog.dismiss()
                }
                itemBinding.deleteButton.setOnClickListener {
                    confirmDeleteFavorite(favorite) { renderFavorites() }
                }
                favoritesBinding.favoritesContainer.addView(itemBinding.root)
                if (index < favorites.lastIndex) {
                    favoritesBinding.favoritesContainer.addView(View(this).apply {
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            marginStart = dp(12)
                            marginEnd = dp(12)
                        }
                    })
                }
            }
        }

        renderFavorites()
        favoritesBinding.searchInput.doAfterTextChanged { renderFavorites() }
        dialog.show()
    }

    private fun useFavorite(favorite: FavoriteLocation, startImmediately: Boolean) {
        moveMapTo(favorite.coordinates, FAVORITE_ZOOM, favorite.name)
        if (startImmediately) binding.root.post { startSelectedLocation() }
    }

    private fun confirmDeleteFavorite(favorite: FavoriteLocation, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_favorite_title)
            .setMessage(getString(R.string.delete_favorite_message, favorite.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                favoriteRepository.delete(favorite.id)
                onDeleted()
                showMessage(getString(R.string.favorite_deleted))
            }
            .show()
    }

    private fun updateServiceState(state: ServiceState) {
        when (state) {
            ServiceState.STOPPED -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_stopped)
                binding.statusText.setText(R.string.status_stopped)
                binding.startButton.setText(R.string.start_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = false
            }

            ServiceState.STARTING -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_stopped)
                binding.statusText.setText(R.string.status_starting)
                binding.startButton.isEnabled = false
                binding.stopButton.isEnabled = true
            }

            ServiceState.RUNNING -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_running)
                binding.statusText.setText(R.string.status_running)
                binding.startButton.setText(R.string.update_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = true
            }

            ServiceState.ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_error)
                binding.statusText.setText(R.string.status_error)
                binding.startButton.setText(R.string.start_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = false
            }
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.controlPanel, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.controlPanel)
            .show()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class PermissionAction {
        START_MOCK,
        CENTER_ON_DEVICE,
        START_ROUTE,
    }

    private enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR,
    }

    private data class TeamTemplateSelectionItem(
        val id: String,
        val type: TeamTemplateItemType,
        val label: String,
    )

    private enum class TeamTemplateItemType {
        FAVORITE,
        ROUTE,
    }

    companion object {
        private const val MAP_ANIMATION_MS = 600L
        private const val FAVORITE_ZOOM = 16.0
        private const val ONBOARDING_PREFERENCES = "onboarding"
        private const val KEY_SETUP_GUIDE_SHOWN = "mock_location_setup_guide_shown"
        private const val STATE_TEAM_TEMPLATE_FAVORITE_IDS = "team_template_favorite_ids"
        private const val STATE_TEAM_TEMPLATE_ROUTE_IDS = "team_template_route_ids"
    }
}
