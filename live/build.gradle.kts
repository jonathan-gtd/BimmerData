plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":ediabas"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    System.getenv("BIMMERDATA_ECU_DIR")?.let { environment("BIMMERDATA_ECU_DIR", it) }
    System.getenv("BIMMERDATA_TRACE_DIR")?.let { environment("BIMMERDATA_TRACE_DIR", it) }
    // Optional end-to-end export test against a real Telegraf listener (deploy/telegraf/)
    System.getenv("BIMMERDATA_TELEGRAF_URL")?.let { environment("BIMMERDATA_TELEGRAF_URL", it) }
    System.getenv("BIMMERDATA_TELEGRAF_TOKEN")?.let { environment("BIMMERDATA_TELEGRAF_TOKEN", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Translation template of one SGBD from your own ECU folder (see translations/README.md):
// ./gradlew :live:translationTemplate -Psgbd=msv70 [-Plang=fr] [-Pmerge=translations/fr/msv70.csv] [-Pout=file.csv] [-PecuDir=...]
tasks.register<JavaExec>("translationTemplate") {
    group = "application"
    description = "Writes the translation template (CSV) of an SGBD from the ECU folder"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.jonathangtd.bimmerdata.live.i18n.TranslationTemplateToolKt")
    workingDir = rootDir
    System.getenv("BIMMERDATA_ECU_DIR")?.let { environment("BIMMERDATA_ECU_DIR", it) }
    val names = listOf("sgbd", "lang", "merge", "out", "ecuDir")
    args = names.mapNotNull { n -> project.findProperty(n)?.toString()?.let { "$n=$it" } } + "rootDir=${rootDir.path}"
}
