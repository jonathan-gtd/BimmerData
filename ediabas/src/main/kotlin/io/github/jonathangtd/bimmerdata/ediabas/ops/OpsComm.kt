/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (communication opcodes x*).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.EdInterfaceBase
import io.github.jonathangtd.bimmerdata.ediabas.EdValueType
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand

/** Returns the interface, or sets EDIABAS_IFH_0056 (which normally raises) and returns null. */
private fun interfaceOrError(ediabas: EdiabasNet, requireConnected: Boolean): EdInterfaceBase? {
    val interfaceClass = ediabas.edInterfaceClass
    if (interfaceClass == null || (requireConnected && !interfaceClass.connected)) {
        ediabas.setError(ErrorCodes.EDIABAS_IFH_0056)
        return null
    }
    return interfaceClass
}

// BEST2: set_answer_length
internal fun opXawlen(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val dataArray = arg0.getArrayData()
    var length = dataArray.size
    if ((length and 0x01) != 0x00) {
        throw IllegalArgumentException("OpXawlen: Invalid data length")
    }
    length = length shr 1
    val answerArray = ShortArray(length)
    for (i in 0 until length) {
        val offset = i shl 1
        answerArray[i] = ((dataArray[offset + 0].toInt() and 0xFF) or ((dataArray[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }
    interfaceClass.commAnswerLen = answerArray
}

// BEST2: get_battery_voltage
internal fun opXbatt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXbat: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val voltage = interfaceClass.batteryVoltage
    if (voltage != Long.MIN_VALUE) {
        arg0.setRawData(voltage.toUInt())
    }
}

// BEST2: ifboot
internal fun opXboot(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    interfaceClass.interfaceBoot()
}

// BEST2: open_communication
internal fun opXconnect(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = ediabas.edInterfaceClass ?: throw IllegalArgumentException("OpXconnect: No communication class present")
    interfaceClass.interfaceConnect()
}

// BEST2: close_communication
internal fun opXhangup(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = ediabas.edInterfaceClass ?: throw IllegalArgumentException("OpXhangup: No communication class present")
    interfaceClass.interfaceDisconnect()
}

// BEST2: ifgetport
internal fun opXgetport(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXgetport: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val portIndex = arg1.getValueData() and 0xFFu
    val portValue = interfaceClass.getPort(portIndex)
    arg0.setRawData(portValue.toUInt())
}

// BEST2: get_ignition_voltage
internal fun opXignit(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXignit: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val voltage = interfaceClass.ignitionVoltage
    if (voltage != Long.MIN_VALUE) {
        arg0.setRawData(voltage.toUInt())
    }
}

// BEST2: recv_keybytes
internal fun opXkeyb(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXkeyb: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val keyBytes = interfaceClass.keyBytes
    if (keyBytes != null) {
        arg0.setRawData(keyBytes)
    }
}

// BEST2: ifloopt
internal fun opXloopt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXloopt: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    arg0.setRawData(interfaceClass.loopTest)
}

// BEST2: set_program_voltage
internal fun opXprog(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    interfaceClass.setProgramVoltage(arg0.getValueData())
}

// BEST2: ifrawmode
internal fun opXraw(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXraw: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val request = arg1.getArrayData()
    val response = interfaceClass.rawData(request)
    if (response != null) {
        arg0.setRawData(response)
    }
}

// BEST2: set_repeat_counter
internal fun opXreps(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    interfaceClass.commRepeats = arg0.getValueData()
}

// BEST2: recv_frequent
internal fun opXrequf(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXrequf: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val response = interfaceClass.receiveFrequent()
    if (response != null) {
        arg0.setRawData(response)
    }
}

// BEST2: ifreset
internal fun opXreset(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    interfaceClass.interfaceReset()
}

// BEST2: send_and_receive
internal fun opXsend(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXsend: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val request = arg1.getArrayData()
    val response = interfaceClass.transmitData(request)
    if (response != null) {
        arg0.setRawData(response)
    }
}

// BEST2: send_frequent
internal fun opXsendf(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val request = arg0.getArrayData()
    interfaceClass.transmitFrequent(request)
}

// BEST2: set_communication_pars
internal fun opXsetpar(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    val dataArray = arg0.getArrayData()
    var length = dataArray.size
    var dataTypeLen = 0
    if (length >= 2) {
        when (dataArray[1].toInt() and 0xFF) {
            0x00 -> dataTypeLen = 2 // integer
            0x01 -> dataTypeLen = 4 // long
            0xFF -> dataTypeLen = 1 // byte
        }
    }

    var parsArray = UIntArray(0)
    if (dataTypeLen > 0 && (length % dataTypeLen == 0)) {
        length /= dataTypeLen
        parsArray = UIntArray(length)
        for (i in 0 until length) {
            val offset = i * dataTypeLen
            var value: EdValueType = (dataArray[offset + 0].toInt() and 0xFF).toUInt()
            if (dataTypeLen >= 2) {
                value = value or ((dataArray[offset + 1].toInt() and 0xFF).toUInt() shl 8)
            }
            if (dataTypeLen >= 4) {
                value = value or ((dataArray[offset + 2].toInt() and 0xFF).toUInt() shl 16) or
                    ((dataArray[offset + 3].toInt() and 0xFF).toUInt() shl 24)
            }
            parsArray[i] = value
        }
    }
    interfaceClass.commParameter = parsArray
}

// BEST2: ifsetport
internal fun opXsetport(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val portData = arg0.getArrayData()
    var portIndex = 0u
    if (portData.isNotEmpty()) {
        portIndex = (portData[0].toInt() and 0xFF).toUInt()
    }
    val portValue = arg1.getValueData()
    interfaceClass.setPort(portIndex, portValue)
}

// BEST2: ifsireset
internal fun opXsireset(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    interfaceClass.switchSiRelais(arg0.getValueData())
}

// BEST2: ifrequeststate
internal fun opXstate(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXstate: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, false) ?: return
    val state = interfaceClass.state
    if (state != null) {
        arg0.setRawData(state)
    }
}

// BEST2: stop_frequent
internal fun opXstopf(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    interfaceClass.stopFrequent()
}

// BEST2: iftype
internal fun opXtype(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXtype: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    arg0.setStringData(interfaceClass.interfaceType)
}

// BEST2: ifvers
internal fun opXvers(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpXvers: Invalid type")
    val interfaceClass = interfaceOrError(ediabas, true) ?: return
    arg0.setRawData(interfaceClass.interfaceVersion)
}
