plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

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
            signingConfig = if (hasUploadSigning) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
