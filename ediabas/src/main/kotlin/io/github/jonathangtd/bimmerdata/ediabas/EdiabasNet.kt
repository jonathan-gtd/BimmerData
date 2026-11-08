/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

import io.github.jonathangtd.bimmerdata.ediabas.ops.OcList
import java.io.Closeable
import java.io.EOFException

/**
 * EDIABAS job interpreter (EdiabasNet.cs). Loads an ECU description (.PRG) or group file (.GRP),
 * executes BEST/2 byte code jobs and virtual jobs, and collects the result sets.
 *
 * Not ported (see README of the module / commit notes): EDIABAS.INI and EdiabasLib.config files
 * (configuration is a string "key=value;..." or [setConfigProperty]), trace files (replaced by
 * [logHook]), file name encoding, resource assemblies, simulation files, Windows specific code.
 *
 * Kotlin specifics: C# `EdValueType` (UInt32) is [UInt] everywhere, so unchecked uint arithmetic,
 * shifts and comparisons behave the same; C# Int64 results are [Long]. Members keep the C# names
 * (camel-cased); private C# fields whose name collides with a public property keep the `_` prefix.
 */
class EdiabasNet @JvmOverloads constructor(config: String? = null) : Closeable {

    // ---- nested info classes (EdiabasNet.cs: VariantInfo, ArgInfo, VJobInfo, JobInfo, ...) ----

    internal class VariantInfo(val variantName: String, val familyName: String)

    internal class ArgInfo {
        var binData: ByteArray? = null
            set(value) {
                field = value
                stringList = null
            }
        var stringList: MutableList<String>? = null
    }

    internal class VJobInfo(val jobName: String, val jobDelegate: (EdiabasNet, MutableList<MutableMap<String, ResultData>>) -> Unit)

    internal class JobInfo(val jobName: String, val jobOffset: UInt, val jobSize: UInt, val arraySize: UInt, val usesInfo: UsesInfo?)

    internal class JobInfos(val jobInfoArray: Array<JobInfo>, val jobNameDict: MutableMap<String, UInt>)

    internal class UsesInfo(val name: String)

    internal class UsesInfos(val usesInfoArray: Array<UsesInfo>)

    internal class VersionInfos {
        var bipVersion: Long = 0
        var author: String = ""
        var from: String = ""
        var revision: String = ""
        var packageVersion: Long = 0
    }

    internal class DescriptionInfos {
        var globalComments: MutableList<String>? = null
        var jobComments: MutableMap<String, MutableList<String>> = LinkedHashMap()
    }

    internal class TableInfo(val name: String, val tableOffset: UInt, val tableColumnOffset: UInt, val columns: EdValueType, val rows: EdValueType) {
        var columnNameDict: MutableMap<String, UInt>? = null
        var seekColumnStringDicts: Array<MutableMap<String, UInt>?>? = null
        var seekColumnValueDicts: Array<MutableMap<EdValueType, UInt>?>? = null
        var tableEntries: Array<UIntArray>? = null
    }

    internal class TableInfos(val tableInfoArray: Array<TableInfo>, val tableNameDict: MutableMap<String, UInt>)

    // ---- state ----

    private var disposed = false
    private var unloading = false
    internal val apiLock = Any()
    private var _jobRunning = false
    internal var jobStd = false
    internal var jobStdExit = false
    private var closeSgbdFs = false
    internal val stackList = ArrayDeque<Byte>() // first element = top of stack (C# Stack<byte>)
    private val argInfo = ArgInfo()
    private val argInfoStd = ArgInfo()
    internal val resultDict = LinkedHashMap<String, ResultData>()
    internal val resultSysDict = LinkedHashMap<String, ResultData>()
    internal val _resultsRequestDict = LinkedHashMap<String, Boolean>()
    private var _resultSets: MutableList<MutableMap<String, ResultData>>? = null
    internal var resultSetsTemp: MutableList<MutableMap<String, ResultData>>? = null
    private val configDict = LinkedHashMap<String, String?>()
    private val groupMappingDict = LinkedHashMap<String, VariantInfo>()
    private var _logInfo: String? = null
    internal var infoProgressRange: Long = 0
    internal var infoProgressPos: Long = 0
    internal var _infoProgressText: String = ""
    internal var resultJobStatus: String = ""
    private var _groupName: String = ""
    private var _familyName: String = ""
    private var _sgbdFileName: String = ""
    internal var sgbdFileResolveLast: String = ""
    private var _ecuPath: String = ""
    private var _simulation = false
    private var _simulationPath: String = ""
    private var ecuPathDefault: String = ""
    private var _edInterfaceClass: EdInterfaceBase? = null
    private val opArgBuffer = ByteArray(5)
    private var _abortJobFunc: (() -> Boolean)? = null
    private var _progressJobFunc: ((EdiabasNet) -> Unit)? = null
    private var _errorRaisedFunc: ((ErrorCodes) -> Unit)? = null
    private var logLevelCached = -1
    private var lockTrace = false
    private var _arrayMaxBufSize: EdValueType = 1024u
    internal var errorTrapMask: EdValueType = 0u
    internal var errorTrapBitNr: Int = -1
    private var _errorCodeLast: ErrorCodes = ErrorCodes.EDIABAS_ERR_NONE
    internal val byteRegisters = ByteArray(32)
    internal val floatRegisters = DoubleArray(16)
    internal val stringRegisters: Array<StringData>
    internal val registerList: Array<Register>
    internal val flags = Flags()
    internal var sgbdFs: EdStream? = null
    internal var sgbdBaseFs: EdStream? = null
    internal var pcCounter: EdValueType = 0u
    internal var jobInfos: JobInfos? = null
    internal var usesInfos: UsesInfos? = null
    internal var versionInfos: VersionInfos? = null
    internal var descriptionInfos: DescriptionInfos? = null
    internal var tableInfos: TableInfos? = null
    internal var tableInfosExt: TableInfos? = null
    internal var _tableFs: EdStream? = null
    internal var tableIndex: Int = -1
    internal var tableRowIndex: Int = -1
    internal val userFilesArray = arrayOfNulls<EdStream>(MAX_FILES)
    internal val tableItemBuffer = ByteArray(1024)
    internal var tokenSeparator: String = ""
    internal var tokenIndex: EdValueType = 0u
    internal var floatPrecision: EdValueType = 4u
    internal var jobEnd = false
    internal var requestInit = true

    /** Minimal pluggable log hook replacing the C# trace file (level filtered by config "IfhTrace"). */
    @Volatile
    var logHook: ((EdLogLevel, String) -> Unit)? = null

    init {
        stringRegisters = Array(16) { StringData(this, _arrayMaxBufSize) }
        registerList = Register.createRegisterList(this)
        _jobRunning = false
        lockTrace = false

        setConfigProperty("EdiabasVersion", EdiabasVersionString)
        setConfigProperty("Simulation", "0")
        setConfigProperty("BipDebugLevel", "0")
        setConfigProperty("ApiTrace", "0")
        setConfigProperty("IfhTrace", "0")
        setConfigProperty("TraceBuffering", "0")
        setConfigProperty("IfhTraceBuffering", "1")
        setConfigProperty("AppendTrace", "0")
        setConfigProperty("LockTrace", "0")
        setConfigProperty("CompatMode", "1")

        setConfigProperty("UbattHandling", "0")
        setConfigProperty("IgnitionHandling", "0")
        setConfigProperty("ClampHandling", "0")

        setConfigProperty("RetryComm", "1")
        setConfigProperty("SystemResults", "1")
        setConfigProperty("TaskPriority", "0")
        setConfigProperty("EdicApiHandle", "0")

        // C#: the assembly directory is the default EcuPath; there is none here.
        setConfigProperty("EcuPath", "")
        ecuPathDefault = _ecuPath

        val lockTraceProp = getConfigProperty("LockTrace")
        if (lockTraceProp != null) {
            if (stringToValue(lockTraceProp) != 0L) {
                lockTrace = true
            }
        }

        // C#: "@file" loads an EdiabasLib.config XML file: not supported.
        if (!config.isNullOrEmpty() && config[0] != '@') {
            val words = config.split(';')
            for (word in words) {
                val assignPos = word.indexOf('=')
                if (assignPos >= 0) {
                    val key = word.substring(0, assignPos)
                    val value = word.substring(assignPos + 1)
                    setConfigProperty(key, value)
                }
            }
        }
        synchronized(sharedDataLock) {
            instanceCount++
        }
    }

    // ---- public properties ----

    val isDisposed: Boolean get() = disposed

    val isUnloading: Boolean get() = unloading

    var jobRunning: Boolean
        get() = synchronized(apiLock) { _jobRunning }
        private set(value) {
            synchronized(apiLock) { _jobRunning = value }
        }

    var argString: String
        get() = synchronized(apiLock) {
            val bin = argInfo.binData ?: return ""
            Cp1252.decode(bin)
        }
        set(value) {
            if (jobRunning) throw IllegalStateException("ArgString: Job is running")
            if (value.isNotEmpty()) logFormat(EdLogLevel.Ifh, "ArgString: {0}", value)
            synchronized(apiLock) {
                argInfo.binData = if (value.isEmpty()) null else Cp1252.encode(value)
            }
        }

    var argBinary: ByteArray
        get() = synchronized(apiLock) { argInfo.binData ?: ByteArray(0) }
        set(value) {
            if (jobRunning) throw IllegalStateException("ArgBinary: Job is running")
            if (value.isNotEmpty()) logData(EdLogLevel.Ifh, value, 0, value.size, "ArgBinary")
            synchronized(apiLock) { argInfo.binData = value }
        }

    var argStringStd: String
        get() = synchronized(apiLock) {
            val bin = argInfoStd.binData ?: return ""
            Cp1252.decode(bin)
        }
        set(value) {
            if (jobRunning) throw IllegalStateException("ArgStringStd: Job is running")
            if (value.isNotEmpty()) logFormat(EdLogLevel.Ifh, "ArgStringStd: {0}", value)
            synchronized(apiLock) {
                argInfoStd.binData = if (value.isEmpty()) null else Cp1252.encode(value)
            }
        }

    var argBinaryStd: ByteArray
        get() = synchronized(apiLock) { argInfoStd.binData ?: ByteArray(0) }
        set(value) {
            if (jobRunning) throw IllegalStateException("ArgBinaryStd: Job is running")
            if (value.isNotEmpty()) logData(EdLogLevel.Ifh, value, 0, value.size, "ArgBinaryStd")
            synchronized(apiLock) { argInfoStd.binData = value }
        }

    var noInitForVJobs: Boolean = false

    internal fun getActiveArgBinary(): ByteArray = if (jobStd) argBinaryStd else argBinary

    internal fun getActiveArgStrings(): List<String> {
        val args = if (jobStd) argStringStd else argString
        val info = if (jobStd) argInfoStd else argInfo
        var list = info.stringList
        if (list == null) {
            list = ArrayList()
            if (args.isNotEmpty()) {
                list.addAll(args.split(';'))
            }
            info.stringList = list
        }
        return list
    }

    /** Result sets of the last job: index 0 holds the system results, 1.. the job's sets. */
    val resultSets: List<Map<String, ResultData>>?
        get() = synchronized(apiLock) { _resultSets }

    /** Requested results (upper case); an empty dictionary means "all results" (etag opcode). */
    val resultsRequestDict: Map<String, Boolean>
        get() = synchronized(apiLock) { _resultsRequestDict }

    var resultsRequests: String
        get() = synchronized(apiLock) { _resultsRequestDict.keys.joinToString(";") }
        set(value) {
            synchronized(apiLock) {
                _resultsRequestDict.clear()
                if (value.isNotEmpty()) {
                    for (word in value.split(';')) {
                        if (word.isNotEmpty()) {
                            // C# Dictionary.Add throws on duplicates
                            val key = EdHelpers.upperInv(word)
                            if (_resultsRequestDict.containsKey(key)) throw IllegalArgumentException("ResultsRequests: duplicate $key")
                            _resultsRequestDict[key] = true
                        }
                    }
                }
            }
        }

    var logInfo: String?
        get() = synchronized(apiLock) { _logInfo }
        set(value) {
            synchronized(apiLock) { _logInfo = value }
        }

    val infoProgressPercent: Int
        get() = synchronized(apiLock) {
            if (infoProgressPos < 0 || infoProgressRange <= 0) -1 else (infoProgressPos * 100 / infoProgressRange).toInt()
        }

    val infoProgressText: String get() = synchronized(apiLock) { _infoProgressText }

    var abortJobFunc: (() -> Boolean)?
        get() = synchronized(apiLock) { _abortJobFunc }
        set(value) {
            if (jobRunning) throw IllegalStateException("AbortJobFunc: Job is running")
            synchronized(apiLock) { _abortJobFunc = value }
        }

    var progressJobFunc: ((EdiabasNet) -> Unit)?
        get() = synchronized(apiLock) { _progressJobFunc }
        set(value) {
            if (jobRunning) throw IllegalStateException("ProgressJobFunc: Job is running")
            synchronized(apiLock) { _progressJobFunc = value }
        }

    var errorRaisedFunc: ((ErrorCodes) -> Unit)?
        get() = synchronized(apiLock) { _errorRaisedFunc }
        set(value) {
            if (jobRunning) throw IllegalStateException("ErrorRaisedFunc: Job is running")
            synchronized(apiLock) { _errorRaisedFunc = value }
        }

    internal val arrayMaxSize: EdValueType get() = _arrayMaxBufSize - 1u

    internal var arrayMaxBufSize: EdValueType
        get() = _arrayMaxBufSize
        set(value) {
            _arrayMaxBufSize = value
            for (data in stringRegisters) {
                data.newArrayLength(_arrayMaxBufSize)
            }
        }

    val groupName: String get() = synchronized(apiLock) { _groupName }

    val familyName: String get() = synchronized(apiLock) { _familyName }

    var sgbdFileName: String
        get() = synchronized(apiLock) { _sgbdFileName }
        set(value) {
            if (jobRunning) throw IllegalStateException("SgbdFileName: Job is running")
            val changed = synchronized(apiLock) { !_sgbdFileName.equals(value, ignoreCase = true) }
            if (changed) {
                closeSgbdFs = true
                synchronized(apiLock) { _sgbdFileName = value }
            }
        }

    val ecuPath: String get() = synchronized(apiLock) { _ecuPath }

    val simulation: Boolean get() = synchronized(apiLock) { _simulation }

    val simulationPath: String get() = synchronized(apiLock) { _simulationPath }

    var edInterfaceClass: EdInterfaceBase?
        get() = synchronized(apiLock) { _edInterfaceClass }
        set(value) {
            if (jobRunning) throw IllegalStateException("EdInterfaceClass: Job is running")
            synchronized(apiLock) {
                _edInterfaceClass = value
                if (value != null) {
                    value.ediabas = this
                    setConfigProperty("Interface", value.interfaceName)
                    setConfigProperty("IfhVersion", value.interfaceVerName)
                }
            }
        }

    val errorCodeLast: ErrorCodes get() = synchronized(apiLock) { _errorCodeLast }

    // ---- lifecycle ----

    override fun close() {
        if (!disposed) {
            _abortJobFunc = null // prevent abort of exitJob
            try {
                closeSgbdFs()
            } catch (e: Exception) {
                // ignored
            }
            closeTableFs()
            closeAllUserFiles()
            _edInterfaceClass?.close()
            _edInterfaceClass = null
            closeLog()
            synchronized(sharedDataLock) {
                instanceCount--
                if (instanceCount == 0) {
                    sharedDataDict.clear()
                }
            }
            disposed = true
        }
    }

    fun clearGroupMapping() {
        synchronized(apiLock) {
            sgbdFileResolveLast = ""
            groupMappingDict.clear()
        }
    }

    // ---- configuration ----

    // EdiabasNet.cs: GetConfigProperty(string name, CallSource callSource)
    fun getConfigProperty(name: String, callSource: CallSource): String? {
        if (callSource == CallSource.EdiabasOperation) {
            if (simulation) {
                if (name.equals("Simulation", ignoreCase = true)) {
                    var compatMode = 0
                    val compatModeProp = getConfigProperty("SimulationCompat")
                    if (compatModeProp != null) {
                        compatMode = stringToValue(compatModeProp).toInt()
                    }
                    if (compatMode == 0) { // hide simulation mode
                        return "0"
                    }
                }
            }
            return getConfigProperty(name)
        }
        return getConfigProperty(name)
    }

    fun getConfigProperty(name: String): String? {
        if (disposed) return null
        val key = EdHelpers.upperInv(name)
        synchronized(apiLock) {
            return configDict[key]
        }
    }

    // EdiabasNet.cs: SetConfigProperty
    fun setConfigProperty(name: String, value: String?) {
        if (disposed) return
        val key = EdHelpers.upperInv(name)
        if (lockTrace) {
            for (k in arrayOf("ApiTrace", "IfhTrace", "TracePath", "TraceBuffering", "AppendTrace", "IfhTraceName", "ApiTraceName")) {
                if (key.equals(k, ignoreCase = true)) return
            }
        }

        var oldValue: String? = null
        synchronized(apiLock) {
            if (configDict.containsKey(key)) {
                oldValue = configDict[key]
                if (!value.isNullOrEmpty()) {
                    configDict[key] = value
                } else {
                    configDict.remove(key)
                }
            } else {
                configDict[key] = value
            }
        }

        if (key.equals("EcuPath", ignoreCase = true)) {
            if (jobRunning) throw IllegalStateException("SetConfigProperty: Job is running")
            val ecuPathNew: String
            val changed: Boolean
            synchronized(apiLock) {
                ecuPathNew = if (value.isNullOrEmpty()) ecuPathDefault else value
                changed = !_ecuPath.equals(ecuPathNew, ignoreCase = true)
            }
            if (changed) {
                synchronized(apiLock) { _ecuPath = ecuPathNew }
                closeSgbdFs = true
                clearGroupMapping()
            }
        }

        if (key.equals("Simulation", ignoreCase = true)) {
            if (jobRunning) throw IllegalStateException("SetConfigProperty: Job is running")
            val simulationMode = stringToValue(value ?: "") != 0L
            val changed = synchronized(apiLock) { _simulation != simulationMode }
            if (changed) {
                synchronized(apiLock) { _simulation = simulationMode }
                closeSgbdFs = true
            }
        }

        if (key.equals("SimulationPath", ignoreCase = true)) {
            if (jobRunning) throw IllegalStateException("SetConfigProperty: Job is running")
            val simulationPathNew = value ?: ""
            val changed = synchronized(apiLock) { !_simulationPath.equals(simulationPathNew, ignoreCase = true) }
            if (changed) {
                synchronized(apiLock) { _simulationPath = simulationPathNew }
                closeSgbdFs = true
            }
        }

        if (key.equals("EDIABASUnload", ignoreCase = true)) {
            val unload = stringToValue(value ?: "") != 0L
            if (unload) {
                unloading = true
            }
        }

        val valueChanged = (oldValue ?: "") != (value ?: "")
        var closeLogFlag = false
        if (valueChanged) {
            if (key.equals("TracePath", ignoreCase = true)) closeLogFlag = true
            if (key.equals("IfhTrace", ignoreCase = true)) closeLogFlag = true
            if (key.equals("IfhTraceName", ignoreCase = true)) closeLogFlag = true
        }
        if (closeLogFlag) {
            closeLog()
        }
    }

    fun closeSgbd() {
        closeSgbdFs = true
    }

    fun isJobExisting(jobName: String): Boolean = sgbdFs != null && getJobInfo(jobName) != null

    internal fun getJobInfo(jobName: String): JobInfo? {
        val infos = jobInfos ?: return null
        val jobIndex = infos.jobNameDict[EdHelpers.upperInv(jobName)] ?: return null
        return infos.jobInfoArray[jobIndex.toInt()]
    }

    // ---- SGBD file handling ----

    // EdiabasNet.cs: OpenSgbdFs
    private fun openSgbdFs(): Boolean {
        if (sgbdFs != null) return true
        val fileName = EdFiles.combine(ecuPath, sgbdFileName)
        val fs = try {
            EdFiles.openRead(fileName)
        } catch (e: Exception) {
            logString(EdLogLevel.Error, "OpenSgbdFs file not found: $fileName")
            return false
        }
        sgbdFs = fs
        usesInfos = readAllUses(fs)
        versionInfos = readVersionInfos(fs)
        descriptionInfos = null
        jobInfos = readAllJobs(fs)
        tableInfos = readAllTables(fs)
        requestInit = true

        if (simulation) {
            edInterfaceClass?.loadSgbdSimFile(sgbdFileName)
        }
        return true
    }

    // EdiabasNet.cs: CloseSgbdFs
    private fun closeSgbdFs() {
        if (sgbdFs != null) {
            try {
                if (!requestInit) {
                    executeExitJob()
                }
            } finally {
                sgbdFs?.close()
                sgbdFs = null
            }
        }
        edInterfaceClass?.unloadInterfaceSimFile()
    }

    internal fun closeTableFs() {
        val fs = _tableFs
        if (fs != null) {
            if (fs !== sgbdBaseFs) {
                fs.close()
            }
            _tableFs = null
        }
        tableInfosExt = null
        tableIndex = -1
        tableRowIndex = -1
    }

    internal fun getTableFs(): EdStream = _tableFs ?: sgbdFs!!

    internal fun setTableFs(fs: EdStream) {
        _tableFs = fs
        tableInfosExt = readAllTables(fs)
    }

    internal fun getTableInfos(fs: EdStream): TableInfos? {
        if (_tableFs != null && _tableFs === fs) {
            return tableInfosExt
        }
        return tableInfos
    }

    internal fun storeUserFile(fs: EdStream): Int {
        for (i in userFilesArray.indices) {
            if (userFilesArray[i] == null) {
                userFilesArray[i] = fs
                return i
            }
        }
        return -1
    }

    internal fun getUserFile(index: Int): EdStream? {
        if (index < 0 || index >= userFilesArray.size) return null
        return userFilesArray[index]
    }

    // EdiabasNet.cs: ReadFileLine
    internal fun readFileLine(fs: EdStream): String? {
        val sb = StringBuilder(100)
        var currByte: Int
        while (true) {
            currByte = fs.readByte()
            if (currByte < 0) break
            if (currByte == '\r'.code || currByte == '\n'.code) break
            sb.append(currByte.toChar())
        }
        if (currByte < 0) return null
        if (currByte == '\r'.code) {
            val nextByte = fs.readByte()
            if (nextByte >= 0 && nextByte != '\n'.code) {
                fs.position--
            }
        }
        return sb.toString()
    }

    // EdiabasNet.cs: ReadFileLineLength
    internal fun readFileLineLength(fs: EdStream): Long {
        var currByte: Int
        var lineLength = 0L
        while (true) {
            currByte = fs.readByte()
            if (currByte < 0) break
            if (currByte == '\r'.code || currByte == '\n'.code) break
            lineLength++
        }
        if (currByte < 0) return -1
        if (currByte == '\r'.code) {
            val nextByte = fs.readByte()
            if (nextByte >= 0 && nextByte != '\n'.code) {
                fs.position--
            }
        }
        return lineLength
    }

    internal fun closeUserFile(index: Int): Boolean {
        if (index < 0 || index >= userFilesArray.size) return false
        val fs = userFilesArray[index] ?: return true
        fs.close()
        userFilesArray[index] = null
        return true
    }

    internal fun closeAllUserFiles() {
        for (i in userFilesArray.indices) {
            userFilesArray[i]?.close()
            userFilesArray[i] = null
        }
    }

    // ---- errors ----

    // EdiabasNet.cs: SetError
    fun setError(error: ErrorCodes) {
        logFormat(EdLogLevel.Error, "SetError: {0}", error)

        if (error != ErrorCodes.EDIABAS_ERR_NONE) {
            val bitNumber = trapBitDict[error]
            errorTrapBitNr = bitNumber?.toInt() ?: 0
            sgbdFileResolveLast = "" // reset last file after error to force sgbd reload
            val activeErrors: EdValueType = (1 shl errorTrapBitNr).toUInt() and errorTrapMask.inv()
            if (activeErrors != 0u) {
                raiseError(error)
            }
        } else {
            errorTrapBitNr = -1
        }
    }

    // EdiabasNet.cs: RaiseError
    fun raiseError(error: ErrorCodes): Nothing {
        var errorCode = error
        if (jobStdExit) {
            errorCode = ErrorCodes.EDIABAS_SYS_0018
        }
        synchronized(apiLock) {
            _errorCodeLast = errorCode
        }
        errorRaisedFunc?.invoke(errorCode)
        throw EdiabasNetException("Error occurred: $errorCode", errorCode)
    }

    // ---- results ----

    internal fun setResultData(resultData: ResultData) {
        resultDict[EdHelpers.upperInv(resultData.name)] = resultData
    }

    @Suppress("unused")
    internal fun getResultData(name: String): ResultData? = resultDict[EdHelpers.upperInv(name)]

    internal fun setSysResultData(resultData: ResultData) {
        resultSysDict[EdHelpers.upperInv(resultData.name)] = resultData
    }

    // EdiabasNet.cs: CreateSystemResultDict
    private fun createSystemResultDict(jobInfo: JobInfo, setCount: Int): MutableMap<String, ResultData> {
        val resultDictSystem = LinkedHashMap<String, ResultData>()

        var objectName = EdFiles.getFileNameWithoutExtension(sgbdFileName)
        if (jobInfo.usesInfo != null) {
            objectName = jobInfo.usesInfo.name
        }

        var sysResults = 0L
        val sysResultStr = getConfigProperty("SystemResults")
        if (sysResultStr != null) {
            sysResults = stringToValue(sysResultStr)
        }

        resultDictSystem["VARIANTE"] = ResultData(ResultType.TypeS, "VARIANTE", EdHelpers.upperInv(EdFiles.getFileNameWithoutExtension(sgbdFileName)))
        if (IsMinVersion760) {
            resultDictSystem["GRUPPE"] = ResultData(ResultType.TypeS, "GRUPPE", EdHelpers.lowerInv(groupName))
            resultDictSystem["FAMILIE"] = ResultData(ResultType.TypeS, "FAMILIE", EdHelpers.lowerInv(familyName))
        }
        resultDictSystem["OBJECT"] = ResultData(ResultType.TypeS, "OBJECT", objectName)
        resultDictSystem["JOBNAME"] = ResultData(ResultType.TypeS, "JOBNAME", jobInfo.jobName)
        resultDictSystem["SAETZE"] = ResultData(ResultType.TypeW, "SAETZE", setCount.toLong())
        if (sysResults != 0L) {
            resultDictSystem["JOBSTATUS"] = ResultData(ResultType.TypeS, "JOBSTATUS", resultJobStatus)

            var ubattCurrent = -1L
            var ubattHistory = -1L
            var ignitionCurrent = -1L
            var ignitionHistory = -1L
            val edInterface = edInterfaceClass
            if (edInterface != null && edInterface.isSimulationMode()) {
                ubattCurrent = edInterface.ubatCurrentSimulation
                ubattHistory = edInterface.ubatHistorySimulation
                ignitionCurrent = edInterface.ignitionCurrentSimulation
                ignitionHistory = edInterface.ignitionHistorySimulation
            }

            resultDictSystem["UBATTCURRENT"] = ResultData(ResultType.TypeI, "UBATTCURRENT", ubattCurrent)
            resultDictSystem["UBATTHISTORY"] = ResultData(ResultType.TypeI, "UBATTHISTORY", ubattHistory)
            resultDictSystem["IGNITIONCURRENT"] = ResultData(ResultType.TypeI, "IGNITIONCURRENT", ignitionCurrent)
            resultDictSystem["IGNITIONHISTORY"] = ResultData(ResultType.TypeI, "IGNITIONHISTORY", ignitionHistory)
        }

        for ((key, resultData) in resultSysDict) {
            if (!resultDictSystem.containsKey(key)) {
                resultDictSystem[key] = resultData
            }
        }
        return resultDictSystem
    }

    internal fun jobProgressInform() {
        progressJobFunc?.invoke(this)
    }

    // ---- group file resolution ----

    // EdiabasNet.cs: ResolveSgbdFile
    fun resolveSgbdFile(fileName: String?) {
        logFormat(EdLogLevel.Info, "ResolveSgbdFile: {0}", fileName ?: "")
        if (jobRunning) throw IllegalStateException("ResolveSgbdFile: Job is running")
        val baseFileName = EdHelpers.lowerInv(EdFiles.getFileNameWithoutExtension(fileName ?: ""))
        if (sgbdFileResolveLast == baseFileName) { // same name specified
            logFormat(EdLogLevel.Info, "ResolveSgbdFile resolved: {0}", sgbdFileName)
            return
        }

        try {
            val fileType = getFileType(EdFiles.combine(ecuPath, fileName ?: ""))
            if (fileType == 0u) { // group file
                val key = baseFileName
                var variantName = ""
                var familyNameLocal = ""
                synchronized(apiLock) {
                    val variantInfo = groupMappingDict[key]
                    if (variantInfo != null) {
                        variantName = variantInfo.variantName
                        familyNameLocal = variantInfo.familyName
                    }
                }

                if (variantName.isEmpty()) {
                    sgbdFileName = baseFileName + GroupFileExt
                    val (variant, family) = executeIdentJob()
                    variantName = EdHelpers.lowerInv(variant)
                    if (family.isNotEmpty()) {
                        familyNameLocal = EdHelpers.lowerInv(family)
                    }

                    if (variantName.isEmpty()) {
                        logFormat(EdLogLevel.Error, "ResolveSgbdFile: No variant found")
                        throw IllegalArgumentException("ResolveSgbdFile: No variant found")
                    }
                    synchronized(apiLock) {
                        // C#: new VariantInfo(variantName, fileName) - the family name slot gets the file name
                        if (groupMappingDict.containsKey(key)) throw IllegalArgumentException("duplicate key $key")
                        groupMappingDict[key] = VariantInfo(variantName, fileName ?: "")
                    }
                }

                synchronized(apiLock) {
                    _groupName = baseFileName
                    _familyName = familyNameLocal
                }
                sgbdFileName = variantName + PrgFileExt
            } else {
                synchronized(apiLock) {
                    _groupName = ""
                    _familyName = ""
                }
                sgbdFileName = baseFileName + PrgFileExt
            }

            logFormat(EdLogLevel.Info, "ResolveSgbdFile resolved: {0}", sgbdFileName)
            sgbdFileResolveLast = baseFileName
        } catch (ex: Exception) {
            logString(EdLogLevel.Info, "ResolveSgbdFile Exception: $ex")
            synchronized(apiLock) {
                _groupName = ""
                _familyName = ""
                _sgbdFileName = ""
            }
            sgbdFileResolveLast = ""
            throw ex
        }
    }

    // EdiabasNet.cs: GetFileType (0 = group file, 1 = ECU file)
    fun getFileType(fileName: String): UInt {
        val baseFileName = EdFiles.getFileNameWithoutExtension(fileName)
        val dirName = EdFiles.getDirectoryName(fileName)
        val prgFileName = EdFiles.combine(dirName, baseFileName + PrgFileExt)
        val grpFileName = EdFiles.combine(dirName, baseFileName + GroupFileExt)
        var localFileName = ""
        if (EdFiles.exists(prgFileName)) {
            localFileName = prgFileName
        } else if (EdFiles.exists(grpFileName)) {
            localFileName = grpFileName
        }
        if (localFileName.isEmpty()) {
            logFormat(EdLogLevel.Error, "GetFileType file not found: '{0}'", fileName)
            throw IllegalArgumentException("GetFileType: File not found: $fileName")
        }
        try {
            EdFiles.openRead(localFileName).use { tempFs ->
                val buffer = ByteArray(4)
                tempFs.position = 0x10
                val readBytes = tempFs.read(buffer, 0, buffer.size)
                if (readBytes != buffer.size) {
                    throw IllegalArgumentException("GetFileType: Invalid read size")
                }
                return leUInt32(buffer, 0)
            }
        } catch (ex: Exception) {
            logFormat(EdLogLevel.Error, "GetFileType Exception: {0}", ex.toString())
            throw IllegalArgumentException("GetFileType: Unable to read file $fileName")
        }
    }

    // ---- standard jobs ----

    // EdiabasNet.cs: ExecuteInitJob
    private fun executeInitJob() {
        val jobRunningOld = jobRunning
        val jobStdOld = jobStd
        try {
            jobRunning = true
            jobStd = true
            try {
                executeJobPrivate(JobNameInit, true)
            } catch (ex: Exception) {
                logString(EdLogLevel.Error, "executeInitJob Exception: $ex")
                closeSgbdFs() // close file to force a reload
                throw ex
            }
            val sets = _resultSets
            if (sets != null && sets.size > 1) {
                val result = sets[1]["DONE"]
                if (result != null && (result.opData as? Long) == 1L) {
                    requestInit = false
                    logString(EdLogLevel.Info, "executeInitJob ok")
                    return
                }
            }
            logString(EdLogLevel.Error, "executeInitJob failed")
            closeSgbdFs() // close file to force a reload
            setError(ErrorCodes.EDIABAS_SYS_0010)
        } finally {
            jobStd = jobStdOld
            jobRunning = jobRunningOld
        }
    }

    // EdiabasNet.cs: ExecuteExitJob
    private fun executeExitJob() {
        val jobRunningOld = jobRunning
        val jobStdOld = jobStd
        try {
            jobRunning = true
            jobStd = true
            jobStdExit = true
            try {
                if (isJobExisting(JobNameExit)) {
                    executeJobPrivate(JobNameExit)
                }
            } catch (ex: Exception) {
                logString(EdLogLevel.Error, "ExecuteExitJob Exception: $ex")
                if (ex is EdiabasNetException) throw ex
                throw RuntimeException("ExecuteExitJob", ex)
            }
        } finally {
            jobStd = jobStdOld
            jobStdExit = false
            jobRunning = jobRunningOld
        }
    }

    // EdiabasNet.cs: ExecuteIdentJob, returns (variant name, family name)
    private fun executeIdentJob(): Pair<String, String> {
        var familyNameLocal = ""
        val jobRunningOld = jobRunning
        val jobStdOld = jobStd
        try {
            jobRunning = true
            jobStd = true
            resultDict.clear()
            try {
                executeJobPrivate(JobNameIdent)
            } catch (ex: Exception) {
                logString(EdLogLevel.Error, "executeIdentJob Exception: $ex")
                if (ex is EdiabasNetException) throw ex
                throw RuntimeException("executeIdentJob", ex)
            }
            val sets = _resultSets
            if (sets != null && sets.size > 1) {
                val family = sets[1]["FAMILIE"]
                if (family != null && family.opData is String) {
                    familyNameLocal = family.opData
                    logString(EdLogLevel.Info, "executeIdentJob family: $familyNameLocal")
                }
                val variant = sets[1]["VARIANTE"]
                if (variant != null && variant.opData is String) {
                    logString(EdLogLevel.Info, "executeIdentJob ok: ${variant.opData}")
                    return Pair(variant.opData, familyNameLocal)
                }
            }
            logString(EdLogLevel.Error, "executeIdentJob failed")
        } finally {
            jobStd = jobStdOld
            jobRunning = jobRunningOld
        }
        return Pair("", familyNameLocal)
    }

    // EdiabasNet.cs: ExecuteJobPrivate(string jobName, bool recursive)
    private fun executeJobPrivate(jobName: String, recursive: Boolean = false) {
        var logArgs: Any = ""
        val argStrings = getActiveArgStrings()
        if (argStrings.isNotEmpty()) {
            logArgs = argStrings.joinToString(", ")
        } else {
            val argBin = getActiveArgBinary()
            if (argBin.isNotEmpty()) logArgs = argBin
        }
        logFormat(EdLogLevel.Ifh, "executeJob({0}): {1} {2}", sgbdFileName, jobName, logArgs)

        if (closeSgbdFs) {
            closeSgbdFs = false
            closeSgbdFs()
        }

        if (!openSgbdFs()) {
            throw IllegalArgumentException("ExecuteJobPrivate: Open SGBD failed")
        }

        val bipVersion = versionInfos!!.bipVersion
        val ediabasBipVersion = (((EdiabasVersion shr 8) and 0xF).toLong() shl 16) or (((EdiabasVersion shr 4) and 0xF).toLong() shl 8) or (EdiabasVersion and 0xF).toLong()
        logFormat(EdLogLevel.Info, "Version BIP={0:X08}, EDIABAS={0:X08}", bipVersion, ediabasBipVersion)

        if (bipVersion > ediabasBipVersion) {
            setError(ErrorCodes.EDIABAS_BIP_0009)
            return
        }

        val jobInfo = getJobInfo(jobName)
        if (jobInfo == null) {
            for (vJobInfo in VJobList) {
                if (vJobInfo.jobName.equals(jobName, ignoreCase = true)) {
                    executeVJob(sgbdFs!!, vJobInfo)
                    logString(EdLogLevel.Info, "executeJob successfull")
                    return
                }
            }
            setError(ErrorCodes.EDIABAS_SYS_0008)
            return
        }

        if (jobInfo.usesInfo != null) {
            val fileName = EdFiles.combine(ecuPath, EdHelpers.lowerInv(jobInfo.usesInfo.name) + PrgFileExt)
            try {
                EdFiles.openRead(fileName).use { tempFs ->
                    sgbdBaseFs = tempFs
                    try {
                        executeJobPrivate(tempFs, jobInfo, recursive)
                    } catch (ex: Exception) {
                        logString(EdLogLevel.Error, "executeJob base job exception: $ex")
                        if (ex is EdiabasNetException) throw ex
                        throw RuntimeException("executeJob base job exception", ex)
                    }
                }
            } catch (ex: Exception) {
                logString(EdLogLevel.Error, "ExecuteJobPrivate file not found: $fileName")
                if (ex is EdiabasNetException) throw ex
                throw IllegalArgumentException("ExecuteJobPrivate: SGBD not found: $fileName", ex)
            } finally {
                closeTableFs()
                sgbdBaseFs = null
            }
        } else {
            executeJobPrivate(sgbdFs!!, jobInfo, recursive)
        }
        logString(EdLogLevel.Info, "executeJob successfull")
    }

    /**
     * EdiabasNet.cs: ExecuteJob. Runs the job (or virtual job) on the current [sgbdFileName];
     * results are in [resultSets]. Errors are reported as [EdiabasNetException].
     */
    fun executeJob(jobName: String) {
        if (jobRunning) throw IllegalStateException("ExecuteJob: Job is running")
        try {
            jobRunning = true
            jobStd = false
            jobStdExit = false
            executeJobPrivate(jobName)
        } finally {
            argInfo.binData = null
            argInfoStd.binData = null
            jobStd = false
            jobStdExit = false
            jobRunning = false
        }
    }

    // EdiabasNet.cs: ExecuteJobPrivate(Stream fs, JobInfo jobInfo, bool recursive)
    private fun executeJobPrivate(fs: EdStream, jobInfo: JobInfo, recursive: Boolean) {
        if (requestInit && !recursive) {
            executeInitJob()
        }

        val edInterface = edInterfaceClass
        edInterface?.transmitCancel(false)

        val buffer = ByteArray(2)

        resultSetsTemp = ArrayList()
        synchronized(apiLock) {
            _resultSets = null
        }
        resultDict.clear()
        resultSysDict.clear()
        stackList.clear()
        setConfigProperty("BipEcuFile", EdFiles.getFileNameWithoutExtension(sgbdFileName))
        flags.init()
        for (stringData in stringRegisters) {
            stringData.clearData()
        }
        errorTrapBitNr = -1
        errorTrapMask = 0u
        _errorCodeLast = ErrorCodes.EDIABAS_ERR_NONE
        infoProgressRange = -1
        infoProgressPos = -1
        _infoProgressText = ""
        resultJobStatus = ""

        arrayMaxBufSize = jobInfo.arraySize
        pcCounter = jobInfo.jobOffset
        closeTableFs()
        closeAllUserFiles()
        jobEnd = false

        val arg0 = Operand(this)
        val arg1 = Operand(this)
        try {
            while (!jobEnd) {
                val pcCounterOld = pcCounter
                fs.position = pcCounter.toLong()
                readAndDecryptBytes(fs, buffer, 0, buffer.size)

                val opCodeVal = buffer[0].toInt() and 0xFF
                val opAddrMode = buffer[1].toInt() and 0xFF
                val opAddrMode0 = OpAddrMode.fromValue((opAddrMode and 0xF0) shr 4)
                val opAddrMode1 = OpAddrMode.fromValue(opAddrMode and 0x0F)

                if (opCodeVal >= OcList.size) {
                    throw IllegalArgumentException("executeJob: Opcode out of range")
                }
                val oc = OcList[opCodeVal]
                getOpArg(fs, opAddrMode0, arg0)
                getOpArg(fs, opAddrMode1, arg1)
                pcCounter = fs.position.toUInt()

                // special near address arg0 opcode handling mainly for jumps
                if (oc.arg0IsNearAddress && opAddrMode0 == OpAddrMode.Imm32) {
                    val labelAddress: EdValueType = pcCounter + (arg0.opData1 as UInt)
                    arg0.opData1 = labelAddress
                }

                val abortFunc = abortJobFunc
                if (abortFunc != null) {
                    if (abortFunc()) {
                        edInterface?.transmitCancel(true)
                        throw RuntimeException("executeJob aborted")
                    }
                }

                val opFunc = oc.opFunc
                if (opFunc != null) {
                    if (logLevelCached >= EdLogLevel.Info.level) {
                        logString(EdLogLevel.Info, ">" + getOpText(pcCounterOld, oc, arg0, arg1))
                    }
                    opFunc(this, oc, arg0, arg1)
                    if (logLevelCached >= EdLogLevel.Info.level) {
                        logString(EdLogLevel.Info, "<" + getOpText(pcCounter, oc, arg0, arg1))
                    }
                } else {
                    throw IllegalArgumentException("executeJob: Function not implemented: ${oc.pneumonic}")
                }
            }
            if (resultDict.isNotEmpty()) {
                resultSetsTemp!!.add(LinkedHashMap(resultDict))
            }
            resultDict.clear()
        } catch (ex: Exception) {
            logString(EdLogLevel.Error, "executeJob Exception: $ex")
            if (ex is EdiabasNetException) throw ex
            throw RuntimeException("executeJob", ex)
        } finally {
            closeTableFs()
            closeAllUserFiles()
            val temp = resultSetsTemp!!
            val systemResultDict = createSystemResultDict(jobInfo, temp.size)
            temp.add(0, systemResultDict)
            synchronized(apiLock) {
                _resultSets = temp
            }
            setConfigProperty("BipEcuFile", null)
        }
    }

    // EdiabasNet.cs: ExecuteVJob
    private fun executeVJob(@Suppress("UNUSED_PARAMETER") fs: EdStream, vJobInfo: VJobInfo) {
        if (requestInit && !noInitForVJobs) {
            executeInitJob()
        }

        resultSetsTemp = ArrayList()
        synchronized(apiLock) {
            _resultSets = null
        }
        setConfigProperty("BipEcuFile", EdFiles.getFileNameWithoutExtension(sgbdFileName))
        try {
            vJobInfo.jobDelegate(this, resultSetsTemp!!)
        } catch (ex: Exception) {
            logString(EdLogLevel.Error, "executeVJob Exception: $ex")
            if (ex is EdiabasNetException) throw ex
            throw RuntimeException("executeVJob", ex)
        } finally {
            val temp = resultSetsTemp!!
            val systemResultDict = createSystemResultDict(JobInfo(vJobInfo.jobName, 0u, 0u, 0u, null), temp.size)
            temp.add(0, systemResultDict)
            synchronized(apiLock) {
                _resultSets = temp
            }
            setConfigProperty("BipEcuFile", null)
        }
    }

    // EdiabasNet.cs: GetTableLines
    fun getTableLines(tableName: String): List<List<String>>? {
        val infos = tableInfos ?: return null
        val tableIdx = infos.tableNameDict[EdHelpers.upperInv(tableName)] ?: return null
        val fs = sgbdFs ?: return null
        val tableList = ArrayList<List<String>>()
        val tableInfo = infos.tableInfoArray[tableIdx.toInt()]
        indexTable(fs, tableInfo)
        for (entries in tableInfo.tableEntries!!) {
            val rowList = ArrayList<String>()
            for (j in 0 until tableInfo.columns.toInt()) {
                rowList.add(getTableString(fs, entries[j]))
            }
            tableList.add(rowList)
        }
        return tableList
    }

    // EdiabasNet.cs: GetTableColumn
    fun getTableColumn(tableLines: List<List<String>>?, columnName: String): List<String>? {
        if (tableLines == null || tableLines.isEmpty()) return null
        var columnIndex = -1
        for ((index, name) in tableLines[0].withIndex()) {
            if (name.equals(columnName, ignoreCase = true)) {
                columnIndex = index
                break
            }
        }
        if (columnIndex < 0) return null
        val columnList = ArrayList<String>()
        for ((index, line) in tableLines.withIndex()) {
            if (index > 0 && line.size >= columnIndex) {
                columnList.add(line[columnIndex])
            }
        }
        return columnList
    }

    // ---- logging (minimal: no trace file, a hook instead) ----

    fun logFormat(logLevel: EdLogLevel, format: String, vararg args: Any?) {
        if (disposed) return
        updateLogLevel()
        if (logLevel.level > logLevelCached) return
        val argList = args.map { arg ->
            when (arg) {
                is String -> "'$arg'"
                is ByteArray -> "[" + EdHelpers.hexDump(arg) + "]"
                else -> arg
            }
        }
        logString(logLevel, NetFormat.format(format, argList.toTypedArray()))
    }

    fun logString(logLevel: EdLogLevel, info: String) {
        if (disposed) return
        updateLogLevel()
        if (logLevel.level > logLevelCached) return
        logHook?.invoke(logLevel, info)
    }

    fun logData(logLevel: EdLogLevel, data: ByteArray, offset: Int, length: Int, info: String) {
        updateLogLevel()
        if (logLevel.level > logLevelCached) return
        logString(logLevel, " ($info): " + EdHelpers.hexDump(data, offset, length))
    }

    fun closeLog() {
        logLevelCached = -1
    }

    private fun updateLogLevel() {
        if (logLevelCached < 0) {
            val ifhTrace = getConfigProperty("IfhTrace") ?: ""
            logLevelCached = stringToValue(ifhTrace).toInt()
        }
    }

    // ---- byte code decoding ----

    // EdiabasNet.cs: GetOpArg
    private fun getOpArg(fs: EdStream, opAddrMode: OpAddrMode, oper: Operand) {
        try {
            val b = opArgBuffer
            when (opAddrMode) {
                OpAddrMode.None -> oper.init(opAddrMode)

                OpAddrMode.RegS, OpAddrMode.RegAb, OpAddrMode.RegI, OpAddrMode.RegL -> {
                    readAndDecryptBytes(fs, b, 0, 1)
                    oper.init(opAddrMode, getRegister(b[0].toInt() and 0xFF))
                }

                OpAddrMode.Imm8 -> {
                    readAndDecryptBytes(fs, b, 0, 1)
                    oper.init(opAddrMode, (b[0].toInt() and 0xFF).toUInt())
                }

                OpAddrMode.Imm16 -> {
                    readAndDecryptBytes(fs, b, 0, 2)
                    oper.init(opAddrMode, leUInt16(b, 0))
                }

                OpAddrMode.Imm32 -> {
                    readAndDecryptBytes(fs, b, 0, 4)
                    oper.init(opAddrMode, leUInt32(b, 0))
                }

                OpAddrMode.ImmStr -> {
                    readAndDecryptBytes(fs, b, 0, 2)
                    val slen = leUInt16(b, 0).toShort().toInt()
                    val buffer = ByteArray(slen)
                    readAndDecryptBytes(fs, buffer, 0, slen)
                    oper.init(opAddrMode, buffer)
                }

                OpAddrMode.IdxImm -> {
                    readAndDecryptBytes(fs, b, 0, 3)
                    val oaReg = getRegister(b[0].toInt() and 0xFF)
                    val idx = leUInt16(b, 1)
                    oper.init(opAddrMode, oaReg, idx)
                }

                OpAddrMode.IdxReg -> {
                    readAndDecryptBytes(fs, b, 0, 2)
                    val oaReg0 = getRegister(b[0].toInt() and 0xFF)
                    val oaReg1 = getRegister(b[1].toInt() and 0xFF)
                    oper.init(opAddrMode, oaReg0, oaReg1)
                }

                OpAddrMode.IdxRegImm -> {
                    readAndDecryptBytes(fs, b, 0, 4)
                    val oaReg0 = getRegister(b[0].toInt() and 0xFF)
                    val oaReg1 = getRegister(b[1].toInt() and 0xFF)
                    val inc = leUInt16(b, 2)
                    oper.init(opAddrMode, oaReg0, oaReg1, inc)
                }

                OpAddrMode.IdxImmLenImm -> {
                    readAndDecryptBytes(fs, b, 0, 5)
                    val oaReg = getRegister(b[0].toInt() and 0xFF)
                    val idx = leUInt16(b, 1)
                    val len = leUInt16(b, 3)
                    oper.init(opAddrMode, oaReg, idx, len)
                }

                OpAddrMode.IdxImmLenReg -> {
                    readAndDecryptBytes(fs, b, 0, 4)
                    val oaReg = getRegister(b[0].toInt() and 0xFF)
                    val idx = leUInt16(b, 1)
                    val oaLen = getRegister(b[3].toInt() and 0xFF)
                    oper.init(opAddrMode, oaReg, idx, oaLen)
                }

                OpAddrMode.IdxRegLenImm -> {
                    readAndDecryptBytes(fs, b, 0, 4)
                    val oaReg = getRegister(b[0].toInt() and 0xFF)
                    val oaIdx = getRegister(b[1].toInt() and 0xFF)
                    val len = leUInt16(b, 2)
                    oper.init(opAddrMode, oaReg, oaIdx, len)
                }

                OpAddrMode.IdxRegLenReg -> {
                    readAndDecryptBytes(fs, b, 0, 3)
                    val oaReg = getRegister(b[0].toInt() and 0xFF)
                    val oaIdx = getRegister(b[1].toInt() and 0xFF)
                    val oaLen = getRegister(b[2].toInt() and 0xFF)
                    oper.init(opAddrMode, oaReg, oaIdx, oaLen)
                }
            }
        } catch (ex: Exception) {
            if (ex is EdiabasNetException) throw ex
            throw RuntimeException("opAddrMode", ex)
        }
    }

    // EdiabasNet.cs: GetRegister
    internal fun getRegister(opcode: Int): Register {
        val result: Register = if (opcode <= 0x33) {
            registerList[opcode]
        } else if (opcode >= 0x80) {
            val index = opcode - 0x80 + 0x34
            if (index >= registerList.size) throw IllegalArgumentException("GetRegister: Opcode out of range")
            registerList[index]
        } else {
            throw IllegalArgumentException("GetRegister: Opcode out of range")
        }
        if (result.opcode != opcode) throw IllegalArgumentException("GetRegister: Opcode mapping invalid")
        return result
    }

    companion object {
        const val EdiabasVersion = 0x773
        const val PrgFileExt = ".prg"
        const val GroupFileExt = ".grp"
        private const val MAX_FILES = 5

        internal const val JobNameInit = "INITIALISIERUNG"
        internal const val JobNameExit = "ENDE"
        internal const val JobNameIdent = "IDENTIFIKATION"

        val EdiabasVersionString: String =
            "${(EdiabasVersion shr 8) and 0xF}.${(EdiabasVersion shr 4) and 0xF}.${EdiabasVersion and 0xF}"

        @Suppress("KotlinConstantConditions")
        const val IsMinVersion760: Boolean = EdiabasVersion >= 0x760

        @Suppress("KotlinConstantConditions")
        const val IsMinVersion770: Boolean = EdiabasVersion >= 0x770

        /** EdiabasNet.cs: _trapBitDict (insertion order matters for the eerr opcode). */
        internal val trapBitDict: LinkedHashMap<ErrorCodes, UInt> = LinkedHashMap<ErrorCodes, UInt>().apply {
            put(ErrorCodes.EDIABAS_BIP_0002, 2u)
            put(ErrorCodes.EDIABAS_BIP_0006, 6u)
            put(ErrorCodes.EDIABAS_BIP_0009, 9u)
            put(ErrorCodes.EDIABAS_BIP_0010, 10u)
            put(ErrorCodes.EDIABAS_IFH_0001, 11u)
            put(ErrorCodes.EDIABAS_IFH_0002, 12u)
            put(ErrorCodes.EDIABAS_IFH_0003, 13u)
            put(ErrorCodes.EDIABAS_IFH_0004, 14u)
            put(ErrorCodes.EDIABAS_IFH_0005, 15u)
            put(ErrorCodes.EDIABAS_IFH_0006, 16u)
            put(ErrorCodes.EDIABAS_IFH_0007, 17u)
            put(ErrorCodes.EDIABAS_IFH_0008, 18u)
            put(ErrorCodes.EDIABAS_IFH_0009, 19u)
            put(ErrorCodes.EDIABAS_IFH_0010, 20u)
            put(ErrorCodes.EDIABAS_IFH_0011, 21u)
            put(ErrorCodes.EDIABAS_IFH_0012, 22u)
            put(ErrorCodes.EDIABAS_IFH_0013, 23u)
            put(ErrorCodes.EDIABAS_IFH_0014, 24u)
            put(ErrorCodes.EDIABAS_IFH_0015, 25u)
            put(ErrorCodes.EDIABAS_IFH_0016, 26u)
            if (IsMinVersion760) {
                put(ErrorCodes.EDIABAS_BIP_0011, 8u)
                put(ErrorCodes.EDIABAS_IFH_0069, 28u)
            }
            if (IsMinVersion770) {
                put(ErrorCodes.EDIABAS_IFH_0074, 29u)
            }
        }

        internal val sharedDataLock = Any()
        internal val sharedDataDict = HashMap<String, ByteArray>()
        private var instanceCount = 0

        internal val VJobList: Array<VJobInfo> = arrayOf(
            VJobInfo("_JOBS", ::vJobJobs),
            VJobInfo("_JOBCOMMENTS", ::vJobJobComments),
            VJobInfo("_ARGUMENTS", ::vJobJobArgs),
            VJobInfo("_RESULTS", ::vJobJobResults),
            VJobInfo("_VERSIONINFO", ::vJobVerInfos),
            VJobInfo("_TABLES", ::vJobTables),
            VJobInfo("_TABLE", ::vJobTable),
        )

        /** EdiabasNet.cs: StringToValue */
        @JvmStatic
        fun stringToValue(number: String): Long = EdHelpers.stringToValue(number).first

        /** EdiabasNet.cs: StringToValue(number, out valid): returns (value, valid). */
        @JvmStatic
        fun stringToValueValid(number: String): Pair<Long, Boolean> = EdHelpers.stringToValue(number)

        /** EdiabasNet.cs: StringToFloat */
        @JvmStatic
        fun stringToFloat(number: String): Double = EdHelpers.stringToFloat(number).first

        /** EdiabasNet.cs: StringToFloat(number, out valid): returns (value, valid). */
        @JvmStatic
        fun stringToFloatValid(number: String): Pair<Double, Boolean> = EdHelpers.stringToFloat(number)

        /** EdiabasNet.cs: HexToByteArray */
        @JvmStatic
        fun hexToByteArray(valueStr: String): ByteArray = EdHelpers.hexToByteArray(valueStr)

        /** EdiabasNet.cs: GetErrorDescription */
        @JvmStatic
        fun getErrorDescription(errorCode: ErrorCodes): String {
            if (errorCode < ErrorCodes.EDIABAS_IFH_0000 || errorCode > ErrorCodes.EDIABAS_ERROR_LAST) return ""
            return ErrorCodes.ERROR_DESCRIPTION[errorCode.code - ErrorCodes.EDIABAS_IFH_0000.code]
        }

        /** EdiabasNet.cs: FormatResult (apiResultText style formatting), null if the format is invalid. */
        @JvmStatic
        fun formatResult(resultData: ResultData, format: String): String? = EdFormat.formatResult(resultData, format)

        // EdiabasNet.cs: ReadAndDecryptBytes
        internal fun readAndDecryptBytes(fs: EdStream, buffer: ByteArray, offset: Int, count: Int) {
            if (fs.read(buffer, offset, count) != count) {
                throw EOFException("ReadAndDecryptBytes")
            }
            for (i in offset until offset + count) {
                buffer[i] = (buffer[i].toInt() xor 0xF7).toByte()
            }
        }

        // EdiabasNet.cs: ReadInt32
        internal fun readInt32(fs: EdStream): Int {
            val buffer = ByteArray(4)
            if (fs.read(buffer, 0, buffer.size) != buffer.size) {
                throw EOFException("ReadInt32")
            }
            return leUInt32(buffer, 0).toInt()
        }

        internal fun leUInt16(b: ByteArray, offset: Int): UInt =
            ((b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)).toUInt()

        internal fun leUInt32(b: ByteArray, offset: Int): UInt =
            ((b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)).toUInt()

        // EdiabasNet.cs: GetOpText
        internal fun getOpText(addr: EdValueType, oc: OpCode, arg0: Operand, arg1: Operand): String {
            var result = String.format("%08X: ", addr.toLong()) + oc.pneumonic
            val text1 = getOpArgText(arg0)
            val text2 = getOpArgText(arg1)
            if (text1.isNotEmpty()) result += " $text1"
            if (text2.isNotEmpty()) result += ", $text2"
            return result
        }

        private fun hex(v: UInt): String = v.toString(16).uppercase()

        // EdiabasNet.cs: GetOpArgText
        private fun getOpArgText(arg: Operand): String {
            try {
                val regName1 = (arg.opData1 as? Register)?.getName() ?: ""
                val regName2 = (arg.opData2 as? Register)?.getName() ?: ""
                val regName3 = (arg.opData3 as? Register)?.getName() ?: ""
                return when (arg.addrMode) {
                    OpAddrMode.None -> ""
                    OpAddrMode.RegS -> {
                        when (val data = arg.getRawData()) {
                            is ByteArray -> regName1 + ": " + getStringText(arg.getArrayData())
                            is Double -> regName1 + ": " + EdHelpers.doubleToStringNet(data)
                            else -> ""
                        }
                    }
                    OpAddrMode.RegAb, OpAddrMode.RegI, OpAddrMode.RegL -> regName1 + ": $" + hex(arg.getValueData())
                    OpAddrMode.Imm8 -> "#$" + hex(arg.getValueData()) + ".B"
                    OpAddrMode.Imm16 -> "#$" + hex(arg.getValueData()) + ".I"
                    OpAddrMode.Imm32 -> "#$" + hex(arg.getValueData()) + ".L"
                    OpAddrMode.ImmStr -> "#" + getStringText(arg.getArrayData())
                    OpAddrMode.IdxImm -> {
                        if (arg.opData2 == null) return ""
                        regName1 + "[#$" + hex(arg.opData2 as UInt) + "]: " + getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxReg -> {
                        if (arg.opData2 == null) return ""
                        regName1 + "[" + regName2 + ": $" + hex((arg.opData2 as Register).getValueData()) + "]: " + getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxRegImm -> {
                        if (arg.opData2 == null || arg.opData3 == null) return ""
                        regName1 + "[" + regName2 + ": $" + hex((arg.opData2 as Register).getValueData()) + ",#$" + hex(arg.opData3 as UInt) + "]: " +
                            getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxImmLenImm -> {
                        if (arg.opData2 == null || arg.opData3 == null) return ""
                        regName1 + "[#" + hex(arg.opData2 as UInt) + "],#$" + hex(arg.opData3 as UInt) + ": " + getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxImmLenReg -> {
                        if (arg.opData2 == null || arg.opData3 == null) return ""
                        regName1 + "[#" + hex(arg.opData2 as UInt) + "]," + regName3 + ": #$" + hex((arg.opData3 as Register).getValueData()) + ": " +
                            getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxRegLenImm -> {
                        if (arg.opData2 == null || arg.opData3 == null) return ""
                        regName1 + "[" + regName2 + ": $" + hex((arg.opData2 as Register).getValueData()) + "],#$" + hex(arg.opData3 as UInt) + ": " +
                            getStringText(arg.getArrayData())
                    }
                    OpAddrMode.IdxRegLenReg -> {
                        if (arg.opData2 == null || arg.opData3 == null) return ""
                        regName1 + "[" + regName2 + ": $" + hex((arg.opData2 as Register).getValueData()) + "]," + regName3 + ": #$" +
                            hex((arg.opData3 as Register).getValueData()) + ": " + getStringText(arg.getArrayData())
                    }
                }
            } catch (ex: Exception) {
                if (ex is EdiabasNetException) throw ex
                throw RuntimeException("opAddrMode", ex)
            }
        }

        // EdiabasNet.cs: GetStringText
        private fun getStringText(dataArray: ByteArray): String {
            var printable = true
            val length = dataArray.size
            if (length < 1) return "{ }"
            for (i in 0 until length - 1) {
                if (!isPrintable(dataArray[i].toInt() and 0xFF)) {
                    printable = false
                    break
                }
            }
            if (printable && dataArray[length - 1] == 0.toByte()) {
                return "\"" + Cp1252.decode(dataArray, 0, length - 1) + "\""
            }
            val sb = StringBuilder()
            sb.append("{")
            for (i in 0 until length) {
                sb.append("$").append(Integer.toHexString(dataArray[i].toInt() and 0xFF).uppercase()).append(".B,")
            }
            sb.setLength(sb.length - 1)
            sb.append("}")
            return sb.toString()
        }

        private fun isPrintable(b: Int): Boolean = b == 9 || b == 10 || b == 13 || (b in 32..126)

        // EdiabasNet.cs: GetArgsValueLength
        internal fun getArgsValueLength(arg0: Operand, @Suppress("UNUSED_PARAMETER") arg1: Operand): EdValueType = arg0.getDataLen(true)
    }
}
