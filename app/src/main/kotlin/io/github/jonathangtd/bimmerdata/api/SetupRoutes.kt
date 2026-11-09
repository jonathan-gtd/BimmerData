/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.ediabas.deepobd.CanMode
import io.github.jonathangtd.bimmerdata.files.EcuFileStore
import io.github.jonathangtd.bimmerdata.files.EcuFileStore.EcuFileException
import io.github.jonathangtd.bimmerdata.files.EcuFileStore.Reason
import io.github.jonathangtd.bimmerdata.live.Measurement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale

/**
 * Routes that set the app up without touching the phone: ECU files, permissions report,
 * Bluetooth adapter, GPS / recording / translation settings and the rest of the Telegraf export.
 * Part of [ApiService]; pure JVM.
 */
internal class SetupRoutes(
    private val backend: ApiBackend,
    private val guard: CarWriteGuard,
    private val confirmTimeoutMs: Long,
    private val clock: () -> Long,
    private val meta: (String) -> Measurement?,
    /** Drops the API's cached ECU catalog (after the ECU files changed). */
    private val ecuFilesChanged: () -> Unit,
) {
    private fun ok(element: JsonElement, status: Int = 200) = json(status, element)
    private inline fun <reified T> okOf(value: T, status: Int = 200) = json(status, ApiJson.encodeToJsonElement(value))
    private fun noContent() = HttpResponse.Bytes(204, HttpServer.JSON, ByteArray(0))

    private fun store(): EcuFileStore = backend.ecuFiles ?: notAvailable("Managing the ECU files")

    /** Runs [block] on the ECU files, turning their errors into API errors. */
    private fun <T> ecu(block: (EcuFileStore) -> T): T = try {
        block(store())
    } catch (e: EcuFileException) {
        throw when (e.reason) {
            Reason.Busy -> ApiException(409, "import_running", e.message ?: "busy")
            Reason.InvalidName -> ApiException(400, "invalid_name", e.message ?: "invalid name")
            Reason.TooLarge -> ApiException(413, "too_large", e.message ?: "too large")
            Reason.InsufficientStorage -> ApiException(507, "insufficient_storage", e.message ?: "no space")
            Reason.NotFound -> notFound("ecu_file_not_found", e.message ?: "not found")
            Reason.NoImportDir -> ApiException(501, "not_available", e.message ?: "not available")
            Reason.NoEcuFiles -> ApiException(422, "no_ecu_files", e.message ?: "no files")
            Reason.Truncated -> ApiException(400, "truncated_body", e.message ?: "truncated")
        }
    } catch (e: IOException) {
        throw ApiException(500, "storage_error", "Cannot write the ECU files: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun requireBluetoothPermission(view: BluetoothView) {
        if (view.permission == "missing") {
            throw ApiException(409, "bluetooth_permission_missing",
                "BimmerData may not use Bluetooth yet (Android permission \"Nearby devices\" missing). Ask the user to tap BimmerData › Settings › Connection › Allow, or Android settings › Apps › BimmerData › Permissions › Nearby devices › Allow; see GET /api/v1/permissions")
        }
    }

    private fun requireBluetoothConnection(view: BluetoothView) {
        if (!view.connected) {
            throw ApiException(409, "not_connected_bluetooth", "Not connected through the Bluetooth adapter: POST /api/v1/connect {\"mode\":\"bluetooth\"} first (the adapter must be in the car, ignition on)")
        }
    }

    fun routes(): List<Route> {
        val nameParam = Param("name", "path", "ECU file name, e.g. `<sgbd>.prg` (stored in lower case).", S.str(), example = JsonPrimitive("<sgbd>.prg"))
        val ecuErrors = mapOf(409 to "An import from the device folder is running (code import_running).", 501 to "Not available.", 507 to "Not enough free space: the app always keeps 100 MB free (code insufficient_storage).")
        val canModes = linkedMapOf("auto" to CanMode.Auto, "kLineOnly" to CanMode.KLineOnly, "dCan500" to CanMode.DCan500)
        val confirmErrors = mapOf(
            403 to "The user refused on the phone (code refused_on_phone), or the config scope is not enabled.",
            408 to "Nobody confirmed on the phone in time (code confirmation_timeout).",
            409 to "Not connected over Bluetooth (not_connected_bluetooth) or the car is moving (vehicle_moving).",
            503 to "The phone cannot ask for a confirmation (confirmation_unavailable).",
        )
        return listOf(
            // ------------------------------------------------------------ permissions
            Route(
                "GET", "/api/v1/permissions", RouteAccess.Needs(Scope.Read), TAG, "getPermissions",
                "Permissions and switches the app needs, granted or missing",
                "Android runtime permissions, special accesses and system switches BimmerData depends on, each with its state, what it is for and exactly what the user must tap. " +
                    "Android never lets an app grant itself a permission: show `howToGrant` of the `missing` entries to the user. `blocking` marks the ones a feature that is switched on cannot work without.",
                reply = Reply(description = "Permissions", schema = S.obj(null, listOf("permissions", "missing"), false, null,
                    "permissions" to S.arr(S.ref("Permission")),
                    "missing" to S.arr(S.str(), "Ids of the entries that are missing or off."),
                    "blocking" to S.arr(S.str(), "Ids of the missing entries a feature that is on needs."))),
            ) {
                val list = backend.permissions()
                ok(buildJsonObject {
                    put("permissions", ApiJson.encodeToJsonElement(list))
                    put("missing", JsonArray(list.filter { it.status == "missing" || it.status == "off" }.map { JsonPrimitive(it.id) }))
                    put("blocking", JsonArray(list.filter { it.blocking }.map { JsonPrimitive(it.id) }))
                })
            },

            // ------------------------------------------------------------ ECU files
            Route(
                "GET", "/api/v1/ecu-files", RouteAccess.Needs(Scope.Read), TAG, "getEcuFiles",
                "Imported ECU files and the import from the device folder",
                "How many EDIABAS ECU files (`.prg` / `.grp`) are imported, their total size, the free space, the device folder `importDir` that POST /api/v1/ecu-files/import-from-device reads (with the number of ECU files waiting there) and the progress of that import (`deviceImport`). Add `?files=true` for the file list.",
                params = listOf(Param("files", "query", "Also list every file.", S.bool(default = false), required = false)),
                reply = Reply(description = "ECU files", schema = S.ref("EcuFiles")),
                errors = mapOf(501 to "Not available."),
            ) { call -> okOf(ecu { it.status(call.queryBool("files", false)) }) },
            Route(
                "POST", "/api/v1/ecu-files/import-from-device", RouteAccess.Needs(Scope.Config), TAG, "importEcuFilesFromDevice",
                "Import the ECU files put in the app's folder on the phone",
                "Copies every `.prg` / `.grp` file of the app's own folder on the phone's shared storage (`importDir` of GET /api/v1/ecu-files, e.g. `/sdcard/Android/data/<package>/files/ecu-import/`; sub-folders included, so `adb push <Ecu folder> <importDir>` works) into the app, in the background: answers 202 at once, follow `deviceImport` with GET /api/v1/ecu-files. " +
                    "`replace` (default) makes this folder the new ECU set, swapped in only once complete like the in-app import (the app disconnects from the car just before the swap); `merge` adds and replaces files and keeps the others. " +
                    "Safe to call twice: while an import runs the call returns its progress (`started`: false); files already imported with the same size and time are not copied again, so calling it again after an interruption goes on where it stopped. " +
                    "Only regular files with a plain `.prg` / `.grp` name are taken (symbolic links, other files and duplicate names are listed in `skipped`). With `deleteSource` the imported files are deleted from the folder afterwards; the folder can also be emptied by hand.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "mode" to S.str("replace (default) or merge.", enum = listOf("replace", "merge")),
                    "deleteSource" to S.bool("Delete the imported files from the folder when done (default false).")),
                    required = false, example = buildJsonObject { put("mode", "replace") }),
                reply = Reply(202, "Import started (or already running)", S.obj(null, listOf("started", "deviceImport"), false, null,
                    "started" to S.bool("False when an import was already running (its progress is returned)."), "deviceImport" to S.ref("DeviceImport"))),
                errors = ecuErrors + mapOf(400 to "Malformed body.", 409 to "Files are being uploaded (code import_running).", 422 to "No ECU file in the folder (code no_ecu_files)."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("mode", "deleteSource")
                val mode = when (b.string("mode") ?: "replace") {
                    "replace" -> EcuFileStore.Mode.Replace
                    "merge" -> EcuFileStore.Mode.Merge
                    else -> throw badRequest("'mode' must be replace or merge")
                }
                val (progress, started) = ecu { it.startDeviceImport(mode, b.bool("deleteSource") ?: false) }
                ok(buildJsonObject { put("started", started); put("deviceImport", ApiJson.encodeToJsonElement(progress)) }, 202)
            },
            Route(
                "DELETE", "/api/v1/ecu-files/import-from-device", RouteAccess.Needs(Scope.Config), TAG, "cancelEcuFilesImport",
                "Stop the import from the device folder",
                "Stops the running import after the current file. A `replace` import keeps the previous ECU set; calling POST …/import-from-device again goes on where it stopped.",
                reply = Reply(description = "Cancel requested", schema = S.obj(null, listOf("cancelled", "deviceImport"), false, null,
                    "cancelled" to S.bool("False when no import was running."), "deviceImport" to S.ref("DeviceImport"))),
                errors = mapOf(501 to "Not available."),
            ) {
                val s = store()
                val cancelled = s.cancelDeviceImport()
                ok(buildJsonObject { put("cancelled", cancelled); put("deviceImport", ApiJson.encodeToJsonElement(s.deviceImport)) })
            },
            Route(
                "PUT", "/api/v1/ecu-files/{name}", RouteAccess.Needs(Scope.Config), TAG, "putEcuFile",
                "Upload one ECU file",
                "Stores the body (the raw file, `Content-Type: application/octet-stream`, at most ${EcuFileStore.MAX_FILE_BYTES / EcuFileStore.MB} MB) as one `.prg` / `.grp` file, replacing a file of the same name. " +
                    "The body is written to a temporary file as it arrives (never kept in memory) and moved in place only once complete; the ECU catalog sees it at once. " +
                    "One file per call: to import a whole ECU folder, put it on the phone and use POST /api/v1/ecu-files/import-from-device. Send `Expect: 100-continue` (curl does) so that a refused upload is not sent. " +
                    "Example: `curl -T <sgbd>.prg -H 'Authorization: Bearer …' http://phone:8765/api/v1/ecu-files/<sgbd>.prg`.",
                params = listOf(nameParam),
                body = Body(S.str("The file content.", format = "binary"), contentType = "application/octet-stream"),
                reply = Reply(201, "Stored (200 when it replaced a file)", S.obj(null, listOf("file", "replaced", "fileCount"), false, null,
                    "file" to S.ref("EcuFile"), "replaced" to S.bool(), "fileCount" to S.int("Imported files now."))),
                errors = ecuErrors + mapOf(400 to "Not a plain .prg / .grp name (invalid_name), or the body ended early (truncated_body).", 413 to "Larger than ${EcuFileStore.MAX_FILE_BYTES / EcuFileStore.MB} MB."),
                upload = Upload(EcuFileStore.MAX_FILE_BYTES),
            ) { call ->
                val (file, replaced) = ecu { it.put(call.path("name"), call.bodyStream(), call.bodyLength) }
                ecuFilesChanged()
                ok(buildJsonObject {
                    put("file", ApiJson.encodeToJsonElement(file)); put("replaced", replaced); put("fileCount", ecu { it.files().size })
                }, if (replaced) 200 else 201)
            },
            Route(
                "DELETE", "/api/v1/ecu-files/{name}", RouteAccess.Needs(Scope.Config), TAG, "deleteEcuFile",
                "Delete one ECU file",
                "Removes one imported `.prg` / `.grp` file.",
                params = listOf(nameParam),
                reply = Reply(204, "Deleted"),
                errors = ecuErrors + mapOf(400 to "Invalid name.", 404 to "No such file."),
            ) { call ->
                if (!ecu { it.delete(call.path("name")) }) throw notFound("ecu_file_not_found", "No imported ECU file '${call.path("name").take(80)}'; see GET /api/v1/ecu-files?files=true")
                ecuFilesChanged()
                noContent()
            },
            Route(
                "DELETE", "/api/v1/ecu-files", RouteAccess.Needs(Scope.Config), TAG, "deleteEcuFiles",
                "Delete every ECU file",
                "Removes the whole imported ECU folder (the app disconnects from the car first). Live values, Tool32 and fault reading need ECU files again afterwards.",
                reply = Reply(description = "Deleted", schema = S.obj(null, listOf("deleted"), false, null, "deleted" to S.int("Files removed."))),
                errors = ecuErrors,
            ) {
                val n = ecu { it.deleteAll() }
                ecuFilesChanged()
                ok(buildJsonObject { put("deleted", n) })
            },

            // ------------------------------------------------------------ Bluetooth
            Route(
                "GET", "/api/v1/bluetooth", RouteAccess.Needs(Scope.Read), "Connection", "getBluetooth",
                "Bluetooth adapter: permission, choice, state",
                "Whether the app may use Bluetooth (`permission`), whether Bluetooth is on, the Deep OBD adapter chosen for `POST /connect {\"mode\":\"bluetooth\"}`, connect-on-start, and the last adapter state read while connected over Bluetooth.",
                reply = Reply(description = "Bluetooth", schema = S.ref("Bluetooth")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.bluetooth()) },
            Route(
                "GET", "/api/v1/bluetooth/devices", RouteAccess.Needs(Scope.Config), "Connection", "listBluetoothDevices",
                "Paired Bluetooth devices",
                "Devices paired with the phone, likely OBD adapters first. Pairing itself (PIN, usually 1234) happens in Android's Bluetooth settings on the phone: the app does not scan. Empty when Bluetooth is off.",
                reply = Reply(description = "Paired devices", schema = S.obj(null, listOf("devices", "enabled"), false, null,
                    "enabled" to S.bool("Bluetooth is on."), "devices" to S.arr(S.ref("PairedDevice")))),
                errors = mapOf(409 to "The Bluetooth permission is missing (code bluetooth_permission_missing).", 501 to "Not available."),
            ) {
                val view = backend.bluetooth()
                requireBluetoothPermission(view)
                ok(buildJsonObject { put("enabled", view.enabled); put("devices", ApiJson.encodeToJsonElement(backend.pairedDevices())) })
            },
            Route(
                "PUT", "/api/v1/bluetooth/adapter", RouteAccess.Needs(Scope.Config), "Connection", "selectBluetoothAdapter",
                "Choose the Bluetooth adapter",
                "Chooses a paired device (by `address`, from GET /api/v1/bluetooth/devices) as the Deep OBD adapter, and / or sets `connectOnStart` (connect to it when the app starts). Then POST /api/v1/connect `{\"mode\":\"bluetooth\"}`.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "address" to S.str("MAC address of a paired device.", "00:11:22:33:44:55"),
                    "connectOnStart" to S.bool("Connect to the adapter when the app starts.")),
                    example = buildJsonObject { put("address", "00:11:22:33:44:55"); put("connectOnStart", true) }),
                reply = Reply(description = "Bluetooth", schema = S.ref("Bluetooth")),
                errors = mapOf(400 to "Malformed body or address.", 404 to "No paired device with that address (code device_not_paired).", 409 to "Permission missing.", 422 to "Bluetooth LE only device.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("address", "connectOnStart")
                val address = b.string("address")?.trim()?.uppercase(Locale.ROOT)?.also {
                    if (!MAC.matches(it)) throw badRequest("'address' must be a MAC address such as 00:11:22:33:44:55")
                }
                if (address == null && b.bool("connectOnStart") == null) throw badRequest("Send 'address' and / or 'connectOnStart'")
                if (address != null) {
                    requireBluetoothPermission(backend.bluetooth())
                    val device = backend.pairedDevices().firstOrNull { it.address.equals(address, ignoreCase = true) }
                        ?: throw notFound("device_not_paired", "No paired device $address: pair the adapter in Android's Bluetooth settings on the phone (PIN usually 1234), then see GET /api/v1/bluetooth/devices")
                    if (device.lowEnergyOnly) throw ApiException(422, "low_energy_only", "${device.name} is a Bluetooth LE device: the Deep OBD adapter needs classic Bluetooth (serial port profile)")
                }
                okOf(backend.selectBluetoothAdapter(address, false, b.bool("connectOnStart")))
            },
            Route(
                "DELETE", "/api/v1/bluetooth/adapter", RouteAccess.Needs(Scope.Config), "Connection", "forgetBluetoothAdapter",
                "Forget the Bluetooth adapter",
                "No adapter is chosen any more (the device stays paired with the phone).",
                reply = Reply(description = "Bluetooth", schema = S.ref("Bluetooth")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.selectBluetoothAdapter(null, true, null)) },
            Route(
                "POST", "/api/v1/bluetooth/adapter/read", RouteAccess.Needs(Scope.Config), "Connection", "readBluetoothAdapter",
                "Read the adapter (type, firmware, voltage, CAN mode)",
                "Reads the Deep OBD adapter between two read cycles (the live reading goes on). Needs a connection through the adapter.",
                reply = Reply(description = "Adapter state", schema = S.ref("AdapterInfo")),
                errors = mapOf(409 to "Not connected over Bluetooth (code not_connected_bluetooth).", 501 to "Not available.", 503 to "The adapter did not answer."),
            ) {
                requireBluetoothConnection(backend.bluetooth())
                okOf(backend.readBluetoothAdapter() ?: throw ApiException(503, "adapter_not_identified", "The adapter is not identified yet; retry in a few seconds"))
            },
            Route(
                "PUT", "/api/v1/bluetooth/adapter/can-mode", RouteAccess.Needs(Scope.Config), "Connection", "setBluetoothCanMode",
                "Change the adapter's CAN mode (writes to the adapter)",
                "**Writes to the adapter** (it keeps the mode). `auto`: D-CAN, else K-line (factory setting); `kLineOnly`: older cars (K-line); `dCan500`: D-CAN only. " +
                    "The call waits (up to ${DiagApi.DEFAULT_CONFIRM_TIMEOUT_MS / 1000} s) for the user to accept or refuse on the phone (dialog or notification), for every single call; nobody answering means refused. Refused while the car is moving. Needs a connection through the adapter.",
                body = Body(S.obj(null, listOf("mode"), false, null, "mode" to S.str(enum = canModes.keys.toList())),
                    example = buildJsonObject { put("mode", "auto") }),
                reply = Reply(description = "Adapter state after the change", schema = S.ref("AdapterInfo")),
                errors = confirmErrors + mapOf(400 to "Unknown mode.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("mode")
                val name = b.string("mode") ?: throw badRequest("'mode' is required: ${canModes.keys.joinToString()}")
                val mode = canModes[name] ?: throw badRequest("'mode' must be one of ${canModes.keys.joinToString()}")
                requireBluetoothConnection(backend.bluetooth())
                confirmCarWrite(guard, CarWrite.AdapterCanMode(mode.code, name), confirmTimeoutMs, VehicleSpeed.kmh(backend.samples.value, meta, clock()))
                okOf(backend.writeCanMode(mode.code) ?: throw ApiException(503, "adapter_not_identified", "The adapter did not answer"))
            },

            // ------------------------------------------------------------ settings pages
            Route(
                "GET", "/api/v1/settings/gps", RouteAccess.Needs(Scope.Read), "Settings", "getGpsSettings",
                "GPS position settings and state",
                "The phone's GPS position recorded with the ECU values (measurements `gps/...`): switch, interval, permission and current state.",
                reply = Reply(description = "GPS", schema = S.ref("GpsSettings")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.gps()) },
            Route(
                "PATCH", "/api/v1/settings/gps", RouteAccess.Needs(Scope.Config), "Settings", "updateGpsSettings",
                "Change the GPS position settings",
                "`enabled` and / or `intervalMs` (one of `intervals`). Switching it on without the location permission is accepted but stays in state `noPermission` until the user allows the location on the phone (see GET /api/v1/permissions).",
                body = Body(S.obj(null, emptyList(), false, null, "enabled" to S.bool(), "intervalMs" to S.int(example = 1000)),
                    example = buildJsonObject { put("enabled", true); put("intervalMs", 2000) }),
                reply = Reply(description = "GPS", schema = S.ref("GpsSettings")),
                errors = mapOf(400 to "Unknown field or value.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("enabled", "intervalMs")
                val interval = b.long("intervalMs")?.also { v ->
                    val allowed = backend.gps().intervals
                    if (v !in allowed) throw badRequest("'intervalMs' must be one of ${allowed.joinToString()}")
                }
                okOf(backend.updateGps(b.bool("enabled"), interval))
            },
            Route(
                "GET", "/api/v1/settings/recording", RouteAccess.Needs(Scope.Read), "Settings", "getRecordingSettings",
                "CSV recording settings",
                "Folder, row rate and file name prefix of the CSV recordings.",
                reply = Reply(description = "Recording settings", schema = S.ref("RecordingSettings")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.recordingSettings()) },
            Route(
                "PATCH", "/api/v1/settings/recording", RouteAccess.Needs(Scope.Config), "Settings", "updateRecordingSettings",
                "Change the CSV recording settings",
                "`rowIntervalMs` (one of `rowIntervals`; 0 = one row per read cycle), `filePrefix` (letters, digits, `-`, `_`, at most 40; empty = default) and `useDefaultFolder: true` (back to Documents/BimmerData/). " +
                    "Choosing another folder needs Android's folder picker, which only the user can use on the phone (Settings › Recording › Choose): sending a folder answers 403.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "rowIntervalMs" to S.int(example = 200), "filePrefix" to S.str(example = "mycar"), "useDefaultFolder" to S.bool()),
                    example = buildJsonObject { put("rowIntervalMs", 200); put("filePrefix", "mycar") }),
                reply = Reply(description = "Recording settings", schema = S.ref("RecordingSettings")),
                errors = mapOf(400 to "Unknown field or value.", 403 to "Tried to choose a folder (phone only).", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                listOf("folder", "folderUri").firstOrNull { it in b }?.let {
                    throw ApiException(403, "phone_only", "A recording folder can only be chosen on the phone (Settings › Recording › Choose: Android's folder picker); 'useDefaultFolder': true goes back to Documents/BimmerData/")
                }
                b.allowOnly("rowIntervalMs", "filePrefix", "useDefaultFolder")
                val rows = b.long("rowIntervalMs")?.also { v ->
                    val allowed = backend.recordingSettings().rowIntervals
                    if (v !in allowed) throw badRequest("'rowIntervalMs' must be one of ${allowed.joinToString()}")
                }
                val prefix = b.string("filePrefix")?.trim()?.also {
                    if (!PREFIX.matches(it)) throw badRequest("'filePrefix' may only have letters, digits, '-' and '_' (at most 40)")
                }
                val useDefault = b.bool("useDefaultFolder") ?: false
                okOf(backend.updateRecordingSettings(RecordingSettingsPatch(rows, prefix, useDefault)))
            },
            Route(
                "GET", "/api/v1/settings/translations", RouteAccess.Needs(Scope.Read), "Settings", "getTranslationSettings",
                "Translation of the ECU texts",
                "Language of the ECU texts (labels, fault texts), where the translation files come from and the last update. The token is never returned (`tokenSet`).",
                reply = Reply(description = "Translations", schema = S.ref("TranslationSettings")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.translations()) },
            Route(
                "PATCH", "/api/v1/settings/translations", RouteAccess.Needs(Scope.Config), "Settings", "updateTranslationSettings",
                "Change the translation settings",
                "`language` (empty = the phone's language, `de` = original texts, i.e. translation off, or any code such as `fr`), `source` (`url`, or `folder` once a folder was picked on the phone), `baseUrl` (files are read from `<baseUrl>/<language>/<sgbd>.csv`), `token` (write-only, sent as Bearer token; empty string deletes it), `selection` (`auto`: the car's ECUs and those shown; `chosen`: only `chosen`). " +
                    "Picking a folder needs Android's folder picker on the phone: sending a folder answers 403.",
                body = Body(S.obj(null, emptyList(), false, null,
                    "language" to S.str(example = "fr"), "source" to S.str(enum = listOf("url", "folder")), "baseUrl" to S.str(format = "uri"),
                    "token" to S.str("Write-only."), "selection" to S.str(enum = listOf("auto", "chosen")),
                    "chosen" to S.arr(S.str(example = "<sgbd>"), "SGBD names.", 0, MAX_CHOSEN)),
                    example = buildJsonObject { put("language", "fr") }),
                reply = Reply(description = "Translations", schema = S.ref("TranslationSettings")),
                errors = mapOf(400 to "Unknown field or value.", 403 to "Tried to pick a folder (phone only).", 409 to "source folder without a folder picked on the phone.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                listOf("folder", "folderUri").firstOrNull { it in b }?.let {
                    throw ApiException(403, "phone_only", "A translation folder can only be picked on the phone (Settings › Translations): Android's folder picker")
                }
                b.allowOnly("language", "source", "baseUrl", "token", "selection", "chosen")
                val language = b.string("language")?.trim()?.lowercase(Locale.ROOT)?.also {
                    if (it.isNotEmpty() && !LANGUAGE.matches(it)) throw badRequest("'language' must be empty (phone language), 'de' (original texts) or a language code such as 'fr'")
                }
                val source = b.string("source")?.also { s ->
                    if (s != "url" && s != "folder") throw badRequest("'source' must be url or folder")
                    if (s == "folder" && backend.translations().folder == null) throw ApiException(409, "phone_only", "No translation folder was picked on the phone yet (Settings › Translations › Folder)")
                }
                val baseUrl = b.string("baseUrl")?.trim()?.also { u ->
                    if (!(u.startsWith("http://") || u.startsWith("https://")) || u.length > 500 || u.any { it.isWhitespace() }) throw badRequest("'baseUrl' must be an http:// or https:// URL")
                }
                val token = b.string("token")?.trim()?.also { if (!secretOk(it)) throw badRequest("'token' must be at most 4096 characters, without line breaks") }
                val selection = b.string("selection")?.also { if (it != "auto" && it != "chosen") throw badRequest("'selection' must be auto or chosen") }
                val chosen = b.stringList("chosen")?.let { list ->
                    if (list.size > MAX_CHOSEN) throw badRequest("At most $MAX_CHOSEN ECUs in 'chosen'")
                    list.map { s ->
                        s.trim().lowercase(Locale.ROOT).removeSuffix(".prg").removeSuffix(".grp").takeIf { SGBD.matches(it) } ?: throw badRequest("Invalid SGBD name '$s' in 'chosen'")
                    }.distinct()
                }
                okOf(backend.updateTranslations(TranslationsPatch(language, source, baseUrl, token, selection, chosen)))
            },
            Route(
                "POST", "/api/v1/settings/translations/update", RouteAccess.Needs(Scope.Config), "Settings", "updateTranslationsNow",
                "Download the translation files again",
                "Like \"Update\" on the phone: fetches the files of the selected ECUs again, in the background. Follow `updating` and `lastUpdate` with GET /api/v1/settings/translations.",
                reply = Reply(202, "Update started", S.ref("TranslationSettings")),
                errors = mapOf(501 to "Not available."),
            ) { okOf(backend.updateTranslationsNow(), 202) },

            // ------------------------------------------------------------ Telegraf export (rest of the page)
            Route(
                "PUT", "/api/v1/export/telegraf/auth", RouteAccess.Needs(Scope.Config), "Export", "setTelegrafAuth",
                "Set the Telegraf authentication (write-only secrets)",
                "`auth`: `none`, `token` (InfluxDB v2 style `Authorization: Token <token>`) or `basic` (`username` + `password`). The token / password is stored encrypted on the phone and never returned (GET /api/v1/export/telegraf shows `secretSet`). " +
                    "An absent `token` / `password` keeps the stored one; an empty string deletes it. The queue is retried at once.",
                body = Body(S.obj(null, listOf("auth"), false, null,
                    "auth" to S.str(enum = listOf("none", "token", "basic")), "username" to S.str("Basic auth only."),
                    "token" to S.str("auth token only; write-only."), "password" to S.str("auth basic only; write-only.")),
                    example = buildJsonObject { put("auth", "token"); put("token", "<token>") }),
                reply = Reply(description = "Telegraf export", schema = S.ref("TelegrafExport")),
                errors = mapOf(400 to "Unknown field, wrong value or a secret that does not match `auth`.", 501 to "Not available."),
            ) { call ->
                val b = call.bodyObject()
                b.allowOnly("auth", "username", "token", "password")
                val auth = b.string("auth") ?: throw badRequest("'auth' is required: none, token or basic")
                val secret = when (auth) {
                    "none" -> {
                        listOf("username", "token", "password").firstOrNull { it in b }?.let { throw badRequest("'$it' does not go with auth none") }
                        null
                    }
                    "token" -> {
                        listOf("username", "password").firstOrNull { it in b }?.let { throw badRequest("'$it' goes with auth basic; auth token takes 'token'") }
                        b.string("token")
                    }
                    "basic" -> {
                        if ("token" in b) throw badRequest("'token' goes with auth token; auth basic takes 'username' and 'password'")
                        b.string("password")
                    }
                    else -> throw badRequest("'auth' must be none, token or basic")
                }
                secret?.let { if (!secretOk(it)) throw badRequest("The token / password must be at most 4096 characters, without line breaks") }
                val user = b.string("username")?.trim()?.also {
                    if (it.length > 200 || ':' in it || it.any { c -> c < ' ' }) throw badRequest("'username' must be at most 200 characters, without ':' or control characters")
                }
                okOf(backend.setTelegrafAuth(TelegrafAuthPatch(auth, user, secret)))
            },
            Route(
                "DELETE", "/api/v1/export/telegraf/queue", RouteAccess.Needs(Scope.Config), "Export", "clearTelegrafQueue",
                "Delete the points waiting on the phone",
                "Like \"Clear\" on the phone: the queued points are deleted without being sent (the CSV recordings keep them). Cannot be undone.",
                reply = Reply(description = "Telegraf export", schema = S.ref("TelegrafExport")),
                errors = mapOf(501 to "Not available."),
            ) {
                backend.clearTelegrafQueue()
                okOf(backend.telegraf() ?: notAvailable("The Telegraf export"))
            },
            Route(
                "POST", "/api/v1/export/telegraf/rejected/retry", RouteAccess.Needs(Scope.Config), "Export", "retryTelegrafRejected",
                "Send the refused batches again",
                "Puts the batches Telegraf refused for good (HTTP 400) back in the queue, e.g. after fixing the Telegraf configuration.",
                reply = Reply(description = "Result", schema = S.obj(null, listOf("requeued", "export"), false, null,
                    "requeued" to S.int("Batches put back."), "export" to S.ref("TelegrafExport"))),
                errors = mapOf(501 to "Not available."),
            ) {
                val n = backend.retryTelegrafRejected()
                ok(buildJsonObject { put("requeued", n); put("export", ApiJson.encodeToJsonElement(backend.telegraf() ?: notAvailable("The Telegraf export"))) })
            },
            Route(
                "GET", "/api/v1/export/telegraf/rejected", RouteAccess.Needs(Scope.Config), "Export", "downloadTelegrafRejected",
                "Download the refused batches",
                "The batches Telegraf refused for good, as one gzip file of line protocol (`zcat` gives every line).",
                reply = Reply(description = "gzip file", schema = S.str("gzip", format = "binary"), contentType = "application/gzip"),
                errors = mapOf(404 to "No refused batch.", 413 to "Larger than ${ApiService.MAX_DOWNLOAD / 1_000_000} MB.", 501 to "Not available."),
            ) {
                val bytes = backend.openTelegrafRejected()?.use { readCapped(it, ApiService.MAX_DOWNLOAD) }
                    ?: throw notFound("no_rejected", "Telegraf refused no batch")
                if (bytes.isEmpty()) throw notFound("no_rejected", "Telegraf refused no batch")
                HttpResponse.Bytes(200, "application/gzip", bytes, mapOf("Content-Disposition" to "attachment; filename=\"bimmerdata-rejected.lp.gz\""))
            },
            Route(
                "DELETE", "/api/v1/export/telegraf/rejected", RouteAccess.Needs(Scope.Config), "Export", "deleteTelegrafRejected",
                "Delete the refused batches",
                "Deletes the batches Telegraf refused for good. Cannot be undone.",
                reply = Reply(description = "Telegraf export", schema = S.ref("TelegrafExport")),
                errors = mapOf(501 to "Not available."),
            ) {
                backend.deleteTelegrafRejected()
                okOf(backend.telegraf() ?: notAvailable("The Telegraf export"))
            },
        )
    }

    private fun readCapped(input: java.io.InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw ApiException(413, "too_large", "The refused batches are larger than ${max / 1_000_000} MB; export them on the phone instead")
        }
        return out.toByteArray()
    }

    private fun secretOk(s: String) = s.length <= 4096 && s.none { it == '\r' || it == '\n' || it == '\u0000' }

    companion object {
        const val TAG = "Setup"
        const val MAX_CHOSEN = 200
        private val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
        private val PREFIX = Regex("[A-Za-z0-9_\\-]{0,40}")
        private val LANGUAGE = Regex("[a-z]{2,3}")
        private val SGBD = Regex("[a-z0-9_\\-]{1,64}")

        /** OpenAPI schemas of these routes. */
        internal fun schemas(): Map<String, JsonObject> {
            val skipped = S.obj("An entry of the import folder that was not imported.", listOf("path", "reason"), false, null,
                "path" to S.str("Path in the folder."),
                "reason" to S.str(enum = listOf("notEcuFile", "invalidName", "duplicate", "tooLarge", "empty", "symbolicLink", "notRegularFile", "outsideFolder", "tooDeep")))
            val ecuFile = S.obj("One imported ECU file.", listOf("name", "sizeBytes", "modifiedMs"), false, null,
                "name" to S.str(example = "<sgbd>.prg"), "sizeBytes" to S.int(format = "int64"), "modifiedMs" to S.int(format = "int64"))
            val deviceImport = S.obj("Progress / result of the last import from the device folder (since the app started).", listOf("phase"), false, null,
                "phase" to S.str(enum = listOf("idle", "running", "done", "failed")),
                "mode" to S.str(enum = listOf("replace", "merge")), "deleteSource" to S.bool(),
                "filesTotal" to S.int(), "filesDone" to S.int(), "bytesTotal" to S.int(format = "int64"), "bytesDone" to S.int(format = "int64"),
                "copied" to S.int("Files copied (new or changed)."), "unchanged" to S.int("Files already imported with the same size and time."),
                "skipped" to S.arr(skipped, "First ${EcuFileStore.MAX_LISTED} skipped entries."), "skippedCount" to S.int(),
                "sourceDeleted" to S.int("Files deleted from the folder (deleteSource)."),
                "current" to S.str("File being copied."), "error" to S.str("Why it failed."),
                "startedAtMs" to S.int(format = "int64"), "finishedAtMs" to S.int(format = "int64"))
            return mapOf(
                "Permission" to S.obj("A permission, special access or system switch.", listOf("id", "kind", "status", "neededFor", "howToGrant"), false, null,
                    "id" to S.str(enum = listOf("bluetoothConnect", "notifications", "location", "localNetwork", "bluetoothOn", "locationOn", "usbDevice")),
                    "permission" to S.str("Android permission name.", "android.permission.BLUETOOTH_CONNECT"),
                    "kind" to S.str(enum = listOf("runtime", "systemSetting", "perDevice")),
                    "status" to S.str(enum = listOf("granted", "missing", "notRequired", "on", "off", "askedOnConnect")),
                    "blocking" to S.bool("A feature that is switched on needs it and it is missing."),
                    "neededFor" to S.str(), "howToGrant" to S.str("What the user taps on the phone.")),
                "EcuFile" to ecuFile,
                "DeviceImport" to deviceImport,
                "EcuFiles" to S.obj("Imported ECU files.", listOf("fileCount", "totalBytes", "freeBytes", "reserveBytes", "maxFileBytes", "deviceImport"), false, null,
                    "fileCount" to S.int(), "totalBytes" to S.int(format = "int64"),
                    "lastChangeMs" to S.int("Last import or change, epoch ms.", format = "int64"),
                    "freeBytes" to S.int("Free space of the app storage.", format = "int64"),
                    "reserveBytes" to S.int("Free space always kept; uploads and imports stop before it.", format = "int64"),
                    "maxFileBytes" to S.int("Largest file accepted.", format = "int64"),
                    "importDir" to S.str("Folder read by POST /api/v1/ecu-files/import-from-device.", "/storage/emulated/0/Android/data/<package>/files/ecu-import"),
                    "importDirFiles" to S.int("ECU files waiting in importDir."),
                    "deviceImport" to S.ref("DeviceImport"),
                    "files" to S.arr(ecuFile, "With ?files=true.")),
                "Bluetooth" to S.obj("Bluetooth adapter.", listOf("permission", "enabled", "connectOnStart", "connected"), false, null,
                    "permission" to S.str("BLUETOOTH_CONNECT (Nearby devices).", enum = listOf("granted", "missing", "notRequired")),
                    "enabled" to S.bool("Bluetooth is on."),
                    "adapter" to S.obj("Chosen adapter; absent when none.", listOf("address"), false, null, "name" to S.str(example = "Deep OBD BMW"), "address" to S.str(example = "00:11:22:33:44:55")),
                    "connectOnStart" to S.bool(), "connected" to S.bool("Connected through the adapter now."),
                    "adapterInfo" to S.ref("AdapterInfo")),
                "PairedDevice" to S.obj("A device paired with the phone.", listOf("name", "address", "likelyAdapter", "lowEnergyOnly", "selected"), false, null,
                    "name" to S.str(example = "Deep OBD BMW"), "address" to S.str(example = "00:11:22:33:44:55"),
                    "likelyAdapter" to S.bool("Name looks like an OBD adapter."), "lowEnergyOnly" to S.bool("Bluetooth LE only: not usable."), "selected" to S.bool()),
                "AdapterInfo" to S.obj("Deep OBD adapter state.", listOf("type", "firmware"), false, null,
                    "type" to S.str(), "firmware" to S.str(example = "0.12"), "serial" to S.str(), "voltage" to S.num("Car battery voltage.", 12.6),
                    "canMode" to S.str(enum = listOf("auto", "kLineOnly", "dCan500", "can100", "unknown")), "canModeCode" to S.int(),
                    "reachesKLine" to S.bool("False when the mode cannot reach K-line cars.")),
                "GpsSettings" to S.obj("GPS position.", listOf("enabled", "intervalMs", "intervals", "permission", "locationOn", "state"), false, null,
                    "enabled" to S.bool(), "intervalMs" to S.int(), "intervals" to S.arr(S.int()),
                    "permission" to S.str(enum = listOf("granted", "missing")), "locationOn" to S.bool("The phone's location is on."),
                    "state" to S.str(enum = listOf("off", "idle", "noPermission", "locationOff", "searching", "fix")),
                    "fixAtMs" to S.int(format = "int64"), "accuracyM" to S.num()),
                "RecordingSettings" to S.obj("CSV recording settings.", listOf("folder", "customFolder", "rowIntervalMs", "rowIntervals", "filePrefix", "nextFileName"), false, null,
                    "folder" to S.str(example = "Documents/BimmerData/"), "customFolder" to S.bool("A folder was picked on the phone."),
                    "rowIntervalMs" to S.int("0 = one row per read cycle."), "rowIntervals" to S.arr(S.int()),
                    "filePrefix" to S.str(example = "bimmerdata"), "nextFileName" to S.str()),
                "TranslationSettings" to S.obj("Translation of the ECU texts.", listOf("enabled", "language", "effectiveLanguage", "source", "baseUrl", "tokenSet", "selection", "chosen", "updating"), false, null,
                    "enabled" to S.bool("False when the language is `de` (original texts)."),
                    "language" to S.str("Empty = the phone's language."), "effectiveLanguage" to S.str(example = "fr"),
                    "languages" to S.arr(S.str(), "Offered on the phone (any code works)."),
                    "source" to S.str(enum = listOf("url", "folder")), "baseUrl" to S.str(), "defaultBaseUrl" to S.str(),
                    "tokenSet" to S.bool("A token is stored (never returned)."), "folder" to S.str("Folder picked on the phone."),
                    "selection" to S.str(enum = listOf("auto", "chosen")), "chosen" to S.arr(S.str()), "cachedEcus" to S.arr(S.str()),
                    "updating" to S.bool(),
                    "lastUpdate" to S.obj(null, listOf("updated", "notFound", "errors", "atMs"), false, null,
                        "updated" to S.arr(S.str()), "notFound" to S.arr(S.str()), "errors" to S.arr(S.str()), "atMs" to S.int(format = "int64")),
                    "lastError" to S.str()),
            )
        }
    }
}
