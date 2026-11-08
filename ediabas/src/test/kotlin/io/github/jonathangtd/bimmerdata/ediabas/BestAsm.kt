/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Operand of the tiny BEST/2 assembler used to build synthetic test object files. */
sealed class Arg(val mode: Int) {
    object None : Arg(0)
    class Reg(val code: Int, mode: Int) : Arg(mode)
    class Imm8(val v: Int) : Arg(5)
    class Imm16(val v: Int) : Arg(6)
    class Imm32(val v: Long) : Arg(7)
    class Str(val bytes: ByteArray) : Arg(8)
    class IdxImm(val reg: Int, val idx: Int) : Arg(9)
    class IdxReg(val reg: Int, val idxReg: Int) : Arg(10)
    class IdxRegImm(val reg: Int, val idxReg: Int, val inc: Int) : Arg(11)
    class IdxImmLenImm(val reg: Int, val idx: Int, val len: Int) : Arg(12)
    class IdxImmLenReg(val reg: Int, val idx: Int, val lenReg: Int) : Arg(13)
    class IdxRegLenImm(val reg: Int, val idxReg: Int, val len: Int) : Arg(14)
    class IdxRegLenReg(val reg: Int, val idxReg: Int, val lenReg: Int) : Arg(15)
    class Label(val name: String) : Arg(7)

    companion object {
        /** Register by name: B0..BF, A0..AF, I0..IF, L0..L7, S0..SF, F0..F7. */
        fun reg(name: String): Reg {
            val n = name.substring(1).toInt(16)
            return when (name[0]) {
                'B' -> Reg(n, 2)
                'A' -> Reg(0x80 + n, 2)
                'I' -> Reg(if (n < 8) 0x10 + n else 0x90 + n - 8, 3)
                'L' -> Reg(if (n < 4) 0x18 + n else 0x98 + n - 4, 4)
                'S' -> Reg(if (n < 8) 0x1C + n else 0x2C + n - 8, 1)
                'F' -> Reg(0x24 + n, 1)
                else -> throw IllegalArgumentException(name)
            }
        }

        fun regCode(name: String): Int = reg(name).code

        fun str(s: String, terminate: Boolean = true): Str = Str(Cp1252.encode(s) + if (terminate) byteArrayOf(0) else byteArrayOf())
    }
}

/** Assembles one job body; jump targets are labels (Imm32 relative to the next instruction). */
class BestAsm {
    private class Fixup(val position: Int, val instrEnd: Int, val label: String)

    private val out = ByteArrayOutputStream()
    private val labels = HashMap<String, Int>()
    private val fixups = ArrayList<Fixup>()

    fun label(name: String): BestAsm {
        labels[name] = out.size()
        return this
    }

    fun op(opcode: Int, a0: Arg = Arg.None, a1: Arg = Arg.None): BestAsm {
        out.write(opcode)
        out.write((a0.mode shl 4) or a1.mode)
        var labelPos = -1
        var label: String? = null
        for (a in listOf(a0, a1)) {
            if (a is Arg.Label) {
                labelPos = out.size()
                label = a.name
            }
            writeArg(a)
        }
        if (label != null) fixups.add(Fixup(labelPos, out.size(), label))
        return this
    }

    private fun le16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeArg(a: Arg) {
        when (a) {
            Arg.None -> {}
            is Arg.Reg -> out.write(a.code)
            is Arg.Imm8 -> out.write(a.v and 0xFF)
            is Arg.Imm16 -> le16(a.v)
            is Arg.Imm32 -> out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(a.v.toInt()).array())
            is Arg.Label -> out.write(ByteArray(4))
            is Arg.Str -> {
                le16(a.bytes.size)
                out.write(a.bytes)
            }
            is Arg.IdxImm -> {
                out.write(a.reg); le16(a.idx)
            }
            is Arg.IdxReg -> {
                out.write(a.reg); out.write(a.idxReg)
            }
            is Arg.IdxRegImm -> {
                out.write(a.reg); out.write(a.idxReg); le16(a.inc)
            }
            is Arg.IdxImmLenImm -> {
                out.write(a.reg); le16(a.idx); le16(a.len)
            }
            is Arg.IdxImmLenReg -> {
                out.write(a.reg); le16(a.idx); out.write(a.lenReg)
            }
            is Arg.IdxRegLenImm -> {
                out.write(a.reg); out.write(a.idxReg); le16(a.len)
            }
            is Arg.IdxRegLenReg -> {
                out.write(a.reg); out.write(a.idxReg); out.write(a.lenReg)
            }
        }
    }

    fun build(): ByteArray {
        val code = out.toByteArray()
        for (f in fixups) {
            val target = labels[f.label] ?: throw IllegalArgumentException("label ${f.label}")
            val rel = target - f.instrEnd
            ByteBuffer.wrap(code, f.position, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(rel)
        }
        return code
    }
}

/** Builds a minimal, valid EDIABAS object file (.prg) with jobs and optional tables. */
object MiniPrg {
    /** Opcode numbers (EdiabasNet.cs OcList). */
    object Op {
        const val MOVE = 0x00; const val CLEAR = 0x01; const val COMP = 0x02; const val SUBB = 0x03; const val ADDS = 0x04
        const val MULT = 0x05; const val DIVS = 0x06; const val AND = 0x07; const val OR = 0x08; const val XOR = 0x09
        const val NOT = 0x0A; const val JUMP = 0x0B; const val JC = 0x0E; const val JAE = 0x0F; const val JZ = 0x10
        const val JNZ = 0x11; const val JV = 0x12; const val JNV = 0x13; const val JMI = 0x14; const val JPL = 0x15
        const val CLRC = 0x16; const val SETC = 0x17; const val ASR = 0x18; const val LSL = 0x19; const val LSR = 0x1A
        const val ASL = 0x1B; const val NOP = 0x1C; const val EOJ = 0x1D; const val PUSH = 0x1E; const val POP = 0x1F
        const val SCMP = 0x20; const val SCAT = 0x21; const val SCUT = 0x22; const val SLEN = 0x23; const val SPASTE = 0x24
        const val SERASE = 0x25; const val XSEND = 0x2A; const val ERGB = 0x34; const val ERGW = 0x35; const val ERGD = 0x36
        const val ERGI = 0x37; const val ERGR = 0x38; const val ERGS = 0x39; const val A2FLT = 0x3A; const val FADD = 0x3B
        const val FSUB = 0x3C; const val FMUL = 0x3D; const val FDIV = 0x3E; const val ERGY = 0x3F; const val ENEWSET = 0x40
        const val ETAG = 0x41; const val GETTMR = 0x43; const val SETTMR = 0x44; const val SETT = 0x45; const val CLRT = 0x46
        const val JT = 0x47; const val JNT = 0x48; const val ADDC = 0x49; const val SUBC = 0x4A; const val BREAK = 0x4B
        const val CLRV = 0x4C; const val EERR = 0x4D; const val POPF = 0x4E; const val PUSHF = 0x4F; const val ATSP = 0x50
        const val SWAP = 0x51; const val SETSPC = 0x52; const val SREVRS = 0x53; const val STOKEN = 0x54; const val PARB = 0x55
        const val PARW = 0x56; const val PARL = 0x57; const val PARS = 0x58; const val JG = 0x5A; const val JGE = 0x5B
        const val JL = 0x5C; const val JLE = 0x5D; const val JA = 0x5E; const val JBE = 0x5F; const val A2FIX = 0x67
        const val FIX2FLT = 0x68; const val PARR = 0x69; const val TEST = 0x6A; const val JTSR = 0x0C; const val FIX2HEX = 0x79
        const val FIX2DEZ = 0x7A; const val TABSET = 0x7B; const val TABSEEK = 0x7C; const val TABGET = 0x7D; const val STRCAT = 0x7E
        const val PARY = 0x7F; const val PARN = 0x80; const val ERGC = 0x81; const val ERGL = 0x82; const val TABLINE = 0x83
        const val FLT2A = 0x87; const val SETFLT = 0x88; const val CFGIG = 0x89; const val CFGSG = 0x8A; const val CFGIS = 0x8B
        const val A2Y = 0x8C; const val HEX2Y = 0x8E; const val STRCMP = 0x8F; const val STRLEN = 0x90; const val Y2BCD = 0x91
        const val Y2HEX = 0x92; const val SHMSET = 0x93; const val SHMGET = 0x94; const val ERGSYSI = 0x95; const val FLT2FIX = 0x96
        const val TABSEEKU = 0x9A; const val FLT2Y4 = 0x9B; const val FLT2Y8 = 0x9C; const val Y42FLT = 0x9D; const val Y82FLT = 0x9E
        const val FCOMP = 0xA1; const val UFIX2DEZ = 0xAB; const val GENERR = 0xAC; const val SSIZE = 0xB5; const val TABCOLS = 0xB6
        const val TABROWS = 0xB7
    }

    class Table(val name: String, val header: List<String>, val rows: List<List<String>>)

    private fun xor(b: ByteArray): ByteArray = ByteArray(b.size) { (b[it].toInt() xor 0xF7).toByte() }

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    fun build(jobs: Map<String, ByteArray>, tables: List<Table> = emptyList(), arraySize: Int = 0): ByteArray {
        val header = ByteArray(0x100)
        "@EDIABAS OBJECT".toByteArray().copyInto(header)
        fun put(offset: Int, v: Int) = le32(v).copyInto(header, offset)
        put(0x10, 1) // ECU file
        put(0x18, arraySize)
        put(0x7C, -1) // uses
        put(0x90, -1) // descriptions
        put(0x94, -1) // version info

        val body = ByteArrayOutputStream()
        fun pos() = 0x100 + body.size()

        // tables
        if (tables.isEmpty()) {
            put(0x84, -1)
        } else {
            put(0x84, pos())
            body.write(xor(le32(tables.size)))
            val entriesPos = body.size()
            body.write(ByteArray(0x50 * tables.size))
            val entries = ArrayList<ByteArray>()
            for (t in tables) {
                val entry = ByteArray(0x50)
                Cp1252.encode(t.name).copyInto(entry)
                le32(pos()).copyInto(entry, 0x40)
                le32(t.header.size).copyInto(entry, 0x48)
                le32(t.rows.size).copyInto(entry, 0x4C)
                for (row in listOf(t.header) + t.rows) {
                    for (cell in row) body.write(xor(Cp1252.encode(cell) + byteArrayOf(0)))
                }
                entries.add(xor(entry))
            }
            val bytes = body.toByteArray()
            entries.forEachIndexed { i, e -> e.copyInto(bytes, entriesPos + i * 0x50) }
            body.reset()
            body.write(bytes)
        }

        // jobs
        val codeStart = pos() + 4 + 0x44 * jobs.size
        put(0x88, pos())
        body.write(le32(jobs.size)) // job count is stored in clear
        var address = codeStart
        for ((name, code) in jobs) {
            val entry = ByteArray(0x44)
            Cp1252.encode(name).copyInto(entry)
            le32(address).copyInto(entry, 0x40)
            body.write(xor(entry))
            address += code.size
        }
        for (code in jobs.values) body.write(xor(code))
        return header + body.toByteArray()
    }

    /** Writes [name].prg into [dir] and returns the file. */
    fun write(dir: File, name: String, jobs: Map<String, ByteArray>, tables: List<Table> = emptyList()): File {
        val file = File(dir, "$name.prg")
        file.writeBytes(build(jobs, tables))
        return file
    }

    /** INITIALISIERUNG job returning DONE=1. */
    fun initJob(): ByteArray = BestAsm()
        .op(Op.ERGB, Arg.str("DONE"), Arg.Imm8(1))
        .op(Op.EOJ)
        .build()
}
