/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (jumps, trap jumps, etag).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.EdHelpers
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.OpAddrMode
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand

// jump above
internal fun opJa(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.carry && !ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump above equal (identical to jnc)
internal fun opJae(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.carry) ediabas.pcCounter = arg0.getValueData()
}

// jump below or equal
internal fun opJbe(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.carry || ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump carry (identical to jb)
internal fun opJc(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.carry) ediabas.pcCounter = arg0.getValueData()
}

// jump greater
internal fun opJg(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.sign == ediabas.flags.overflow && !ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump greater or equal
internal fun opJge(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.zero || ediabas.flags.sign == ediabas.flags.overflow) ediabas.pcCounter = arg0.getValueData()
}

// jump less
internal fun opJl(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.zero && ediabas.flags.sign != ediabas.flags.overflow) ediabas.pcCounter = arg0.getValueData()
}

// jump less or equal
internal fun opJle(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.sign != ediabas.flags.overflow || ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump minus
internal fun opJmi(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.sign) ediabas.pcCounter = arg0.getValueData()
}

// jump plus
internal fun opJpl(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.sign) ediabas.pcCounter = arg0.getValueData()
}

// jump not overflow
internal fun opJnv(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.overflow) ediabas.pcCounter = arg0.getValueData()
}

// jump overflow
internal fun opJv(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.overflow) ediabas.pcCounter = arg0.getValueData()
}

// jump not zero
internal fun opJnz(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (!ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump zero
internal fun opJz(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.flags.zero) ediabas.pcCounter = arg0.getValueData()
}

// jump
internal fun opJump(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.pcCounter = arg0.getValueData()
}

// jump trap
internal fun opJt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    var errorDetected = false
    if (arg1.addrMode != OpAddrMode.None) {
        val testBit = arg1.getValueData(1u)
        if (testBit > 0u) {
            if (ediabas.errorTrapBitNr == testBit.toInt()) errorDetected = true
            if (ediabas.errorTrapBitNr == 0 && testBit == 32u) errorDetected = true
        } else {
            if (ediabas.errorTrapBitNr >= 0x40000000) errorDetected = true
        }
    } else {
        if (ediabas.errorTrapBitNr >= 0) errorDetected = true
    }
    if (errorDetected) {
        ediabas.pcCounter = arg0.getValueData()
    }
}

// jump not trap
internal fun opJnt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    var errorDetected = false
    if (arg1.addrMode != OpAddrMode.None) {
        val testBit = arg1.getValueData(1u)
        if (testBit > 0u) {
            if (ediabas.errorTrapBitNr == testBit.toInt()) errorDetected = true
            if (ediabas.errorTrapBitNr == 0 && testBit == 32u) errorDetected = true
        } else {
            if (ediabas.errorTrapBitNr >= 0x40000000) errorDetected = true
        }
    } else {
        // Ediabas failure, should be identical to OpJt
        // incorrect behaviour if no argument is specified
        if (ediabas.errorTrapBitNr >= 0x40000000) errorDetected = true
    }
    if (!errorDetected) {
        ediabas.pcCounter = arg0.getValueData()
    }
}

// jump if result tag is not existing
internal fun opEtag(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    synchronized(ediabas.apiLock) {
        if (ediabas._resultsRequestDict.isNotEmpty()) {
            if (!ediabas._resultsRequestDict.containsKey(EdHelpers.upperInv(arg1.getStringData()))) {
                ediabas.pcCounter = arg0.getValueData()
            }
        }
    }
}
