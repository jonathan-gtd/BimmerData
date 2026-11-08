/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdOperations.cs (results, job parameters, configuration, errors and traps,
 * progress, shared memory, date/time, plugin stubs).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
@file:Suppress("UNUSED_PARAMETER")

package io.github.jonathangtd.bimmerdata.ediabas.ops

import io.github.jonathangtd.bimmerdata.ediabas.CallSource
import io.github.jonathangtd.bimmerdata.ediabas.Cp1252
import io.github.jonathangtd.bimmerdata.ediabas.EdFloatType
import io.github.jonathangtd.bimmerdata.ediabas.EdHelpers
import io.github.jonathangtd.bimmerdata.ediabas.EdLogLevel
import io.github.jonathangtd.bimmerdata.ediabas.EdValueType
import io.github.jonathangtd.bimmerdata.ediabas.EdiabasNet
import io.github.jonathangtd.bimmerdata.ediabas.ErrorCodes
import io.github.jonathangtd.bimmerdata.ediabas.OpAddrMode
import io.github.jonathangtd.bimmerdata.ediabas.OpCode
import io.github.jonathangtd.bimmerdata.ediabas.Operand
import io.github.jonathangtd.bimmerdata.ediabas.ResultData
import io.github.jonathangtd.bimmerdata.ediabas.ResultType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.IsoFields

// ---- results ----

internal fun opErgb(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeB, arg0.getStringData(), (arg1.getValueData(1u) and 0xFFu).toLong()))
}

internal fun opErgw(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeW, arg0.getStringData(), (arg1.getValueData(2u) and 0xFFFFu).toLong()))
}

internal fun opErgd(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeD, arg0.getStringData(), arg1.getValueData(4u).toLong()))
}

internal fun opErgi(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeI, arg0.getStringData(), arg1.getValueData(2u).toShort().toLong()))
}

internal fun opErgr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeR, arg0.getStringData(), arg1.getFloatData()))
}

internal fun opErgs(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeS, arg0.getStringData(), arg1.getStringData()))
}

internal fun opErgy(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeY, arg0.getStringData(), arg1.getArrayData()))
}

internal fun opErgc(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeC, arg0.getStringData(), arg1.getValueData(1u).toByte().toLong()))
}

internal fun opErgl(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setResultData(ResultData(ResultType.TypeL, arg0.getStringData(), arg1.getValueData(4u).toInt().toLong()))
}

// BEST2: doNewInit
internal fun opErgsysi(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val data = arg0.getStringData()
    val dataValue = arg1.getValueData(2u)
    if (data == "!INITIALISIERUNG") {
        if (dataValue != 0u) {
            ediabas.requestInit = true
        }
    } else {
        ediabas.setSysResultData(ResultData(ResultType.TypeI, data, dataValue.toLong()))
    }
}

// BEST2: new_set_of_results
internal fun opEnewset(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val temp = ediabas.resultSetsTemp
    if (temp != null && ediabas.resultDict.isNotEmpty()) {
        temp.add(LinkedHashMap(ediabas.resultDict))
        ediabas.resultDict.clear()
    }
}

internal fun opEoj(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (arg0.addrMode != OpAddrMode.None) {
        ediabas.resultJobStatus = arg0.getStringData()
    }
    ediabas.jobEnd = true
}

// ---- errors and traps ----

// BEST2: make_error (execute)
internal fun opEerr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    if (ediabas.errorTrapBitNr >= 0) {
        for ((key, bit) in EdiabasNet.trapBitDict) {
            if (bit.toLong() == ediabas.errorTrapBitNr.toLong()) {
                ediabas.raiseError(key)
            }
        }
        ediabas.raiseError(ErrorCodes.EDIABAS_BIP_0000)
    }
}

// BEST2: userbreak
internal fun opBreak(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.setError(ErrorCodes.EDIABAS_BIP_0008)
}

// BEST2: generateRunError
internal fun opGenerr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val value = arg0.getValueData()
    // enum ErrorCodes : uint, compared unsigned
    if (value < ErrorCodes.EDIABAS_RUN_0000.code.toUInt() || value > ErrorCodes.EDIABAS_ERROR_LAST.code.toUInt()) {
        ediabas.raiseError(ErrorCodes.EDIABAS_BIP_0001)
    } else {
        ediabas.raiseError(ErrorCodes(value.toInt()))
    }
}

// BEST2: get_trap_mask
internal fun opGettmr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpGettmr: Invalid type")
    arg0.setRawData(ediabas.errorTrapMask)
    ediabas.flags.updateFlags(arg0.getValueData(), arg0.getDataLen())
}

// BEST2: set_trap_mask
internal fun opSettmr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.errorTrapMask = arg0.getValueData()
}

// BEST2: make_error
internal fun opSett(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    var error = arg0.getValueData()
    if (error == 0u) {
        error = 0x40000000u
    }
    ediabas.errorTrapBitNr = error.toInt()
}

// BEST2: clear_error
internal fun opClrt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.errorTrapBitNr = -1
}

// ---- job parameters ----

/** parb, parw and parl share OpParl in EdOperations.cs. */
internal fun opParl(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpParl Invalid type")
    var result: EdValueType = 0u
    ediabas.flags.zero = true
    ediabas.flags.carry = false
    ediabas.flags.sign = false
    ediabas.flags.overflow = false
    var pos = arg1.getValueData()
    pos--

    val argStrings = ediabas.getActiveArgStrings()
    if (pos.toLong() < argStrings.size) {
        val argString = argStrings[pos.toInt()]
        if (argString.isNotEmpty()) {
            result = EdiabasNet.stringToValue(argString).toUInt()
            ediabas.flags.zero = false
        }
    }
    arg0.setRawData(result)
}

// BEST2: parcount
internal fun opParn(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpParn: Invalid type")
    val argStrings = ediabas.getActiveArgStrings()
    arg0.setRawData(argStrings.size.toUInt())
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(arg0.getValueData(), arg0.getDataLen())
}

internal fun opParr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpParr: Invalid type")
    var result: EdFloatType = 0.0
    ediabas.flags.zero = true
    ediabas.flags.carry = false
    ediabas.flags.sign = false
    ediabas.flags.overflow = false
    var pos = arg1.getValueData()
    pos--

    val argStrings = ediabas.getActiveArgStrings()
    if (pos.toLong() < argStrings.size) {
        val argString = argStrings[pos.toInt()]
        if (argString.isNotEmpty()) {
            result = EdiabasNet.stringToFloat(argString)
            ediabas.flags.zero = false
        }
    }
    arg0.setRawData(result)
}

internal fun opPars(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpPars: Invalid type")
    var result = ""
    ediabas.flags.zero = true
    var pos = arg1.getValueData()
    pos--

    val argStrings = ediabas.getActiveArgStrings()
    if (pos.toLong() < argStrings.size) {
        val argString = argStrings[pos.toInt()]
        if (argString.isNotEmpty()) {
            result = argString
            ediabas.flags.zero = false
        }
    }
    arg0.setStringData(result)
}

internal fun opPary(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpPary: Invalid type")
    var result = ByteArray(0)
    ediabas.flags.zero = true
    val argBin = ediabas.getActiveArgBinary()
    if (argBin.isNotEmpty()) {
        result = argBin
        ediabas.flags.zero = false
    }
    arg0.setArrayData(result)
}

// ---- configuration ----

// BEST2: getCfgInt
internal fun opCfgig(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpCfgig: Invalid type")
    val value = ediabas.getConfigProperty(arg1.getStringData(), CallSource.EdiabasOperation)
    if (value != null) {
        arg0.setRawData(EdiabasNet.stringToValue(value).toUInt())
    }
}

// BEST2: getCfgString
internal fun opCfgsg(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpCfgsg: Invalid type")
    val value = ediabas.getConfigProperty(arg1.getStringData(), CallSource.EdiabasOperation)
    if (value != null) {
        arg0.setArrayData(Cp1252.encode(value))
    }
}

internal fun opCfgis(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val valueString = arg1.getValueData().toString()
    ediabas.setConfigProperty(arg0.getStringData(), valueString)
}

// ---- progress ----

// BEST2: incProgressPos
internal fun opIincpos(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val incVal = arg0.getValueData().toLong()
    synchronized(ediabas.apiLock) {
        var newValue = if (ediabas.infoProgressPos < 0) incVal else ediabas.infoProgressPos + incVal
        if (newValue > ediabas.infoProgressRange) {
            newValue = ediabas.infoProgressRange
        }
        ediabas.infoProgressPos = newValue
    }
    ediabas.jobProgressInform()
}

// BEST2: setProgressRange
internal fun opIrange(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val progressRange = arg0.getValueData()
    synchronized(ediabas.apiLock) {
        ediabas.infoProgressPos = -1
        ediabas.infoProgressRange = progressRange.toLong()
    }
    ediabas.jobProgressInform()
}

// BEST2: updateInfo
internal fun opIupdate(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val progressText = arg0.getStringData()
    synchronized(ediabas.apiLock) {
        ediabas._infoProgressText = progressText
    }
    ediabas.jobProgressInform()
}

// ---- shared memory ----

// BEST2: shdataget
internal fun opShmget(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpShmget: Invalid type")
    val key = EdHelpers.upperInv(arg1.getStringData())
    var data: ByteArray?
    synchronized(EdiabasNet.sharedDataLock) {
        data = EdiabasNet.sharedDataDict[key]
        if (data != null) {
            ediabas.flags.carry = false
        } else {
            ediabas.flags.carry = true
            data = ByteArray(0)
        }
    }
    arg0.setArrayData(data!!)
}

// BEST2: shdataset
internal fun opShmset(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    val key = EdHelpers.upperInv(arg0.getStringData())
    synchronized(EdiabasNet.sharedDataLock) {
        EdiabasNet.sharedDataDict[key] = arg1.getArrayData()
    }
}

// ---- date and time ----

/** CultureInfo.InvariantCulture.Calendar.GetWeekOfYear(date, FirstFourDayWeek, Monday). */
internal fun netWeekOfYear(date: LocalDate): Int {
    val isoWeekYear = date.get(IsoFields.WEEK_BASED_YEAR)
    if (isoWeekYear > date.year) {
        // .NET does not roll the last December days over to week 1 of the next year
        return date.minusDays(7).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + 1
    }
    return date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

// BEST2: getdate
internal fun opDate(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpDate: Invalid type")
    val saveNow = LocalDateTime.now()
    val resultArray = ByteArray(5)
    resultArray[0] = saveNow.dayOfMonth.toByte()
    resultArray[1] = saveNow.monthValue.toByte()
    resultArray[2] = (saveNow.year % 100).toByte()
    resultArray[3] = netWeekOfYear(saveNow.toLocalDate()).toByte()
    resultArray[4] = saveNow.dayOfWeek.value.toByte() // Monday = 1 .. Sunday = 7
    arg0.setArrayData(resultArray)
}

// BEST2: gettime
internal fun opTime(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpTime: Invalid type")
    val saveNow = LocalDateTime.now()
    val resultArray = ByteArray(3)
    resultArray[0] = saveNow.hour.toByte()
    resultArray[1] = saveNow.minute.toByte()
    resultArray[2] = saveNow.second.toByte()
    arg0.setArrayData(resultArray)
}

/** DateTime.Now.Ticks / 10000: local time in ms since 0001-01-01. */
private const val DOTNET_EPOCH_OFFSET_MS = 62135596800000L

internal fun opTicks(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    requireRegister(arg0, "OpTicks: Invalid type")
    val nowUtc = System.currentTimeMillis()
    val offset = ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(nowUtc)).totalSeconds * 1000L
    val value: EdValueType = (nowUtc + offset + DOTNET_EPOCH_OFFSET_MS).toUInt()
    arg0.setRawData(value)
}

private fun threadSleep(ms: Int) {
    if (ms < 0) throw IllegalArgumentException("Thread.Sleep: negative timeout $ms")
    Thread.sleep(ms.toLong())
}

// BEST2: wait
internal fun opWait(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    threadSleep((arg0.getValueData() * 1000u).toInt())
}

// BEST2: waitex
internal fun opWaitex(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    threadSleep(arg0.getValueData().toInt())
}

// ---- plugins (ignored, as in EdiabasLib) ----

// plugin jump
internal fun opPjtsr(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPjtsr: Ignoring")
}

// link plugin
internal fun opPlink(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPlink: Ignoring plugin {0}", arg0.getStringData())
}

// link plugin with version
internal fun opPlinkv(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPlinkv: Ignoring plugin {0} version {1:X04}", arg0.getStringData(), arg1.getValueData())
}

// plugin pop value
internal fun opPpop(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpop: Ignoring")
    val value: EdValueType = 0u
    arg0.setRawData(value)
    ediabas.flags.overflow = false
    ediabas.flags.updateFlags(value, arg0.getDataLen())
}

// plugin pop float
internal fun opPpopflt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpopflt: Ignoring")
    val value: EdFloatType = 0.0
    arg0.setRawData(value)
    ediabas.flags.overflow = false
}

// plugin pop string
internal fun opPpopy(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpopy: Ignoring")
    arg0.setArrayData(ByteArray(0))
}

// plugin push value
internal fun opPpush(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpush: Ignoring")
}

// plugin push float
internal fun opPpushflt(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpushflt: Ignoring")
}

// plugin push string
internal fun opPpushy(ediabas: EdiabasNet, oc: OpCode, arg0: Operand, arg1: Operand) {
    ediabas.logFormat(EdLogLevel.Info, "OpPpushy: Ignoring")
}
