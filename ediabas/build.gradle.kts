plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // UIntArray (C# UInt32[] for communication parameters and table offsets)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Optional: folder with the user's own EDIABAS ECU files, for tests that need real .PRG files.
    System.getenv("BIMMERDATA_ECU_DIR")?.let { environment("BIMMERDATA_ECU_DIR", it) }
    // Optional: folder with Tool32 traces (IFH.trc + api.trc) of the user's own car, for Tool32ReferenceTest.
    System.getenv("BIMMERDATA_TRACE_DIR")?.let { environment("BIMMERDATA_TRACE_DIR", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Small command-line tool: ./gradlew :ediabas:prgDump --args="/path/MSV70.PRG [TABLE_NAME]"
tasks.register<JavaExec>("prgDump") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.jonathangtd.bimmerdata.ediabas.tools.PrgDumpKt")
}
