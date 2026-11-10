import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.jonathangtd.bimmerdata"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jonathangtd.bimmerdata"
        minSdk = 29
        targetSdk = 37
        // -PversionCode=N overrides it (Google Play uploads need a new code each time, see
        // tools: the publishing script takes the last code on Play + 1)
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 4
        // shown to users: the publishing script raises the last number when this one is already on Play
        versionName = "0.4.3"
    }

    // Release signing key, kept OUTSIDE the repository (never committed): a properties file with
    // storeFile / storePassword / keyAlias / keyPassword, by default
    // ~/.config/bimmerdata/release.properties, or BIMMERDATA_RELEASE_PROPERTIES, or the
    // BIMMERDATA_KEYSTORE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD variables.
    // Without them the release APK is built unsigned (it cannot be installed as is).
    val releaseSigning: Map<String, String>? = run {
        val env = System.getenv()
        if (env["BIMMERDATA_KEYSTORE"] != null) {
            return@run mapOf(
                "storeFile" to env.getValue("BIMMERDATA_KEYSTORE"),
                "storePassword" to env["BIMMERDATA_STORE_PASSWORD"].orEmpty(),
                "keyAlias" to (env["BIMMERDATA_KEY_ALIAS"] ?: "bimmerdata"),
                "keyPassword" to (env["BIMMERDATA_KEY_PASSWORD"] ?: env["BIMMERDATA_STORE_PASSWORD"].orEmpty()),
            )
        }
        val file = File(env["BIMMERDATA_RELEASE_PROPERTIES"] ?: (System.getProperty("user.home") + "/.config/bimmerdata/release.properties"))
        if (!file.isFile) return@run null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        props.stringPropertyNames().associateWith { props.getProperty(it) }
    }
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("BimmerData: no release signing key found (~/.config/bimmerdata/release.properties): the release APK will be UNSIGNED.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Robolectric screenshot tests (ScreenshotTest) need the merged resources and fonts.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":ediabas"))
    implementation(project(":live"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.usb.serial)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.car.app)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // screenshot tests: JUnit 4 + Robolectric, run on the JUnit platform by the vintage engine
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
}

// Robolectric screenshot tests take most of the test time: they only run with -Pscreenshots
// (UI changes, README pictures). Test classes run in parallel JVMs.
val withScreenshots = providers.gradleProperty("screenshots").isPresent

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (!withScreenshots) exclude("**/*ScreenshotTest*")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 6)
    // Optional: the user's own EDIABAS ECU folder, for tests that need real .PRG files.
    System.getenv("BIMMERDATA_ECU_DIR")?.let { environment("BIMMERDATA_ECU_DIR", it) }
    // Robolectric reaches into FileDescriptor internals (shared memory of recent Android SDKs)
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    // ScreenshotTest writes its PNGs there (default: app/build/screenshots)
    System.getenv("BIMMERDATA_SCREENSHOT_DIR")?.let { environment("BIMMERDATA_SCREENSHOT_DIR", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
