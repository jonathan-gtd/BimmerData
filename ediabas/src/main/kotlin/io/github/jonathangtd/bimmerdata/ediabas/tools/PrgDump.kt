/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas.tools

import io.github.jonathangtd.bimmerdata.ediabas.PrgFile
import java.io.File
import kotlin.system.exitProcess

/** Usage: prgDump <file.PRG> [TABLE_NAME]  - lists jobs and tables, or prints one table. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: prgDump <file.PRG|file.GRP> [TABLE_NAME]")
        exitProcess(2)
    }
    val prg = PrgFile.open(File(args[0]))
    if (!prg.isObjectFile) {
        System.err.println("not an EDIABAS object file: ${prg.signature}")
        exitProcess(1)
    }
    if (args.size >= 2) {
        val table = prg.table(args[1]) ?: run {
            System.err.println("no table ${args[1]}")
            exitProcess(1)
        }
        println(table.header.joinToString(" | "))
        table.rows.forEach { println(it.joinToString(" | ")) }
        return
    }
    println("${prg.jobs.size} jobs")
    println(prg.jobs.joinToString(", ") { it.name })
    println()
    println("${prg.tables.size} tables")
    println(prg.tables.joinToString(", ") { "${it.name}(${it.columnCount}x${it.rowCount})" })
}
