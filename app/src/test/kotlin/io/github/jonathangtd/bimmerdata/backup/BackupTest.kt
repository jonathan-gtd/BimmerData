/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.backup

import io.github.jonathangtd.bimmerdata.api.Scope
import io.github.jonathangtd.bimmerdata.dashboard.Card
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.export.ExportMode
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.graph.Graph
import io.github.jonathangtd.bimmerdata.graph.GraphSeries
import io.github.jonathangtd.bimmerdata.i18n.EcuSelection
import io.github.jonathangtd.bimmerdata.i18n.TranslationConfig
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.ScanMethod
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import io.github.jonathangtd.bimmerdata.profiles.Profile
import io.github.jonathangtd.bimmerdata.profiles.ProfileSet
import io.github.jonathangtd.bimmerdata.profiles.ProfileVehicle
import io.github.jonathangtd.bimmerdata.settings.ApiConfig
import io.github.jonathangtd.bimmerdata.settings.BluetoothSettings
import io.github.jonathangtd.bimmerdata.settings.GpsSettings
import io.github.jonathangtd.bimmerdata.settings.RecordingConfig
import io.github.jonathangtd.bimmerdata.settings.Settings
import io.github.jonathangtd.bimmerdata.settings.TelegrafAuthMode
import io.github.jonathangtd.bimmerdata.settings.TelegrafSettings
import io.github.jonathangtd.bimmerdata.vehicle.Garage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory [BackupParts]: the app state the backup reads and replaces; [failOn] makes one apply fail once. */
class FakeBackupParts(
    var garage: Garage = Garage(),
    var profiles: ProfileSet = ProfileSet(),
    var settings: Settings = Settings(),
    var translationConfig: TranslationConfig = TranslationConfig(),
    var translationFiles: Map<String, String> = emptyMap(),
    var secretSet: Boolean = false,
) : BackupParts {
    var failOn: String? = null
    var prepared = 0
    var busy = false

    private fun maybeFail(part: String) {
        if (failOn == part) {
            failOn = null
            throw IllegalStateException("disk full ($part)")
        }
    }

    override fun prepare() {
        if (busy) throw BackupException("busy", "a scan runs")
        prepared++
    }

    override fun capture(limitTranslations: Boolean) = BackupContent(
        garage, profiles, BackupSettings.of(settings),
        BackupTranslations.of(translationConfig, translationFiles.map { (k, v) -> BackupTranslationFile(k.substringBefore('/'), k.substringAfter('/'), v) }),
    )

    override fun applyGarage(garage: Garage) {
        maybeFail("garage"); this.garage = garage
    }

    override fun applyProfiles(profiles: ProfileSet) {
        maybeFail("profiles"); this.profiles = profiles
    }

    override fun applySettings(settings: BackupSettings, includeApi: Boolean) {
        maybeFail("settings"); this.settings = settings.applyTo(this.settings, includeApi)
    }

    override fun applyTranslations(translations: BackupTranslations) {
        maybeFail("translations")
        translationConfig = translations.applyTo(translationConfig)
        translations.files?.let { files -> translationFiles = files.associate { "${it.language}/${it.sgbd}" to it.csv } }
    }

    override fun telegrafSecretSet() = secretSet

    /** Everything a backup restores, for comparisons. */
    fun state() = listOf(garage, profiles, settings, translationConfig, translationFiles)

    companion object {
        const val VIN = "WBAVB71070VA12345"
        const val API_TOKEN = "bd_THE_API_TOKEN_MUST_NEVER_LEAVE"
        const val TR_TOKEN = "tr_TRANSLATION_TOKEN_SECRET"

        /** A phone with one scanned car with faults, two profiles (pages, Android Auto, graphs) and settings. */
        fun populated(): FakeBackupParts {
            val car = Vehicle(
                id = "car-e90", label = "E90 330i", vin = VIN, vinShort = "VA12345", series = "E90", model = "330i",
                ecus = listOf(VehicleEcu(0x12, "DME/DDE", "Motorelektronik MS 70", "d_motor", "msv70", true), VehicleEcu(0x60, "KOMBI", null, null, "komb87", true)),
                scannedAtMs = 1_794_296_709_624, scanMethod = ScanMethod.Functional, addedSgbds = listOf("cas4"),
            )
            val garage = Garage(
                vehicles = listOf(car, Vehicle(id = "car-mini", label = "Mini", ecus = emptyList())),
                currentId = "car-e90",
                faults = mapOf("car-e90" to listOf(EcuFaults(0x12, EcuFaultStatus.Faults, listOf(Fault(0x2F08, "Ansauglufttemperatursensor")), readAtMs = 7))),
            )
            val page = Page(id = "p1", name = "Engine", columns = 2, rows = 3, cards = listOf(Card(id = "c1", type = GaugeType.Dial, measurementIds = listOf("msv70/0x4402"))))
            val aa = Page(id = "a1", name = "Engine 1/1", columns = 4, rows = 2, cards = listOf(Card(id = "c2", type = GaugeType.Dial, measurementIds = listOf("msv70/0x4402"))))
            val profiles = ProfileSet(
                profiles = listOf(
                    Profile(
                        id = "prof1", name = "E90 330i · Ratés", carId = "car-e90", vehicle = ProfileVehicle("E90", "330i"), ecus = listOf("msv70"),
                        dashboard = Dashboard(phonePages = listOf(page), carPages = listOf(aa)), lastUsedMs = 5,
                        graphs = listOf(Graph(id = "g1", name = "Oil", series = listOf(GraphSeries("msv70/0x4402")))),
                    ),
                    Profile(id = "prof2", name = "Mini · Infos", carId = "car-mini"),
                ),
                activeId = "prof1",
            )
            val settings = Settings(
                autoConnectOnCable = true,
                api = ApiConfig(enabled = true, port = 8765, token = API_TOKEN, scopes = setOf(Scope.Read, Scope.Layout, Scope.Config), https = true),
                lastMode = LiveMode.Bluetooth,
                recording = RecordingConfig(folderUri = "content://tree/primary%3ALogs", folderLabel = "Logs", rowIntervalMs = 200, filePrefix = "e90"),
                telegraf = TelegrafSettings(enabled = true, url = "https://bimmerdata.example/api/v2/write", authMode = TelegrafAuthMode.Token, car = "E90", mode = ExportMode.Live),
                bluetooth = BluetoothSettings("00:1D:A5:06:22:1B", "JGOBD", connectOnStart = true),
                gps = GpsSettings(enabled = true, intervalMs = 5000),
            )
            val tr = TranslationConfig(language = "fr", token = TR_TOKEN, selection = EcuSelection.Auto)
            return FakeBackupParts(garage, profiles, settings, tr, mapOf("fr/msv70" to "id;de;fr\n0x4402;Öltemperatur;Température d'huile\n"), secretSet = true)
        }
    }
}

class BackupTest {
    private val info = BackupInfo("0.4.0", 14, 1_794_300_000_000)

    @Test
    fun `round trip - export, empty phone, restore gives the same cars, profiles, graphs and settings`() {
        val phone = FakeBackupParts.populated()
        val text = BackupRestorer(phone).export(info)

        val fresh = FakeBackupParts(settings = Settings(api = ApiConfig(token = "bd_new_token")))
        val summary = BackupRestorer(fresh).restore(text, includeApi = true)

        assertEquals(phone.garage, fresh.garage, "cars, VIN, ECU lists, scan, faults, selected car")
        assertEquals(phone.profiles, fresh.profiles, "profiles, pages, Android Auto, graphs, active profile")
        assertEquals(phone.translationFiles, fresh.translationFiles)
        assertEquals("fr", fresh.translationConfig.language)
        val s = fresh.settings
        assertEquals(phone.settings.copy(api = phone.settings.api.copy(token = "bd_new_token"), recording = phone.settings.recording.copy(folderUri = null, folderLabel = null)), s)
        assertEquals(1, fresh.prepared, "the app disconnects first")

        assertEquals(2, summary.cars)
        assertEquals(1, summary.carsWithVin)
        assertEquals(1, summary.carsScanned)
        assertEquals(2, summary.profiles)
        assertEquals(1, summary.graphs)
        assertEquals("E90 330i · Ratés", summary.activeProfile)
        assertEquals("E90 330i", summary.selectedCar)
        assertEquals(1, summary.translationFiles)
        assertTrue(summary.apiSettingsRestored)
        assertTrue("telegraf_secret" in summary.todoCodes, "the new phone has no Telegraf token")
        assertEquals("0.4.0", summary.appVersion)

        // and once more: a backup of the restored phone is the same file (but its date)
        assertEquals(text, BackupRestorer(fresh).export(info))
    }

    @Test
    fun `no secret in the file, the VIN is`() {
        val text = BackupRestorer(FakeBackupParts.populated()).export(info)
        assertFalse(FakeBackupParts.API_TOKEN in text)
        assertFalse(FakeBackupParts.TR_TOKEN in text)
        assertFalse("content://" in text, "no folder picked on the phone")
        assertFalse(Regex("\"(token|password|secret)\"\\s*:").containsMatchIn(text), "no secret field")
        assertTrue(FakeBackupParts.VIN in text, "the user's own backup keeps the VIN")
        assertTrue(text.contains("\"format\": \"bimmerdata-backup\"") && text.contains("\"version\": 1"), text.take(300))
        assertTrue(text.contains("\"appVersionCode\": 14"))
    }

    @Test
    fun `through the API the Local API settings stay as they are`() {
        val text = BackupRestorer(FakeBackupParts.populated()).export(info)
        val mine = ApiConfig(enabled = true, port = 9000, token = "bd_mine", scopes = setOf(Scope.Read, Scope.Config))
        val fresh = FakeBackupParts(settings = Settings(api = mine))
        val summary = BackupRestorer(fresh).restore(text, includeApi = false)
        assertEquals(mine, fresh.settings.api)
        assertFalse(summary.apiSettingsRestored)
        assertEquals(LiveMode.Bluetooth, fresh.settings.lastMode)
    }

    @Test
    fun `a failure while replacing puts everything back`() {
        val text = BackupRestorer(FakeBackupParts.populated()).export(info)
        for (part in listOf("garage", "profiles", "settings", "translations")) {
            val other = FakeBackupParts(
                garage = Garage(listOf(Vehicle(id = "x", label = "Other", ecus = emptyList())), "x"),
                profiles = ProfileSet(profiles = listOf(Profile(id = "o", name = "Other", carId = "x")), activeId = "o"),
                settings = Settings(autoConnectOnCable = false, api = ApiConfig(token = "bd_t")),
                translationFiles = mapOf("en/msv70" to "x"),
            )
            val before = other.state()
            other.failOn = part
            val e = assertThrows(BackupException::class.java) { BackupRestorer(other).restore(text, includeApi = true) }
            assertEquals("restore_failed", e.code)
            assertTrue(e.message!!.contains("nothing was changed"), e.message)
            assertEquals(before, other.state(), "after a failure on $part")
        }
    }

    @Test
    fun `dry run checks and changes nothing`() {
        val text = BackupRestorer(FakeBackupParts.populated()).export(info)
        val other = FakeBackupParts()
        val before = other.state()
        val s = BackupRestorer(other).restore(text, includeApi = false, dryRun = true)
        assertTrue(s.dryRun)
        assertEquals(2, s.profiles)
        assertEquals(before, other.state())
        assertEquals(0, other.prepared)
    }

    @Test
    fun `busy phone refuses before anything changes`() {
        val text = BackupRestorer(FakeBackupParts.populated()).export(info)
        val other = FakeBackupParts().apply { busy = true }
        val before = other.state()
        assertEquals("busy", assertThrows(BackupException::class.java) { BackupRestorer(other).restore(text, includeApi = false) }.code)
        assertEquals(before, other.state())
    }

    @Test
    fun `wrong files are refused with a clear reason`() {
        fun code(text: String) = assertThrows(BackupException::class.java) { BackupCodec.decode(text) }
        assertEquals("not_a_backup", code("not json").code)
        assertEquals("not_a_backup", code("[1,2]").code)
        assertEquals("not_a_backup", code("""{"format":"something-else","version":1}""").code)
        val profile = code("""{"kind":"bimmerdata-profile","formatVersion":1,"name":"x"}""")
        assertEquals("not_a_backup", profile.code)
        assertTrue(profile.message!!.contains("profile file"), profile.message)
        val newer = code("""{"format":"bimmerdata-backup","version":2,"garage":{},"profiles":{}}""")
        assertEquals("newer_version", newer.code)
        assertTrue(newer.message!!.contains("newer version"))
        assertEquals("invalid_backup", code("""{"format":"bimmerdata-backup","garage":{},"profiles":{}}""").code)
        assertEquals("invalid_backup", code("""{"format":"bimmerdata-backup","version":1}""").code, "no garage / profiles")
        assertEquals("invalid_backup", code("""{"format":"bimmerdata-backup","version":1,"garage":{"vehicles":"x"},"profiles":{}}""").code)

        val good = BackupRestorer(FakeBackupParts.populated()).export(info)
        val orphan = good.replace("\"carId\": \"car-mini\"", "\"carId\": \"car-gone\"")
        assertTrue(orphan != good)
        assertTrue(code(orphan).message!!.contains("car-gone"))
        val traversal = good.replace("\"sgbd\": \"msv70\"", "\"sgbd\": \"../../shared_prefs/settings\"")
        assertTrue(traversal != good)
        assertEquals("invalid_backup", code(traversal).code)
    }

    @Test
    fun `a backup without translation files keeps the phone's cache`() {
        val phone = FakeBackupParts.populated()
        val content = phone.capture(true).let { it.copy(translations = it.translations.copy(files = null, filesOmitted = 3)) }
        val text = BackupCodec.encode(content, info)
        val other = FakeBackupParts(translationFiles = mapOf("fr/cas4" to "kept"))
        val summary = BackupRestorer(other).restore(text, includeApi = false)
        assertNull(summary.translationFiles)
        assertEquals(mapOf("fr/cas4" to "kept"), other.translationFiles)
        assertEquals("fr", other.translationConfig.language)
    }

    @Test
    fun `odd values in a hand-edited file fall back to safe ones`() {
        val s = BackupSettings(lastMode = "warp", gpsIntervalMs = 3, recordingRowIntervalMs = 7, recordingFilePrefix = "../x y", bluetoothAddress = "not-a-mac",
            telegrafAuth = "weird", api = BackupApi(true, 80, listOf("read", "root"), false))
        val r = s.applyTo(Settings(api = ApiConfig(token = "t")), includeApi = true)
        assertNull(r.lastMode)
        assertEquals(1000, r.gps.intervalMs)
        assertEquals(0, r.recording.rowIntervalMs)
        assertFalse(r.recording.filePrefix.contains("/"))
        assertNull(r.bluetooth.address)
        assertEquals(TelegrafAuthMode.Token, r.telegraf.authMode)
        assertEquals(ApiConfig.DEFAULT_PORT, r.api.port)
        assertEquals(setOf(Scope.Read), r.api.scopes)
        assertEquals("t", r.api.token)
    }

    @Test
    fun `file name`() {
        assertEquals("bimmerdata-backup-20261110-1432.json", BackupCodec.fileName(java.time.Instant.parse("2026-11-10T14:32:00Z").toEpochMilli(), java.time.ZoneOffset.UTC))
    }
}
