plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
}

// Small command-line tool: ./gradlew :ediabas:prgDump --args="/path/MSV70.PRG [TABLE_NAME]"
tasks.register<JavaExec>("prgDump") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.jonathangtd.bimmerdata.ediabas.tools.PrgDumpKt")
}
