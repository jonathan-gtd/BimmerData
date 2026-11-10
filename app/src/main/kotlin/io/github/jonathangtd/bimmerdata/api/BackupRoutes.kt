/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.api.http.HttpServer
import io.github.jonathangtd.bimmerdata.backup.BackupCodec
import io.github.jonathangtd.bimmerdata.backup.BackupException
import io.github.jonathangtd.bimmerdata.backup.RestoreSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Full backup of the app (scope config): download it, put it back. Part of [ApiService]; pure JVM.
 * The file is the one of Settings › Backup on the phone (see [BackupCodec]); it holds the VINs of
 * the user's cars (the only place they leave the phone) but no token or password.
 */
internal class BackupRoutes(private val backend: ApiBackend, private val clock: () -> Long) {

    private fun apiError(e: BackupException): ApiException = when (e.code) {
        "not_a_backup" -> ApiException(400, e.code, e.message ?: e.code)
        "invalid_backup", "newer_version" -> ApiException(422, e.code, e.message ?: e.code)
        "busy" -> ApiException(409, e.code, e.message ?: e.code)
        else -> ApiException(500, e.code, e.message ?: e.code)
    }

    fun routes(): List<Route> = listOf(
        Route(
            "GET", "/api/v1/backup", RouteAccess.Needs(Scope.Config), TAG, "getBackup",
            "Full backup of the app",
            "One JSON file with everything needed to get the app back as it is after a reinstall: the cars **with their VIN** (the only place the VIN leaves the phone: keep the file private), " +
                "their ECU lists (detected and added by hand), last ECU detection and last fault read; every profile with its phone and Android Auto pages and its graphs; the selected car and active profile; " +
                "the connection, Bluetooth adapter, GPS, recording, Telegraf and Local API settings; the translation settings and the cached translation files (left out beyond ${BackupCodec.MAX_TRANSLATION_BYTES / (1024 * 1024)} MB, `translations.filesOmitted`). " +
                "Never a secret: no API token, no Telegraf token or password, no translation token. Not in it: the ECU files, the folders picked on the phone, the recordings, the Telegraf queue. " +
                "Same file as Settings › Backup › Export on the phone; put it back with POST /api/v1/backup/restore.",
            reply = Reply(description = "The backup file (`Content-Disposition: attachment`)", schema = S.ref("Backup")),
            errors = mapOf(501 to "Not available."),
        ) {
            val text = try {
                backend.backup()
            } catch (e: BackupException) {
                throw apiError(e)
            }
            HttpResponse.Bytes(
                200, HttpServer.JSON, text.toByteArray(Charsets.UTF_8),
                mapOf("Content-Disposition" to "attachment; filename=\"${BackupCodec.fileName(clock())}\""),
            )
        },
        Route(
            "POST", "/api/v1/backup/restore", RouteAccess.Needs(Scope.Config), TAG, "restoreBackup",
            "Put a backup back (replaces everything)",
            "The body is a backup file (GET /api/v1/backup or Settings › Backup › Export). **Replaces** the cars, profiles, graphs, settings and cached translation files with those of the file, " +
                "all or nothing: the whole file is checked first, the app disconnects from the car, then every part is replaced; if one fails, everything is put back as it was (`restore_failed`). " +
                "Needs `\"confirm\": true` at the top level of the file, or `?confirm=true` (`?dryRun=true` only checks the file and returns the summary). " +
                "Through the API, Settings › Local API (enable, port, scopes, HTTPS) stays as it is on the phone (`apiSettingsRestored`: false): only a restore on the phone puts it back. " +
                "The phone keeps its own API token and Telegraf secrets; `todo` lists what is still to be done by hand. " +
                "Example: `curl -X POST --data-binary @bimmerdata-backup.json -H 'Content-Type: application/json' -H \"Authorization: Bearer …\" 'http://phone:8765/api/v1/backup/restore?confirm=true'`.",
            params = listOf(
                Param("confirm", "query", "Replace everything (or `\"confirm\": true` in the body).", S.bool(default = false)),
                Param("dryRun", "query", "Only check the file and return what would be restored.", S.bool(default = false)),
            ),
            body = Body(S.ref("Backup"), description = "A backup file, at most ${BackupCodec.MAX_FILE_BYTES / (1024 * 1024)} MB."),
            reply = Reply(description = "What was restored", schema = S.ref("RestoreSummary")),
            errors = mapOf(
                400 to "Not JSON, not a backup file (not_a_backup; a profile file says so), or no confirmation (confirm_required).",
                409 to "An operation on the car runs or the app could not disconnect (busy).",
                413 to "Larger than ${BackupCodec.MAX_FILE_BYTES / (1024 * 1024)} MB.",
                422 to "Unreadable backup (invalid_backup) or made by a newer app (newer_version).",
                500 to "A part could not be written; nothing was changed (restore_failed).",
                501 to "Not available.",
            ),
            upload = Upload(BackupCodec.MAX_FILE_BYTES),
        ) { call ->
            if (call.bodyLength > BackupCodec.MAX_FILE_BYTES) throw ApiException(413, "too_large", "A backup is at most ${BackupCodec.MAX_FILE_BYTES / (1024 * 1024)} MB")
            val bytes = readAtMost(call.bodyStream(), BackupCodec.MAX_FILE_BYTES + 1)
            if (bytes.size > BackupCodec.MAX_FILE_BYTES) throw ApiException(413, "too_large", "A backup is at most ${BackupCodec.MAX_FILE_BYTES / (1024 * 1024)} MB")
            if (bytes.isEmpty()) throw badRequest("Send the backup file as the body")
            val text = bytes.toString(Charsets.UTF_8)
            val top = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            fun flag(name: String) = (top?.get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
            val dryRun = call.queryBool("dryRun", false) || flag("dryRun")
            val confirm = call.queryBool("confirm", false) || flag("confirm")
            if (!dryRun && !confirm) {
                throw ApiException(400, "confirm_required", "A restore replaces every car, profile and setting: send \"confirm\": true in the file or ?confirm=true (?dryRun=true to check the file first)")
            }
            val summary = try {
                backend.restoreBackup(text, dryRun)
            } catch (e: BackupException) {
                throw apiError(e)
            }
            json(200, ApiJson.encodeToJsonElement(RestoreSummary.serializer(), summary))
        },
    )

    companion object {
        const val TAG = "Backup"

        fun schemas(): Map<String, JsonObject> = mapOf(
            "Backup" to S.obj(
                "BimmerData backup file (format ${BackupCodec.FORMAT}, version ${BackupCodec.VERSION}).",
                listOf("format", "version", "garage", "profiles"), true, null,
                "format" to S.str(enum = listOf(BackupCodec.FORMAT)),
                "version" to S.int(min = 1, max = BackupCodec.VERSION.toLong()),
                "appVersion" to S.str(), "appVersionCode" to S.int(), "createdAt" to S.str(format = "date-time"), "createdAtMs" to S.int(),
                "garage" to S.obj("Cars with VIN, ECU lists, last detection and fault read, selected car (the app's vehicles.json).", additional = true),
                "profiles" to S.obj("Every profile with its pages and graphs, and the active one (the app's profiles.json).", additional = true),
                "settings" to S.obj("Connection, Bluetooth adapter, GPS, recording, Telegraf (no secret) and Local API (no token) settings.", additional = true),
                "translations" to S.obj("Translation settings (no token) and cached files `files` [{language, sgbd, csv}] (null: not included).", additional = true),
            ),
            "RestoreSummary" to S.obj(
                "What a restore put back.", listOf("cars", "profiles", "apiSettingsRestored"), false, null,
                "cars" to S.int(), "carsWithVin" to S.int(), "carsScanned" to S.int(), "profiles" to S.int(), "graphs" to S.int(),
                "activeProfile" to S.str(), "selectedCar" to S.str(),
                "translationFiles" to S.int("Translation files put back; absent when the backup had none (the cache was kept)."),
                "apiSettingsRestored" to S.bool("Always false through the API: Settings › Local API is only restored on the phone."),
                "createdAt" to S.str(format = "date-time"), "appVersion" to S.str(),
                "todo" to S.arr(S.str(), "What still has to be done by hand."),
                "dryRun" to S.bool(),
            ),
        )

        /** Up to [max] bytes of [input]. */
        private fun readAtMost(input: java.io.InputStream, max: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), max - out.size()).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
