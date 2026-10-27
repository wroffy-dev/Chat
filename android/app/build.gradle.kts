import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun apiBaseUrl(buildType: String): String {
    val url = (findProperty("vaultchat.apiBaseUrl") ?: findProperty("vaultchat.apiBaseUrl.$buildType"))
        ?.toString()
        ?: throw GradleException("Missing vaultchat.apiBaseUrl.$buildType in gradle.properties")
    if (buildType == "release" && !url.startsWith("https://")) {
        throw GradleException("Release builds must use an https:// API base URL")
    }
    return if (url.endsWith("/")) url else "$url/"
}

/**
 * Debug builds may use plain HTTP only to the development machine: the emulator's host alias and the
 * host in the debug API base URL (e.g. a LAN address for phones on the same Wi-Fi). LiveKit runs on the
 * same machine, so its ws:// URL uses the same host. Release builds keep the HTTPS-only config.
 */
abstract class DebugNetworkConfigTask : DefaultTask() {
    @get:Input
    abstract val hosts: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val domains = hosts.get().joinToString("\n") { "        <domain includeSubdomains=\"false\">$it</domain>" }
        val file = outputDir.get().file("xml/network_security_config.xml").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated for debug builds by app/build.gradle.kts; do not edit. -->
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="false">
            |        <trust-anchors>
            |            <certificates src="system" />
            |        </trust-anchors>
            |    </base-config>
            |    <domain-config cleartextTrafficPermitted="true">
            |$domains
            |    </domain-config>
            |</network-security-config>
            |
            """.trimMargin(),
        )
    }
}

android {
    namespace = "app.vaultchat"
    compileSdk = 35

    defaultConfig {
        // Firebase client identifiers for FCM (not secrets, but kept out of git). Empty = push disabled.
        // Set vaultchat.fcm.* in ~/.gradle/gradle.properties (values from the Firebase console).
        listOf("projectId", "appId", "apiKey", "senderId").forEach { key ->
            val value = findProperty("vaultchat.fcm.$key")?.toString() ?: ""
            buildConfigField("String", "FCM_${key.uppercase()}", "\"$value\"")
        }
        // Calls through Android's call framework (headset/car/watch buttons, system audio routing).
        // Off until tested on the two phones: set vaultchat.systemCalls=true to try it (docs/08 §10).
        val systemCalls = findProperty("vaultchat.systemCalls")?.toString() == "true"
        buildConfigField("boolean", "SYSTEM_CALLS", systemCalls.toString())
        // Working application ID; final name is open decision D-14 (docs/17-open-decisions.md).
        applicationId = "app.vaultchat"
        // Proposed floor; final value depends on the two users' phones (open decision D-09).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Release signing material is never committed. Provide it on the release machine via
        // ~/.gradle/gradle.properties (see docs/16-deployment-and-operations.md §9).
        val storeFilePath = findProperty("vaultchat.signing.storeFile")?.toString()
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = findProperty("vaultchat.signing.storePassword")?.toString()
                keyAlias = findProperty("vaultchat.signing.keyAlias")?.toString()
                keyPassword = findProperty("vaultchat.signing.keyPassword")?.toString()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("debug")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("release")}\"")
            signingConfig = signingConfigs.findByName("release")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val apiHost = URI(apiBaseUrl("debug")).host
            ?: throw GradleException("vaultchat.apiBaseUrl has no host")
        if (!Regex("^[A-Za-z0-9.-]+$").matches(apiHost)) throw GradleException("Unexpected API host: $apiHost")
        val task = tasks.register<DebugNetworkConfigTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}NetworkConfig") {
            hosts.set(listOf("10.0.2.2", "localhost", "127.0.0.1", apiHost).distinct())
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, DebugNetworkConfigTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // QR encode/decode entirely on-device (no ML Kit / Google services).
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // WebRTC calls through the self-hosted LiveKit SFU (docs/08-calling.md).
    implementation(libs.livekit.android)
    // Self-managed calls in Android's telecom framework; used only when SYSTEM_CALLS is on.
    implementation(libs.core.telecom)
    // FCM only (no Analytics/Crashlytics). Initialised manually from build properties; see docs/10.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
