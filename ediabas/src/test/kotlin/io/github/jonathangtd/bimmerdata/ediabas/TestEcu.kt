/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/** Access to the user's own EDIABAS ECU folder (BIMMERDATA_ECU_DIR); tests are skipped without it. */
object TestEcu {
    fun dir(): File {
        val dir = System.getenv("BIMMERDATA_ECU_DIR")
        assumeTrue(dir != null, "BIMMERDATA_ECU_DIR not set")
        val file = File(dir!!)
        assumeTrue(file.isDirectory, "$dir is not a directory")
        return file
    }

    fun file(name: String): File {
        val file = dir().listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        assumeTrue(file != null, "$name not found")
        return file!!
    }

    /** New interpreter on the ECU folder with [sgbd] selected (e.g. "msv70"). */
    fun ediabas(sgbd: String, trace: Int = 0, log: Boolean = false): EdiabasNet {
        val ecu = dir()
        val ediabas = EdiabasNet("EcuPath=${ecu.path};IfhTrace=$trace")
        if (log) ediabas.logHook = { level, text -> println("[$level] $text") }
        ediabas.sgbdFileName = if (sgbd.contains('.')) sgbd else "$sgbd.prg"
        return ediabas
    }

    fun hex(data: ByteArray?): String = data?.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) } ?: "null"
}
