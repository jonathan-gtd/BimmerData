/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.i18n

import java.util.Locale

/**
 * Reads and writes translation files: UTF-8, `;` separated, first line `type;cle;de;traduction;statut`.
 *
 * Reading is lenient, so that files edited in LibreOffice or Excel load as they are:
 * - optional UTF-8 BOM, `\n` or `\r\n` line ends, empty lines;
 * - fields in double quotes may contain `;`, quotes (doubled) and line breaks;
 * - the header may use English column names (`type;key;de;translation;status`) and any column
 *   order; without a header the default order is assumed;
 * - a line with an unknown type is skipped and reported in [Parsed.errors].
 */
object TranslationCsv {
    const val Header = "type;cle;de;traduction;statut"

    class Parsed(val entries: List<TranslationEntry>, val errors: List<String>)

    private val headerNames: Map<String, Int> = mapOf(
        "type" to 0,
        "cle" to 1, "clé" to 1, "key" to 1,
        "de" to 2, "deutsch" to 2, "original" to 2,
        "traduction" to 3, "translation" to 3, "text" to 3, "texte" to 3,
        "statut" to 4, "status" to 4,
    )

    fun parse(text: String): Parsed {
        val rows = rows(text.removePrefix("﻿"))
        val errors = ArrayList<String>()
        val out = ArrayList<TranslationEntry>()
        var columns = intArrayOf(0, 1, 2, 3, 4)
        var start = 0
        rows.firstOrNull()?.let { (_, first) ->
            val mapped = first.map { headerNames[it.trim().lowercase(Locale.ROOT)] }
            if (0 in mapped && 1 in mapped && mapped.count { it != null } >= 3) {
                // column index in the file for each logical field (-1 = missing)
                columns = IntArray(5) { field -> mapped.indexOf(field) }
                start = 1
            }
        }
        for (i in start until rows.size) {
            val (line, cells) = rows[i]
            if (cells.all { it.isBlank() }) continue
            fun cell(field: Int) = columns[field].let { c -> if (c >= 0 && c < cells.size) cells[c] else "" }
            val type = TranslationType.fromId(cell(0))
            if (type == null) {
                errors += "line $line: unknown type \"${cell(0).trim()}\""
                continue
            }
            out += TranslationEntry(
                type = type,
                key = TranslationKeys.normalize(type, cell(1)),
                de = cell(2).trim(),
                text = cell(3).trim(),
                status = TranslationStatus.fromId(cell(4)),
            )
        }
        return Parsed(out, errors)
    }

    /** Splits [text] into rows of cells; each row comes with its 1-based start line. */
    internal fun rows(text: String): List<Pair<Int, List<String>>> {
        val rows = ArrayList<Pair<Int, List<String>>>()
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1
        var rowLine = 1
        var i = 0
        val n = text.length
        fun endRow() {
            cells.add(cell.toString())
            cell.setLength(0)
            rows.add(rowLine to cells)
            cells = ArrayList()
        }
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> { if (c == '\n') line++; cell.append(c) }
                }
            } else {
                when (c) {
                    '"' -> if (cell.isBlank()) { cell.setLength(0); inQuotes = true } else cell.append(c)
                    ';' -> { cells.add(cell.toString()); cell.setLength(0) }
                    '\r' -> {}
                    '\n' -> { endRow(); line++; rowLine = line }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }

    fun write(entries: List<TranslationEntry>): String {
        val sb = StringBuilder(Header).append('\n')
        for (e in entries) {
            sb.append(e.type.id).append(';').append(field(e.key)).append(';').append(field(e.de)).append(';')
                .append(field(e.text)).append(';').append(if (e.translated) e.status.id else "").append('\n')
        }
        return sb.toString()
    }

    private fun field(s: String): String {
        val clean = s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
        val quote = clean.any { it == ';' || it == '"' } || clean != clean.trim()
        return if (quote) "\"" + clean.replace("\"", "\"\"") + "\"" else clean
    }
}
