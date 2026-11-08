/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceObd
import io.github.jonathangtd.bimmerdata.ediabas.FakeInterface
import io.github.jonathangtd.bimmerdata.ediabas.sim.VehicleSimulator
import io.github.jonathangtd.bimmerdata.live.LiveTestData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whole flow on the user's real ECU files (skipped without BIMMERDATA_ECU_DIR): detection ->
 * faults -> detail -> clear -> read again, against the simulated E90 of [VehicleSimulator].
 */
class VehicleFlowTest {

    private class Rig(val sim: VehicleSimulator, val fake: FakeInterface, val runner: EdiabasJobRunner, val tables: VehicleTables)

    private fun rig(sim: VehicleSimulator = VehicleSimulator.e90(engine = null)): Rig {
        val dir = TestEcuFolder.dir()
        val fake = FakeInterface()
        fake.multiResponder = sim::respond
        val runner = EdiabasJobRunner(dir, fake)
        assertNull(runBlocking { runner.connect() })
        return Rig(sim, fake, runner, VehicleTables(dir))
    }

    /** DME fault memory answer as the user's MSV70 sent it (IFH.trc), or the same frame if the trace is not available. */
    private fun msv70FaultAnswer(): String {
        val dir = System.getenv("BIMMERDATA_TRACE_DIR") ?: return "85 F1 12 58 01 2F 08 21 39"
        val trc = java.io.File(dir).listFiles()?.firstOrNull { it.name.equals("IFH.trc", true) } ?: return "85 F1 12 58 01 2F 08 21 39"
        val line = trc.readLines(Charsets.ISO_8859_1).first { it.contains("D -> I:") && it.contains("85 F1 12 58") }
        return line.substringAfter("D -> I:").trim().split(" ").drop(3).joinToString(" ")
    }

    @Test
    fun `scan finds every ECU, the series, the VIN, the variants and the buses`() {
        val r = rig()
        r.runner.use { runner ->
            val steps = mutableListOf<VehicleScanner.Step>()
            val result = runBlocking { VehicleScanner(runner, r.tables).scan(onProgress = { steps += it.step }) }
            result.log.forEach { println("scan: $it") }
            val v = result.vehicle!!
            assertEquals(r.sim.ecus.map { it.address }, v.ecus.map { it.address })
            assertEquals("e89x", v.seriesSgbd)
            assertEquals("E8x/E9x", v.series)
            assertEquals("WBAVB71070VA12345", v.vin)
            assertEquals("VA12345", v.vinShort)
            assertEquals("VA12345", v.id)
            assertEquals(ScanMethod.Functional, v.scanMethod)
            val bySgbd = v.ecus.associate { it.address to it.sgbd }
            assertEquals(
                mapOf(
                    0x00 to "jbbf87", 0x01 to "mrs5", 0x02 to "szl_60", 0x12 to "msv70", 0x17 to "ekpm60_2", 0x29 to "dsc_87",
                    0x40 to "cas", 0x56 to "fzd_87", 0x60 to "komb87", 0x62 to "rad2_gw", 0x63 to "rad2", 0x64 to "pdc_87",
                    0x72 to "frm_87", 0x78 to "ihka87",
                ),
                bySgbd,
            )
            assertTrue(v.ecus.all { it.sgbdAvailable })
            val dme = v.ecu(0x12)!!
            assertEquals("DME/DDE", dme.name)
            assertEquals("d_motor", dme.group)
            assertEquals("Motorelektronik MS 70 6 Zylinder N52", dme.description)
            assertEquals("7561234", dme.ident.first { it.name == "ID_BMW_NR" }.value)
            // topology from the per-bus functional identification
            assertTrue(v.busTopology)
            assertEquals("PT-CAN", dme.bus)
            assertEquals("MOST", v.ecu(0x63)!!.bus)
            assertEquals("SI", v.ecu(0x01)!!.bus)
            assertEquals("K-CAN", v.ecu(0x40)!!.bus)
            assertTrue(steps.containsAll(listOf(VehicleScanner.Step.Identifying, VehicleScanner.Step.Vin, VehicleScanner.Step.Variants, VehicleScanner.Step.Buses)))
            // only read jobs went out: no clear (14), no write service
            assertTrue(r.fake.requests.none { BmwFastData.sid(it) in setOf(0x14, 0x2E, 0x3B, 0x3D, 0x27, 0x34) })
        }
    }

    @Test
    fun `faults of all ECUs, detail, clear one, clear all`() {
        val r = rig()
        r.runner.use { runner ->
            val vehicle = runBlocking { VehicleScanner(runner, r.tables).scan(options = VehicleScanner.Options(probeBuses = false)) }.vehicle!!
            val service = FaultService(runner, r.tables)
            r.fake.exchanges.clear()

            val all = runBlocking { service.readAll(vehicle) }.associateBy { it.address }
            assertEquals(vehicle.ecus.size, all.size)
            assertEquals(setOf(0x12, 0x29, 0x60, 0x72), all.values.filter { it.status == EcuFaultStatus.Faults }.map { it.address }.toSet())
            assertTrue(all.values.filter { it.address !in setOf(0x12, 0x29, 0x60, 0x72) }.all { it.status == EcuFaultStatus.Ok })
            // one functional request, then FS_LESEN only for the four ECUs with faults
            val sent = r.fake.requests.filter { it.isNotEmpty() }.map { LiveTestData.hex(it) }
            assertEquals(1, sent.count { it.startsWith("C4 EF F1 18 02 FF FF") })
            assertEquals(4, sent.count { it.matches(Regex("84 .. F1 18 02 FF FF")) })
            // the DME answer is the frame of the Tool32 trace, decoded with the MSV70 texts
            assertTrue(r.fake.exchanges.any { LiveTestData.hex(it.request) == "84 12 F1 18 02 FF FF" && LiveTestData.hex(it.response) == msv70FaultAnswer() })
            val dmeFault = all.getValue(0x12).faults.single()
            assertEquals(0x2F08, dmeFault.code)
            assertEquals("Ansauglufttemperatursensor, Signal", dmeFault.text)
            assertEquals("Kurzschluss nach Plus oder Leitungsunterbrechung", dmeFault.symptom)
            assertEquals("Pumpenmotor defekt.", all.getValue(0x29).faults.single().text)
            assertEquals("AUSSENTEMPERATURSENSOR", all.getValue(0x60).faults.single().text)

            // detail: counters, mileage, environment
            val detail = runBlocking { service.detail(vehicle.ecu(0x29)!!, all.getValue(0x29).faults.single()) }.getOrThrow()
            assertEquals(3, detail.frequency)
            assertTrue(detail.occurrences.isNotEmpty())
            assertEquals("Fahrzeuggeschwindigkeit", detail.occurrences.first().values.first().name)
            val dmeDetail = runBlocking { service.detail(vehicle.ecu(0x12)!!, dmeFault) }.getOrThrow()
            assertTrue(dmeDetail.occurrences.first().values.any { it.name == "Motordrehzahl" && it.unit == "rpm" })

            // clear the DME only, then it is read again
            val dme = runBlocking { service.clearEcu(vehicle.ecu(0x12)!!, UserConfirmation("test")) }
            assertEquals(EcuFaultStatus.Ok, dme.status)
            assertTrue(r.sim.ecu(0x12)!!.currentFaults.isEmpty())
            assertEquals(1, r.sim.ecu(0x29)!!.currentFaults.size, "other ECUs untouched")

            // clear all: functional clear, then everything is read again
            val after = runBlocking { service.clearAll(vehicle, UserConfirmation("test")) }
            assertTrue(after.all { it.status == EcuFaultStatus.Ok })
            assertTrue(r.sim.ecus.all { it.currentFaults.isEmpty() })
            assertTrue(r.fake.requests.any { LiveTestData.hex(it) == "C3 EF F1 14 FF FF" })
        }
    }

    @Test
    fun `an ECU that stops answering is shown as not responding`() {
        val r = rig()
        r.runner.use { runner ->
            val vehicle = runBlocking { VehicleScanner(runner, r.tables).scan(options = VehicleScanner.Options(probeBuses = false)) }.vehicle!!
            r.sim.ecu(0x64)!!.silent = true
            val all = runBlocking { FaultService(runner, r.tables).readAll(vehicle) }.associateBy { it.address }
            assertEquals(EcuFaultStatus.NotResponding, all.getValue(0x64).status)
            assertEquals(EcuFaultStatus.Ok, all.getValue(0x78).status)
        }
    }

    @Test
    fun `without functional answers the group files are identified one by one`() {
        val sim = VehicleSimulator.e90(engine = null).apply { functionalAddressing = false }
        val r = rig(sim)
        r.runner.use { runner ->
            val result = runBlocking { VehicleScanner(runner, r.tables).scan() }
            val v = result.vehicle!!
            assertEquals(ScanMethod.Sequential, v.scanMethod)
            assertEquals(sim.ecus.map { it.address }, v.ecus.map { it.address })
            assertEquals("msv70", v.ecu(0x12)!!.sgbd)
            assertEquals("e89x", v.seriesSgbd)
            assertEquals("WBAVB71070VA12345", v.vin)
            // faults still work, ECU by ECU
            val all = runBlocking { FaultService(runner, r.tables).readAll(v) }.associateBy { it.address }
            assertEquals(EcuFaultStatus.Faults, all.getValue(0x12).status)
        }
    }

    @Test
    fun `silent bus gives an error and no vehicle`() {
        val sim = VehicleSimulator.e90(engine = null)
        sim.ecus.forEach { it.silent = true }
        val r = rig(sim)
        r.runner.use { runner ->
            val result = runBlocking { VehicleScanner(runner, r.tables).scan(options = VehicleScanner.Options(sequentialFallback = false)) }
            assertNull(result.vehicle)
            assertNotNull(result.error)
            assertFalse(result.cancelled)
        }
    }

    @Test
    fun `a re-scan of the same car keeps its id and label`() {
        val r = rig()
        r.runner.use { runner ->
            val scanner = VehicleScanner(runner, r.tables)
            val first = runBlocking { scanner.scan(options = VehicleScanner.Options(probeBuses = false)) }.vehicle!!.copy(label = "My E90")
            val again = runBlocking { scanner.scan(previous = first, options = VehicleScanner.Options(probeBuses = false)) }.vehicle!!
            assertEquals(first.id, again.id)
            assertEquals("My E90", again.label)
        }
    }

    @Test
    fun `the runner refuses every job outside the read and clear lists`() {
        val r = rig()
        r.runner.use { runner ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { runner.run("cas", "STEUERN_FAHRGESTELLNUMMER") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { runner.run("msv70", "FS_LOESCHEN") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { runner.clear("msv70", "IDENT", UserConfirmation("test")) } }
            assertTrue(r.fake.exchanges.isEmpty())
        }
    }

    @Test
    fun `detection through the real BMW-FAST interface on a simulated K-line`() {
        val dir = TestEcuFolder.dir()
        val sim = VehicleSimulator.e90(engine = null)
        val iface = EdInterfaceObd(sim.kLine(realisticTiming = false))
        EdiabasJobRunner(dir, iface).use { runner ->
            assertNull(runBlocking { runner.connect() })
            val v = runBlocking { VehicleScanner(runner, VehicleTables(dir)).scan(options = VehicleScanner.Options(probeBuses = false, identRetries = 1)) }.vehicle!!
            assertEquals(sim.ecus.size, v.ecus.size)
            assertEquals("msv70", v.ecu(0x12)!!.sgbd)
        }
    }
}

/**
 * The files of the user's ECU folder this flow needs, copied once (lower-case names, like the app's
 * import) to a local temporary folder: the ECU folder may sit on a slow mount (WSL /mnt/c), and
 * the interpreter reads a whole SGBD every time it switches to it.
 */
internal object TestEcuFolder {
    private val needed = setOf(
        "e89x.prg", "t_grtb.prg", "t_pcod.prg", "t_ausb.prg", "t_scod.prg", "jbbf87.prg", "mrs5.prg", "szl_60.prg", "msv70.prg", "ekpm60_2.prg", "dsc_87.prg", "cas.prg",
        "fzd_87.prg", "komb87.prg", "rad2_gw.prg", "rad2.prg", "pdc_87.prg", "frm_87.prg", "ihka87.prg",
    )

    private val copy: java.io.File by lazy {
        val source = LiveTestData.ecuDir()
        val target = kotlin.io.path.createTempDirectory("bimmerdata-ecu").toFile().apply { deleteOnExit() }
        source.listFiles()?.filter { f ->
            val n = f.name.lowercase()
            n in needed || (n.endsWith(".grp") && n.startsWith("d_"))
        }?.forEach { f -> f.copyTo(java.io.File(target, f.name.lowercase())).deleteOnExit() }
        target
    }

    fun dir(): java.io.File {
        LiveTestData.ecuDir() // skips the test without the user's folder
        return copy
    }
}

/** Service id of a BMW-FAST request (-1 for receive-only exchanges). */
internal object BmwFastData {
    fun sid(telegram: ByteArray): Int {
        if (telegram.size < 4) return -1
        val offset = if ((telegram[0].toInt() and 0x3F) != 0) 3 else 4
        return if (telegram.size > offset) telegram[offset].toInt() and 0xFF else -1
    }
}
