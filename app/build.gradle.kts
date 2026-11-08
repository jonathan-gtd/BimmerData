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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
