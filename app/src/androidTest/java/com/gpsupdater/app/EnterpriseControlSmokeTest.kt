package com.gpsupdater.app

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpsupdater.app.enterprise.EnterpriseReportGenerator
import com.gpsupdater.app.enterprise.SimulationOutcome
import com.gpsupdater.app.enterprise.SimulationRunRepository
import com.gpsupdater.app.history.HistoryMode
import com.gpsupdater.app.model.Coordinates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class EnterpriseControlSmokeTest {
    @Test
    fun enterpriseReportRecordsTheRuntimeTimeZoneAndOffset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SimulationRunRepository(context)
        val coordinates = Coordinates(35.6762, 139.6503)
        val run = repository.begin("Tokyo weather", HistoryMode.STATIC, coordinates, null)
        repository.finishActive(SimulationOutcome.COMPLETED, coordinates)

        val report = JSONObject(EnterpriseReportGenerator(context).generate())
        val events = report.getJSONArray("events")
        val event = (0 until events.length())
            .map(events::getJSONObject)
            .first { it.getString("id") == run.id }

        assertEquals(2, report.getInt("schemaVersion"))
        assertTrue(event.getString("timeZoneId").isNotBlank())
        assertNotNull(event.opt("utcOffsetSeconds"))
    }

    @SuppressLint("HardwareIds")
    @Test
    fun deviceIdProvisioningReturnsTheAppScopedAndroidId() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = Settings.Secure.getString(
            instrumentation.targetContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am broadcast --receiver-foreground " +
                    "-n com.gpsupdater.app/.enterprise.EnterpriseControlReceiver " +
                    "-a com.gpsupdater.app.enterprise.GET_DEVICE_ID",
            ),
        ).bufferedReader().use { it.readText() }

        assertTrue(output, output.contains("result=1"))
        assertTrue(output, output.contains("data=\"$expected\""))
    }

    @SuppressLint("HardwareIds")
    @Test
    fun deviceStatusReturnsVersionEntitlementAndReadinessWithoutForegroundingTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expectedDeviceId = Settings.Secure.getString(
            instrumentation.targetContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am broadcast --receiver-foreground " +
                    "-n com.gpsupdater.app/.enterprise.EnterpriseControlReceiver " +
                    "-a com.gpsupdater.app.enterprise.GET_DEVICE_STATUS",
            ),
        ).bufferedReader().use { it.readText() }
        val encoded = Regex("""data="([^"]+)"""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("Device status payload is missing: $output")
        val status = JSONObject(
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8),
        )

        assertEquals(1, status.getInt("schemaVersion"))
        assertEquals(BuildConfig.APPLICATION_ID, status.getString("applicationId"))
        assertEquals(BuildConfig.VERSION_NAME, status.getString("appVersion"))
        assertEquals(BuildConfig.VERSION_CODE, status.getInt("versionCode"))
        assertEquals(BuildConfig.DEBUG, status.getBoolean("debug"))
        assertEquals(expectedDeviceId, status.getString("deviceId"))
        assertTrue(status.getString("tier").isNotBlank())
        assertNotNull(status.opt("mockLocationReady"))
        assertNotNull(status.opt("simulationActive"))
        val enterprise = status.getJSONObject("enterprise")
        assertNotNull(enterprise.opt("organization"))
        assertNotNull(enterprise.opt("expiresAt"))
        assertNotNull(enterprise.opt("active"))
        assertNotNull(enterprise.opt("deviceBound"))
    }
}
