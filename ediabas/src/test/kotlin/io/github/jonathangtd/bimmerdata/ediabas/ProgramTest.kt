/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.Arg.Companion.reg
import io.github.jonathangtd.bimmerdata.ediabas.Arg.Companion.str
import io.github.jonathangtd.bimmerdata.ediabas.MiniPrg.Op
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Whole jobs assembled into synthetic object files: exercises the byte code decoder (all operand
 * address modes, XOR 0xF7), relative jumps, result sets, tables and the ExecuteJob API.
 */
class ProgramTest {

    @TempDir
    lateinit var dir: File

    private val table = MiniPrg.Table(
        "TAB",
        listOf("NAME", "NUM", "TEXT"),
        listOf(
            listOf("alpha", "0x10", "first"),
            listOf("beta", "0x20", "second"),
            listOf("XY", "0xFFFF", "unknown"),
        ),
    )

    private fun run(job: BestAsm, args: String = "", requests: String = "", config: (EdiabasNet) -> Unit = {}): List<Map<String, ResultData>> {
        MiniPrg.write(dir, "test", mapOf("INITIALISIERUNG" to MiniPrg.initJob(), "TEST" to job.build()), listOf(table))
        EdiabasNet("EcuPath=${dir.path}").use { ed ->
            config(ed)
            ed.sgbdFileName = "test.prg"
            ed.argString = args
            ed.resultsRequests = requests
            ed.executeJob("TEST")
            return ed.resultSets!!
        }
    }

    @Test
    fun `loop with backward relative jump and results`() {
        val sets = run(
            BestAsm()
                .op(Op.MOVE, reg("L0"), Arg.Imm32(5))
                .op(Op.CLEAR, reg("L1"))
                .label("loop")
                .op(Op.ADDS, reg("L1"), reg("L0"))
                .op(Op.SUBB, reg("L0"), Arg.Imm8(1))
                .op(Op.JNZ, Arg.Label("loop"))
                .op(Op.ERGL, str("SUM"), reg("L1"))
                .op(Op.EOJ, str("DONE_OK")),
        )
        assertEquals(15L, sets[1]["SUM"]!!.opData)
        assertEquals("DONE_OK", sets[0]["JOBSTATUS"]!!.opData)
        assertEquals("TEST", sets[0]["JOBNAME"]!!.opData)
        assertEquals("TEST", sets[0]["VARIANTE"]!!.opData)
    }

    @Test
    fun `signed and unsigned conditional jumps`() {
        // compare -1 with 1: signed less, unsigned above
        val job = BestAsm()
            .op(Op.MOVE, reg("L0"), Arg.Imm32(0xFFFFFFFF))
            .op(Op.COMP, reg("L0"), Arg.Imm32(1))
            .op(Op.JL, Arg.Label("less")).op(Op.ERGB, str("JL"), Arg.Imm8(0)).op(Op.JUMP, Arg.Label("c2"))
            .label("less").op(Op.ERGB, str("JL"), Arg.Imm8(1))
            .label("c2").op(Op.COMP, reg("L0"), Arg.Imm32(1))
            .op(Op.JA, Arg.Label("above")).op(Op.ERGB, str("JA"), Arg.Imm8(0)).op(Op.JUMP, Arg.Label("c3"))
            .label("above").op(Op.ERGB, str("JA"), Arg.Imm8(1))
            .label("c3").op(Op.COMP, reg("L0"), Arg.Imm32(1))
            .op(Op.JG, Arg.Label("greater")).op(Op.ERGB, str("JG"), Arg.Imm8(0)).op(Op.JUMP, Arg.Label("c4"))
            .label("greater").op(Op.ERGB, str("JG"), Arg.Imm8(1))
            .label("c4").op(Op.COMP, reg("L0"), Arg.Imm32(1))
            .op(Op.JC, Arg.Label("carry")).op(Op.ERGB, str("JC"), Arg.Imm8(0)).op(Op.JUMP, Arg.Label("end"))
            .label("carry").op(Op.ERGB, str("JC"), Arg.Imm8(1))
            .label("end").op(Op.EOJ)
        val r = run(job)[1]
        assertEquals(1L, r["JL"]!!.opData)
        assertEquals(1L, r["JA"]!!.opData) // unsigned 0xFFFFFFFF > 1
        assertEquals(0L, r["JG"]!!.opData)
        assertEquals(0L, r["JC"]!!.opData)
    }

    @Test
    fun `all operand address modes decode`() {
        val job = BestAsm()
            .op(Op.MOVE, reg("S0"), str("ABCDEFG"))
            .op(Op.MOVE, reg("I4"), Arg.Imm16(0x1234)) // I0 would alias B0/B1
            .op(Op.MOVE, reg("B1"), Arg.Imm8(2))
            .op(Op.MOVE, reg("B2"), Arg.Imm8(3))
            .op(Op.MOVE, reg("B0"), Arg.IdxImm(Arg.regCode("S0"), 1)) // 'B'
            .op(Op.ERGB, str("IDXIMM"), reg("B0"))
            .op(Op.MOVE, reg("B0"), Arg.IdxReg(Arg.regCode("S0"), Arg.regCode("B1"))) // 'C'
            .op(Op.ERGB, str("IDXREG"), reg("B0"))
            .op(Op.MOVE, reg("B0"), Arg.IdxRegImm(Arg.regCode("S0"), Arg.regCode("B1"), 2)) // 'E'
            .op(Op.ERGB, str("IDXREGIMM"), reg("B0"))
            .op(Op.MOVE, reg("S1"), Arg.IdxImmLenImm(Arg.regCode("S0"), 1, 2))
            .op(Op.ERGY, str("IDXIMMLENIMM"), reg("S1"))
            .op(Op.MOVE, reg("S1"), Arg.IdxImmLenReg(Arg.regCode("S0"), 2, Arg.regCode("B2")))
            .op(Op.ERGY, str("IDXIMMLENREG"), reg("S1"))
            .op(Op.MOVE, reg("S1"), Arg.IdxRegLenImm(Arg.regCode("S0"), Arg.regCode("B1"), 4))
            .op(Op.ERGY, str("IDXREGLENIMM"), reg("S1"))
            .op(Op.MOVE, reg("S1"), Arg.IdxRegLenReg(Arg.regCode("S0"), Arg.regCode("B1"), Arg.regCode("B2")))
            .op(Op.ERGY, str("IDXREGLENREG"), reg("S1"))
            .op(Op.ERGW, str("IMM16"), reg("I4"))
            .op(Op.ERGD, str("IMM32"), Arg.Imm32(0xDEADBEEF))
            .op(Op.MOVE, reg("A3"), Arg.Imm8(0x5A))
            .op(Op.ERGB, str("HIGHREG"), reg("A3"))
            .op(Op.EOJ)
        val r = run(job)[1]
        fun y(name: String) = TestEcu.hex(r[name]!!.opData as ByteArray)
        assertEquals('B'.code.toLong(), r["IDXIMM"]!!.opData)
        assertEquals('C'.code.toLong(), r["IDXREG"]!!.opData)
        assertEquals('E'.code.toLong(), r["IDXREGIMM"]!!.opData)
        assertEquals("42 43", y("IDXIMMLENIMM"))
        assertEquals("43 44 45", y("IDXIMMLENREG"))
        assertEquals("43 44 45 46", y("IDXREGLENIMM"))
        // OpMove into a longer string register only overwrites the start: the 4th byte of the
        // previous value stays (EdOperations.cs behaviour)
        assertEquals("43 44 45 46", y("IDXREGLENREG"))
        assertEquals(0x1234L, r["IMM16"]!!.opData)
        assertEquals(0xDEADBEEFL, r["IMM32"]!!.opData)
        assertEquals(0x5AL, r["HIGHREG"]!!.opData)
    }

    @Test
    fun `result sets, etag filter and arguments`() {
        val job = BestAsm()
            .op(Op.ETAG, Arg.Label("skipA"), str("A"))
            .op(Op.ERGB, str("A"), Arg.Imm8(1))
            .label("skipA")
            .op(Op.ETAG, Arg.Label("skipB"), str("B"))
            .op(Op.ERGB, str("B"), Arg.Imm8(2))
            .label("skipB")
            .op(Op.ENEWSET)
            .op(Op.PARS, reg("S0"), Arg.Imm8(1))
            .op(Op.ERGS, str("ARG1"), reg("S0"))
            .op(Op.EOJ)
        val all = run(job, args = "hello;world")
        assertEquals(3, all.size)
        assertEquals(2L, all[0]["SAETZE"]!!.opData)
        assertEquals(1L, all[1]["A"]!!.opData)
        assertEquals(2L, all[1]["B"]!!.opData)
        assertEquals("hello", all[2]["ARG1"]!!.opData)

        val filtered = run(job, args = "x", requests = "b;arg1")
        assertNull(filtered[1]["A"])
        assertEquals(2L, filtered[1]["B"]!!.opData)
    }

    @Test
    fun `table access`() {
        val job = BestAsm()
            .op(Op.TABSET, str("tab"))
            .op(Op.TABCOLS, reg("L0")).op(Op.ERGL, str("COLS"), reg("L0"))
            .op(Op.TABROWS, reg("L0")).op(Op.ERGL, str("ROWS"), reg("L0"))
            .op(Op.TABSEEK, str("NAME"), str("BETA"))
            .op(Op.TABGET, reg("S0"), str("TEXT")).op(Op.ERGS, str("SEEK"), reg("S0"))
            .op(Op.TABSEEKU, str("NUM"), Arg.Imm8(0x10))
            .op(Op.TABGET, reg("S0"), str("text")).op(Op.ERGS, str("SEEKU"), reg("S0"))
            .op(Op.TABSEEK, str("NAME"), str("gamma"))
            .op(Op.JNZ, Arg.Label("found"))
            .op(Op.TABGET, reg("S0"), str("TEXT")).op(Op.ERGS, str("DEFAULT"), reg("S0"))
            .label("found")
            .op(Op.TABLINE, Arg.Imm8(0))
            .op(Op.TABGET, reg("S0"), str("NAME")).op(Op.ERGS, str("LINE0"), reg("S0"))
            .op(Op.EOJ)
        val r = run(job)[1]
        assertEquals(3L, r["COLS"]!!.opData)
        assertEquals(4L, r["ROWS"]!!.opData) // including header
        assertEquals("second", r["SEEK"]!!.opData)
        assertEquals("first", r["SEEKU"]!!.opData)
        assertEquals("unknown", r["DEFAULT"]!!.opData) // not found selects the last line
        assertEquals("alpha", r["LINE0"]!!.opData)
    }

    @Test
    fun `missing table raises BIP-0010 unless trapped`() {
        val raising = BestAsm().op(Op.TABSET, str("NOPE")).op(Op.EOJ)
        val ex = assertThrows(EdiabasNetException::class.java) { run(raising) }
        assertEquals(ErrorCodes.EDIABAS_BIP_0010, ex.errorCode)

        val trapped = BestAsm()
            .op(Op.SETTMR, Arg.Imm32(1L shl 10))
            .op(Op.TABSET, str("NOPE"))
            .op(Op.JT, Arg.Label("err"), Arg.Imm8(10))
            .op(Op.ERGS, str("STATUS"), str("OKAY")).op(Op.EOJ)
            .label("err").op(Op.ERGS, str("STATUS"), str("ERROR_TABLE")).op(Op.EOJ)
        assertEquals("ERROR_TABLE", run(trapped)[1]["STATUS"]!!.opData)
    }

    @Test
    fun `unimplemented opcode aborts the job with an exception`() {
        val job = BestAsm().op(Op.JTSR, Arg.Imm32(0)).op(Op.EOJ)
        val ex = assertThrows(RuntimeException::class.java) { run(job) }
        assertFalse(ex is EdiabasNetException)
        assertTrue(ex.cause!!.message!!.contains("jtsr"), ex.cause!!.message)
    }

    @Test
    fun `virtual jobs on the synthetic file`() {
        MiniPrg.write(dir, "test", mapOf("INITIALISIERUNG" to MiniPrg.initJob(), "TEST" to BestAsm().op(Op.EOJ).build()), listOf(table))
        EdiabasNet("EcuPath=${dir.path}").use { ed ->
            ed.sgbdFileName = "TEST.PRG"
            ed.executeJob("_JOBS") // runs INITIALISIERUNG first (no interface needed here)
            assertEquals(listOf("INITIALISIERUNG", "TEST"), ed.resultSets!!.drop(1).map { it["JOBNAME"]!!.opData })
            ed.argString = "TAB"
            ed.executeJob("_TABLE")
            assertEquals(listOf("NAME", "alpha", "beta", "XY"), ed.resultSets!!.drop(1).map { it["COLUMN0"]!!.opData })
            assertEquals(4, ed.getTableLines("tab")!!.size)
            assertEquals(listOf("first", "second", "unknown"), ed.getTableColumn(ed.getTableLines("TAB"), "text"))
        }
    }
}
