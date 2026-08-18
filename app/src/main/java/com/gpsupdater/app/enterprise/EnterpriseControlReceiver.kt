package com.gpsupdater.app.enterprise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gpsupdater.app.location.MockLocationService
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.location.MockLocationAccess
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.product.EntitlementRepository
import com.gpsupdater.app.routes.LocationDocumentCodec
import com.gpsupdater.app.routes.RouteJsonCodec
import com.gpsupdater.app.routes.RouteRepository
import org.json.JSONObject
import java.util.Base64

class EnterpriseControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GET_DEVICE_ID -> {
                accept(EntitlementRepository(context).currentDeviceId())
                return
            }
            ACTION_GET_DEVICE_STATUS -> {
                accept(deviceStatus(context))
                return
            }
        }
        try {
            when (intent.action) {
                ACTION_SET_LOCATION -> setLocation(context, intent)
                ACTION_START_ROUTE -> startRoute(context, intent)
                ACTION_STOP -> {
                    context.startService(MockLocationService.stopIntent(context))
                    accept("stopped")
                }
                ACTION_WRITE_REPORT -> {
                    val report = EnterpriseReportGenerator(context).writeLatest()
                    accept(report.absolutePath)
                }
                else -> reject(RESULT_INVALID_COMMAND, "unsupported_action")
            }
        } catch (error: RuntimeException) {
            reject(RESULT_COMMAND_FAILED, error.javaClass.simpleName)
        }
    }

    private fun setLocation(context: Context, intent: Intent) {
        val coordinates = Coordinates.parse(
            intent.getStringExtra(EXTRA_LATITUDE).orEmpty(),
            intent.getStringExtra(EXTRA_LONGITUDE).orEmpty(),
        ) ?: return reject(RESULT_INVALID_PAYLOAD, "invalid_coordinates")
        val name = decodeName(intent)
            .getOrElse { return reject(RESULT_INVALID_PAYLOAD, "invalid_name") }
        ContextCompat.startForegroundService(
            context,
            MockLocationService.startIntent(
                context,
                coordinates,
                name,
            ),
        )
        accept("started")
    }

    private fun startRoute(context: Context, intent: Intent) {
        val encoded = intent.getStringExtra(EXTRA_ROUTE_DOCUMENT)
            ?: return reject(RESULT_INVALID_PAYLOAD, "missing_route_document")
        val value = runCatching {
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrNull() ?: return reject(RESULT_INVALID_PAYLOAD, "invalid_route_document")
        val fallbackName = decodeName(intent)
            .getOrElse { return reject(RESULT_INVALID_PAYLOAD, "invalid_name") }
            .orEmpty()
            .ifBlank { "ADB route" }
        val route = RouteJsonCodec.decode(value)
            ?: runCatching { LocationDocumentCodec.import(value, fallbackName) }.getOrNull()
            ?: return reject(RESULT_INVALID_PAYLOAD, "unsupported_route_document")
        RouteRepository(context).save(route)
        ContextCompat.startForegroundService(
            context,
            MockLocationService.startRouteIntent(context, route.id),
        )
        accept("started")
    }

    private fun decodeName(intent: Intent): Result<String?> {
        val encoded = intent.getStringExtra(EXTRA_NAME_BASE64)
            ?: return Result.success(intent.getStringExtra(EXTRA_NAME))
        return runCatching {
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
        }
    }

    private fun deviceStatus(context: Context): String {
        val entitlementRepository = EntitlementRepository(context)
        val enterprise = entitlementRepository.enterpriseStatus()
        val payload = JSONObject()
            .put("schemaVersion", DEVICE_STATUS_SCHEMA_VERSION)
            .put("applicationId", BuildConfig.APPLICATION_ID)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("debug", BuildConfig.DEBUG)
            .put("tier", entitlementRepository.currentTier().name.lowercase())
            .put("deviceId", entitlementRepository.currentDeviceId())
            .put("mockLocationReady", MockLocationAccess.isEnabled(context))
            .put("simulationActive", MockLocationPreferences.isActive(context))
            .put(
                "enterprise",
                JSONObject()
                    .put("organization", enterprise?.organization.orEmpty())
                    .put("expiresAt", enterprise?.expiresAt ?: 0L)
                    .put("active", enterprise?.isActive() == true)
                    .put("deviceBound", enterprise?.deviceId != null),
            )
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun accept(data: String) {
        resultCode = RESULT_OK
        resultData = data
    }

    private fun reject(code: Int, data: String) {
        resultCode = code
        resultData = data
    }

    companion object {
        const val ACTION_SET_LOCATION = "com.gpsupdater.app.enterprise.SET_LOCATION"
        const val ACTION_START_ROUTE = "com.gpsupdater.app.enterprise.START_ROUTE"
        const val ACTION_STOP = "com.gpsupdater.app.enterprise.STOP"
        const val ACTION_WRITE_REPORT = "com.gpsupdater.app.enterprise.WRITE_REPORT"
        const val ACTION_GET_DEVICE_ID = "com.gpsupdater.app.enterprise.GET_DEVICE_ID"
        const val ACTION_GET_DEVICE_STATUS = "com.gpsupdater.app.enterprise.GET_DEVICE_STATUS"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_NAME = "name"
        const val EXTRA_NAME_BASE64 = "name_base64"
        const val EXTRA_ROUTE_DOCUMENT = "route_document"
        const val RESULT_OK = 1
        const val RESULT_NOT_LICENSED = 2
        const val RESULT_INVALID_COMMAND = 3
        const val RESULT_INVALID_PAYLOAD = 4
        const val RESULT_COMMAND_FAILED = 5
        private const val DEVICE_STATUS_SCHEMA_VERSION = 1
    }
}
