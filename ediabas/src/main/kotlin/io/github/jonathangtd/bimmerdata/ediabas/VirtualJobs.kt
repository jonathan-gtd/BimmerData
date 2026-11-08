/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs (virtual jobs VJobJobs ... VJobTable).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

private typealias ResultSets = MutableList<MutableMap<String, ResultData>>

private fun strResult(name: String, value: String) = ResultData(ResultType.TypeS, name, value)

// EdiabasNet.cs: VJobJobs (_JOBS)
internal fun vJobJobs(ediabas: EdiabasNet, resultSets: ResultSets) {
    val entryJobName = "JOBNAME"
    val argStrings = ediabas.getActiveArgStrings()
    // the ALL argument is an EdiabasLib extensions
    val allJobs = argStrings.isNotEmpty() && argStrings[0].equals("ALL", ignoreCase = true)
    val jobNameDict = HashMap<String, Boolean>()
    for (jobInfo in ediabas.jobInfos!!.jobInfoArray) {
        val key = EdHelpers.upperInv(jobInfo.jobName)
        if ((allJobs && !jobNameDict.containsKey(key)) || jobInfo.usesInfo == null) {
            val resultDict = linkedMapOf(entryJobName to strResult(entryJobName, jobInfo.jobName))
            resultSets.add(LinkedHashMap(resultDict))
            // C# Dictionary.Add throws on duplicate job names
            if (jobNameDict.containsKey(key)) throw IllegalArgumentException("VJobJobs: duplicate job $key")
            jobNameDict[key] = true
        }
    }
}

private fun EdiabasNet.jobCommentsFor(): List<String>? {
    val argStrings = getActiveArgStrings()
    if (argStrings.isEmpty()) return null
    if (descriptionInfos == null) {
        descriptionInfos = readDescriptions(sgbdFs!!)
    }
    return descriptionInfos!!.jobComments[EdHelpers.upperInv(argStrings[0])]
}

// EdiabasNet.cs: VJobJobComments (_JOBCOMMENTS)
internal fun vJobJobComments(ediabas: EdiabasNet, resultSets: ResultSets) {
    val jobComments = ediabas.jobCommentsFor() ?: return
    val resultDict = LinkedHashMap<String, ResultData>()
    var commentCount = 0
    for (desc in jobComments) {
        val colon = desc.indexOf(':')
        if (colon >= 0) {
            var key = desc.substring(0, colon)
            val value = desc.substring(colon + 1)
            if (key.equals("JOBCOMMENT", ignoreCase = true)) {
                key += commentCount.toString()
                commentCount++
                if (!resultDict.containsKey(key)) {
                    resultDict[key] = strResult(key, value)
                }
            }
        }
    }
    if (resultDict.isNotEmpty()) {
        resultSets.add(LinkedHashMap(resultDict))
    }
}

/** Shared implementation of VJobJobArgs / VJobJobResults (identical apart from the key names). */
private fun vJobJobItems(ediabas: EdiabasNet, resultSets: ResultSets, itemKey: String, typeKey: String, commentKey: String) {
    val jobComments = ediabas.jobCommentsFor() ?: return
    var resultDict: LinkedHashMap<String, ResultData>? = null
    var commentCount = 0
    for (desc in jobComments) {
        val colon = desc.indexOf(':')
        if (colon >= 0) {
            var key = desc.substring(0, colon)
            val value = desc.substring(colon + 1)

            if (key.equals(itemKey, ignoreCase = true)) {
                if (resultDict != null && resultDict.isNotEmpty()) {
                    resultSets.add(LinkedHashMap(resultDict))
                }
                resultDict = LinkedHashMap()
                commentCount = 0
            }

            if (resultDict != null) {
                if (key.equals(itemKey, ignoreCase = true)) {
                    if (!resultDict.containsKey(key)) resultDict[key] = strResult(key, value)
                }
                if (key.equals(typeKey, ignoreCase = true)) {
                    if (!resultDict.containsKey(key)) resultDict[key] = strResult(key, value)
                }
                if (key.equals(commentKey, ignoreCase = true)) {
                    key += commentCount.toString()
                    commentCount++
                    if (!resultDict.containsKey(key)) resultDict[key] = strResult(key, value)
                }
            }
        }
    }
    if (resultDict != null && resultDict.isNotEmpty()) {
        resultSets.add(LinkedHashMap(resultDict))
    }
}

// EdiabasNet.cs: VJobJobArgs (_ARGUMENTS)
internal fun vJobJobArgs(ediabas: EdiabasNet, resultSets: ResultSets) =
    vJobJobItems(ediabas, resultSets, "ARG", "ARGTYPE", "ARGCOMMENT")

// EdiabasNet.cs: VJobJobResults (_RESULTS)
internal fun vJobJobResults(ediabas: EdiabasNet, resultSets: ResultSets) =
    vJobJobItems(ediabas, resultSets, "RESULT", "RESULTTYPE", "RESULTCOMMENT")

// EdiabasNet.cs: VJobVerInfos (_VERSIONINFO)
internal fun vJobVerInfos(ediabas: EdiabasNet, resultSets: ResultSets) {
    val fs = ediabas.sgbdFs!!
    if (ediabas.descriptionInfos == null) {
        ediabas.descriptionInfos = ediabas.readDescriptions(fs)
    }
    if (ediabas.versionInfos == null) {
        ediabas.versionInfos = ediabas.readVersionInfos(fs)
    }
    val versionInfos = ediabas.versionInfos!!
    val resultDict = LinkedHashMap<String, ResultData>()

    val bipVersion = versionInfos.bipVersion
    val bipVersionStr = "${(bipVersion shr 16) and 0xFF}.${(bipVersion shr 8) and 0xFF}.${bipVersion and 0xFF}"
    resultDict["BIP_VERSION"] = strResult("BIP_VERSION", bipVersionStr)
    resultDict["AUTHOR"] = strResult("AUTHOR", versionInfos.author)
    resultDict["REVISION"] = strResult("REVISION", versionInfos.revision)
    resultDict["FROM"] = strResult("FROM", versionInfos.from)
    resultDict["PACKAGE"] = ResultData(ResultType.TypeL, "PACKAGE", versionInfos.packageVersion)

    val globalComments = ediabas.descriptionInfos!!.globalComments
    if (globalComments != null) {
        var descCount = 0
        var usesCount = 0
        for (desc in globalComments) {
            val colon = desc.indexOf(':')
            if (colon >= 0) {
                var key = desc.substring(0, colon)
                val value = desc.substring(colon + 1)
                if (key.equals("ECUCOMMENT", ignoreCase = true)) {
                    key += descCount.toString()
                    descCount++
                }
                if (key.equals("USES", ignoreCase = true)) {
                    key += usesCount.toString()
                    usesCount++
                }
                if (!resultDict.containsKey(key)) {
                    resultDict[key] = strResult(key, value)
                }
            }
        }
    }
    if (resultDict.isNotEmpty()) {
        resultSets.add(LinkedHashMap(resultDict))
    }
}

// EdiabasNet.cs: VJobTables (_TABLES)
internal fun vJobTables(ediabas: EdiabasNet, resultSets: ResultSets) {
    val entryTableName = "TABLE"
    for (tableInfo in ediabas.tableInfos!!.tableInfoArray) {
        resultSets.add(linkedMapOf(entryTableName to strResult(entryTableName, tableInfo.name)))
    }
}

// EdiabasNet.cs: VJobTable (_TABLE): one result set per table line, header line first
internal fun vJobTable(ediabas: EdiabasNet, resultSets: ResultSets) {
    val argStrings = ediabas.getActiveArgStrings()
    if (argStrings.isEmpty()) return
    val infos = ediabas.tableInfos!!
    val tableIdx = infos.tableNameDict[EdHelpers.upperInv(argStrings[0])] ?: return
    val tableInfo = infos.tableInfoArray[tableIdx.toInt()]
    val fs = ediabas.sgbdFs!!
    ediabas.indexTable(fs, tableInfo)
    for (entries in tableInfo.tableEntries!!) {
        val resultDict = LinkedHashMap<String, ResultData>()
        for (j in 0 until tableInfo.columns.toInt()) {
            val rowStr = ediabas.getTableString(fs, entries[j])
            val entryName = "COLUMN$j"
            resultDict[entryName] = strResult(entryName, rowStr)
        }
        if (resultDict.isNotEmpty()) {
            resultSets.add(LinkedHashMap(resultDict))
        }
    }
}
