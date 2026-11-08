/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * files EdiabasLib/EdiabasNet.cs and EdiabasLib/MemoryStreamReader.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal replacement for the .NET Stream / MemoryStreamReader pair used by EdiabasNet:
 * the whole file is held in memory, [position] may be set past the end (reads then return 0 bytes),
 * exactly like a seekable .NET stream.
 */
class EdStream(private val data: ByteArray, val name: String = "") : Closeable {
    var position: Long = 0

    val length: Long get() = data.size.toLong()

    /** .NET Stream.Read: returns the number of bytes read (0 at end of stream). */
    fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (position >= data.size || count <= 0) return 0
        val n = minOf(count.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        position += n
        return n
    }

    /** .NET Stream.ReadByte: -1 at end of stream. */
    fun readByte(): Int {
        if (position >= data.size || position < 0) return -1
        return data[(position++).toInt()].toInt() and 0xFF
    }

    override fun close() {}

    companion object {
        fun fromBytes(bytes: ByteArray, name: String = ""): EdStream = EdStream(bytes, name)
    }
}

/**
 * File helpers replacing MemoryStreamReader.OpenRead / Exists. EDIABAS file names are case-insensitive
 * (DOS heritage) while SGBDs reference each other in lower case ("uses" names are lowered), so on a
 * case-sensitive file system an exact match is tried first, then a case-insensitive match in the folder.
 * File-name encoding (EdiabasNet.EncodeFilePath, .enc0) is not supported (out of scope).
 */
object EdFiles {
    fun resolve(path: String): File? {
        val file = File(path)
        if (file.isFile) return file
        val dir = file.absoluteFile.parentFile ?: return null
        val name = file.name
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    fun exists(path: String): Boolean = resolve(path) != null

    /** MemoryStreamReader.OpenRead: throws FileNotFoundException if missing. */
    fun openRead(path: String): EdStream {
        val file = resolve(path) ?: throw FileNotFoundException(path)
        return EdStream(file.readBytes(), file.path)
    }

    /** Path.Combine (simplified: a rooted second part wins, empty first part is ignored). */
    fun combine(dir: String, name: String): String {
        if (dir.isEmpty()) return name
        if (File(name).isAbsolute || name.startsWith("/") || name.startsWith("\\")) return name
        return File(dir, name).path
    }

    /** Path.GetFileNameWithoutExtension (accepts both '/' and '\' as separators). */
    fun getFileNameWithoutExtension(path: String?): String {
        if (path == null) return ""
        val fileName = getFileName(path)
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0) fileName.substring(0, dot) else fileName
    }

    /** Path.GetFileName. */
    fun getFileName(path: String): String {
        val sep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (sep >= 0) path.substring(sep + 1) else path
    }

    /** Path.GetDirectoryName (empty string instead of null for a bare file name). */
    fun getDirectoryName(path: String): String {
        val sep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (sep >= 0) path.substring(0, sep) else ""
    }
}
