import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun serviceUri(value: String): URI? = runCatching {
    URI(
        value
            .replace("{z}", "0")
            .replace("{x}", "0")
            .replace("{y}", "0"),
    )
}.getOrNull()

fun serviceHost(value: String): String? = serviceUri(value)?.host?.lowercase()

fun isHttpsServiceUrl(value: String): Boolean = serviceUri(value)?.let { uri ->
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
} == true

fun isPlaceholderHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return true
    return normalized == "localhost" ||
        normalized == "example.com" || normalized.endsWith(".example.com") ||
        normalized == "example.net" || normalized.endsWith(".example.net") ||
        normalized == "example.org" || normalized.endsWith(".example.org") ||
        listOf(".example", ".invalid", ".localhost", ".test").any(normalized::endsWith)
}

fun isPrivateOrLocalHost(host: String?): Boolean {
    val normalized = host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return true
    if (isPlaceholderHost(normalized) || normalized.endsWith(".local")) return true
    if (normalized == "::" || normalized == "::1" || normalized.startsWith("2001:db8:") ||
        normalized.contains(':') &&
        (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:"))) {
        return true
    }
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 0 || octets[0] == 10 || octets[0] == 127 ||
        (octets[0] == 100 && octets[1] in 64..127) ||
        (octets[0] == 169 && octets[1] == 254) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 192 && octets[1] == 0 && octets[2] in setOf(0, 2)) ||
        (octets[0] == 198 && octets[1] in 18..19) ||
        (octets[0] == 198 && octets[1] == 51 && octets[2] == 100) ||
        (octets[0] == 203 && octets[1] == 0 && octets[2] == 113) ||
        octets[0] >= 224
}

fun isPlaceholderEmail(value: String): Boolean =
    isPrivateOrLocalHost(value.substringAfterLast('@', missingDelimiterValue = ""))

fun isPublicOpenStreetMapTileService(value: String): Boolean =
    serviceHost(value)?.let { host ->
        host == "tile.openstreetmap.org" || host.endsWith(".tile.openstreetmap.org")
    } == true

fun isPublicNominatimService(value: String): Boolean =
    serviceHost(value) == "nominatim.openstreetmap.org"

fun isValidEnterpriseRsaPublicKey(value: String): Boolean = runCatching {
    val encoded = Base64.getDecoder().decode(value.trim())
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
    key is RSAPublicKey && key.modulus.bitLength() >= 2_048
}.getOrDefault(false)

val tileUrlTemplate = providers.environmentVariable("GPS_TILE_URL_TEMPLATE")
    .orElse(providers.gradleProperty("GPS_TILE_URL_TEMPLATE"))
    .orElse("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
val geocodingBaseUrl = providers.environmentVariable("GPS_GEOCODING_BASE_URL")
    .orElse(providers.gradleProperty("GPS_GEOCODING_BASE_URL"))
    .orElse("https://nominatim.openstreetmap.org")
val mapAttribution = providers.environmentVariable("GPS_MAP_ATTRIBUTION")
    .orElse(providers.gradleProperty("GPS_MAP_ATTRIBUTION"))
    .orElse("© OpenStreetMap contributors")
val enterpriseLicensePublicKey = providers.environmentVariable("GPS_ENTERPRISE_LICENSE_PUBLIC_KEY")
    .orElse(providers.gradleProperty("GPS_ENTERPRISE_LICENSE_PUBLIC_KEY"))
    .orElse("")
val privacyPolicyUrl = providers.environmentVariable("GPS_PRIVACY_POLICY_URL")
    .orElse(providers.gradleProperty("GPS_PRIVACY_POLICY_URL"))
    .orElse("")
val supportEmail = providers.environmentVariable("GPS_SUPPORT_EMAIL")
    .orElse(providers.gradleProperty("GPS_SUPPORT_EMAIL"))
    .orElse("")
val enterpriseSalesEmail = providers.environmentVariable("GPS_ENTERPRISE_SALES_EMAIL")
    .orElse(providers.gradleProperty("GPS_ENTERPRISE_SALES_EMAIL"))
    .orElse(supportEmail)
val commercialRelease = providers.environmentVariable("GPS_COMMERCIAL_RELEASE")
    .orElse(providers.gradleProperty("GPS_COMMERCIAL_RELEASE"))
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val uploadStoreFile = providers.environmentVariable("GPS_UPLOAD_STORE_FILE")
val uploadStorePassword = providers.environmentVariable("GPS_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = providers.environmentVariable("GPS_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = providers.environmentVariable("GPS_UPLOAD_KEY_PASSWORD")
val uploadSigningProviders = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
)
val hasUploadSigning = uploadSigningProviders.all { !it.orNull.isNullOrBlank() }

android {
    namespace = "com.gpsupdater.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gpsupdater.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.0.0"

        buildConfigField("String", "TILE_URL_TEMPLATE", tileUrlTemplate.get().asBuildConfigString())
        buildConfigField("String", "GEOCODING_BASE_URL", geocodingBaseUrl.get().asBuildConfigString())
        buildConfigField("String", "MAP_ATTRIBUTION", mapAttribution.get().asBuildConfigString())
        buildConfigField(
            "String",
            "ENTERPRISE_LICENSE_PUBLIC_KEY",
            enterpriseLicensePublicKey.get().asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            privacyPolicyUrl.get().asBuildConfigString(),
        )
        buildConfigField("String", "SUPPORT_EMAIL", supportEmail.get().asBuildConfigString())
        buildConfigField(
            "String",
            "ENTERPRISE_SALES_EMAIL",
            enterpriseSalesEmail.get().asBuildConfigString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile.get())
                storePassword = uploadStorePassword.get()
                keyAlias = uploadKeyAlias.get()
                keyPassword = uploadKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("upload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
    }
}

val verifyCommercialReleaseConfig by tasks.registering {
    group = "verification"
    description = "Fails a commercial release when production services or upload signing are missing."
    doLast {
        if (!commercialRelease.get()) return@doLast

        val failures = mutableListOf<String>()
        val tileUrl = tileUrlTemplate.get()
        val geocodingUrl = geocodingBaseUrl.get()
        if (!isHttpsServiceUrl(tileUrl)) failures += "GPS_TILE_URL_TEMPLATE must be a valid HTTPS URL"
        if (isPlaceholderHost(serviceHost(tileUrl))) {
            failures += "GPS_TILE_URL_TEMPLATE must not use an example, test, or local host"
        }
        if (isPublicOpenStreetMapTileService(tileUrl)) {
            failures += "GPS_TILE_URL_TEMPLATE must not use the public OpenStreetMap tile service"
        }
        if (listOf("{z}", "{x}", "{y}").any { it !in tileUrl }) {
            failures += "GPS_TILE_URL_TEMPLATE must contain {z}, {x}, and {y}"
        }
        if (!isHttpsServiceUrl(geocodingUrl)) {
            failures += "GPS_GEOCODING_BASE_URL must be a valid HTTPS URL"
        }
        if (isPlaceholderHost(serviceHost(geocodingUrl))) {
            failures += "GPS_GEOCODING_BASE_URL must not use an example, test, or local host"
        }
        if (isPublicNominatimService(geocodingUrl)) {
            failures += "GPS_GEOCODING_BASE_URL must not use the public Nominatim service"
        }
        val attribution = mapAttribution.get().trim()
        if (attribution.isBlank() || attribution.contains("your map provider", ignoreCase = true)) {
            failures += "GPS_MAP_ATTRIBUTION must identify the production map provider"
        }
        val policyUrl = privacyPolicyUrl.get()
        if (!isHttpsServiceUrl(policyUrl) || isPrivateOrLocalHost(serviceHost(policyUrl))) {
            failures += "GPS_PRIVACY_POLICY_URL must be a valid public HTTPS URL"
        }
        val supportAddress = supportEmail.get().trim()
        if (!supportAddress.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) ||
            isPlaceholderEmail(supportAddress)) {
            failures += "GPS_SUPPORT_EMAIL must be a valid non-placeholder support email address"
        }
        val salesAddress = enterpriseSalesEmail.get().trim()
        if (!salesAddress.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) ||
            isPlaceholderEmail(salesAddress)) {
            failures += "GPS_ENTERPRISE_SALES_EMAIL must be a valid non-placeholder sales email address"
        }
        if (!isValidEnterpriseRsaPublicKey(enterpriseLicensePublicKey.get())) {
            failures += "GPS_ENTERPRISE_LICENSE_PUBLIC_KEY must be a valid RSA public key of at least 2048 bits"
        }
        if (!hasUploadSigning) {
            failures += "all GPS_UPLOAD_* signing variables must be configured"
        } else if (!rootProject.file(uploadStoreFile.get()).isFile) {
            failures += "GPS_UPLOAD_STORE_FILE does not point to a file"
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                failures.joinToString(
                    prefix = "Commercial release configuration is incomplete:\n - ",
                    separator = "\n - ",
                ),
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyCommercialReleaseConfig)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.android.billingclient:billing:9.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
