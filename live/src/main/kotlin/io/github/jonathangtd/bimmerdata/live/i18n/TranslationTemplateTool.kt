/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import java.io.File
import kotlin.system.exitProcess

/**
 * Command line: writes the translation template of an SGBD from the user's own ECU folder.
 *
 * ```
 * ./gradlew :live:translationTemplate -Psgbd=msv70 [-Plang=fr] [-Pmerge=translations/fr/msv70.csv] [-Pout=file.csv]
 * ```
 * The ECU folder is `-PecuDir=...` or the environment variable BIMMERDATA_ECU_DIR. With `merge`,
 * the translations of an existing file are kept (the `de` column is refreshed from the SGBD).
 * Default output: `build/translations/<lang>/<sgbd>.csv` of the root project.
 */
fun main(args: Array<String>) {
    val opts = args.mapNotNull { a -> a.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].removePrefix("--") to it[1] } }.toMap()
    val sgbd = opts["sgbd"]?.takeIf { it.isNotBlank() } ?: fail("missing sgbd (-Psgbd=msv70)")
    val ecuDir = File(opts["ecuDir"]?.takeIf { it.isNotBlank() } ?: System.getenv("BIMMERDATA_ECU_DIR") ?: fail("missing ECU folder (-PecuDir=... or BIMMERDATA_ECU_DIR)"))
    val file = ecuDir.listFiles()?.firstOrNull { it.isFile && it.name.equals("$sgbd.prg", ignoreCase = true) } ?: fail("$sgbd.prg not found in $ecuDir")
    val lang = opts["lang"]?.takeIf { it.isNotBlank() } ?: "xx"
    var entries = TranslationTemplate.generate(sgbd, file)
    opts["merge"]?.takeIf { it.isNotBlank() }?.let { path ->
        val f = File(path)
        if (f.isFile) {
            val parsed = TranslationCsv.parse(f.readText(Charsets.UTF_8))
            parsed.errors.forEach { System.err.println("$path: $it") }
            entries = TranslationTemplate.merge(entries, parsed.entries)
        }
    }
    val out = File(opts["out"]?.takeIf { it.isNotBlank() } ?: "${opts["rootDir"] ?: "."}/build/translations/$lang/${sgbd.lowercase()}.csv")
    out.parentFile?.mkdirs()
    out.writeText(TranslationCsv.write(entries), Charsets.UTF_8)
    val counts = entries.groupingBy { it.type }.eachCount().entries.joinToString(", ") { (t, n) -> "${t.id} $n" }
    println("${out.path}: ${entries.size} entries ($counts), ${entries.count { it.translated }} translated")
}

private fun fail(message: String): Nothing {
    System.err.println("translationTemplate: $message")
    exitProcess(2)
}
