/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api

import io.github.jonathangtd.bimmerdata.api.http.HttpResponse
import io.github.jonathangtd.bimmerdata.dyno.DynoControl
import io.github.jonathangtd.bimmerdata.dyno.DynoCsv
import io.github.jonathangtd.bimmerdata.dyno.DynoDefaults
import io.github.jonathangtd.bimmerdata.dyno.DynoInputs
import io.github.jonathangtd.bimmerdata.dyno.DynoLiveState
import io.github.jonathangtd.bimmerdata.dyno.DynoNotReady
import io.github.jonathangtd.bimmerdata.dyno.DynoRun
import io.github.jonathangtd.bimmerdata.dyno.DynoRuns
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.dyno.DynoParams
import io.github.jonathangtd.bimmerdata.live.dyno.DynoResult
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The virtual dyno: inputs per car, arm / start / stop, the runs with their curves (computed again
 * at each read, so a corrected car mass applies to every run), CSV export. Part of [ApiService];
 * pure JVM ([DynoControl] is the phone's runtime).
 */
internal class DynoRoutes(
    private val backend: ApiBackend,
    private val meta: (String) -> Measurement?,
) {
    private fun dyno(): DynoControl = backend.dyno()

    private fun ok(element: kotlinx.serialization.json.JsonElement, status: Int = 200) = json(status, element)

    private fun invalid(message: String) = ApiException(422, "invalid_dyno_input", message)

    private fun selectedCar(): Vehicle = backend.cars().selected
        ?: throw ApiException(409, "no_car", "No car selected: add one with POST /api/v1/cars")

    private fun car(call: Call): Vehicle {
        val id = call.query("car") ?: return selectedCar()
        return backend.cars().car(id) ?: throw notFound("car_not_found", "No car '$id'; see GET /api/v1/cars")
    }

    private fun stateJson(s: DynoLiveState) = buildJsonObject {
        put("phase", s.phase.name.lowercase())
        s.carId?.let { put("carId", it) }
        put("runSamples", s.runSamples)
        s.runStartedAtMs?.let { put("runStartedAtMs", it) }
        put("readings", s.readings)
        s.rpm?.let { put("rpm", it) }
        s.pedal?.let { put("pedal", it) }
        s.lastRunId?.let { put("lastRunId", it) }
        s.lastDiscardedSamples?.let { put("lastDiscardedSamples", it) }
    }

    private fun measurementJson(id: String?, candidates: List<Measurement>) = id?.let { i ->
        val m = candidates.firstOrNull { it.id == i } ?: meta(i)
        buildJsonObject {
            put("id", i)
            m?.let { put("label", it.label); put("unit", it.unit) }
        }
    } ?: JsonNull

    private fun inputsJson(carId: String): JsonObject {
        val d = dyno()
        val saved = d.inputs(carId)
        val resolved = d.resolvedInputs(carId)
        val candidates = d.candidates()
        return buildJsonObject {
            put("carId", carId)
            putJsonObject("saved") {
                put("rpm", saved.rpm?.let(::JsonPrimitive) ?: JsonNull)
                put("speed", saved.speed?.let(::JsonPrimitive) ?: JsonNull)
                put("pedal", saved.pedal?.let(::JsonPrimitive) ?: JsonNull)
                put("overlay", saved.overlay?.let(::JsonPrimitive) ?: JsonNull)
                put("drivelineLossPct", saved.drivelineLossPct)
            }
            putJsonObject("used") {
                put("rpm", measurementJson(resolved.rpm, candidates))
                put("speed", measurementJson(resolved.speed, candidates))
                put("pedal", measurementJson(resolved.pedal, candidates))
                put("overlay", measurementJson(resolved.overlay, candidates))
                put("drivelineLossPct", resolved.drivelineLossPct)
            }
            putJsonArray("candidates") {
                for (m in candidates) addJsonObject {
                    put("id", m.id); put("label", m.label); put("unit", m.unit)
                    putJsonArray("roles") {
                        if (DynoDefaults.isRpm(m)) add("rpm")
                        if (DynoDefaults.isSpeed(m)) add("speed")
                        if (DynoDefaults.isPercent(m)) add("pedal")
                        if (DynoDefaults.isOverlay(m)) add("overlay")
                    }
                }
            }
        }
    }

    private fun resultJson(r: DynoResult, withCurve: Boolean, withSamples: Boolean) = buildJsonObject {
        put("ok", r.ok)
        r.problem?.let { put("problem", it.name) }
        put("warnings", JsonArray(r.warnings.map { JsonPrimitive(it.name) }))
        r.kmhPer1000Rpm?.let { put("kmhPer1000Rpm", round(it, 2)) }
        r.ratioSpreadPct?.let { put("ratioSpreadPct", round(it, 2)) }
        put("samples", r.samples)
        put("sampleRateHz", round(r.sampleRateHz, 2))
        put("durationS", round(r.durationS, 2))
        r.rpmFrom?.let { put("rpmFrom", it.roundToInt()) }
        r.rpmTo?.let { put("rpmTo", it.roundToInt()) }
        r.peakWheelPowerCh?.let { put("peakWheelPowerCh", round(it, 1)) }
        r.peakWheelPowerRpm?.let { put("peakWheelPowerRpm", it.roundToInt()) }
        r.peakEnginePowerCh?.let { put("peakEnginePowerCh", round(it, 1)) }
        r.peakTorqueNm?.let { put("peakTorqueNm", round(it, 1)) }
        r.peakTorqueRpm?.let { put("peakTorqueRpm", it.roundToInt()) }
        r.peakOverlayTorqueNm?.let { put("peakOverlayTorqueNm", round(it, 1)) }
        r.peakOverlayPowerCh?.let { put("peakOverlayPowerCh", round(it, 1)) }
        if (withCurve) {
            put("binRpm", r.binRpm)
            putJsonArray("points") {
                for (p in r.points) addJsonObject {
                    put("rpm", p.rpm.roundToInt()); put("wheelPowerCh", round(p.wheelPowerCh, 1)); put("torqueNm", round(p.torqueNm, 1))
                    put("enginePowerCh", round(p.enginePowerCh, 1))
                    p.overlayTorqueNm?.let { put("overlayTorqueNm", round(it, 1)) }
                    p.overlayPowerCh?.let { put("overlayPowerCh", round(it, 1)) }
                    put("samples", p.samples)
                }
            }
        }
        if (withSamples) putJsonArray("processed") {
            for (p in r.processed) addJsonObject {
                put("tMs", p.tMs); put("rpmRead", p.rpmRaw); put("rpm", round(p.rpm, 1)); put("speedKmh", round(p.speedKmh, 2))
                put("accelMs2", round(p.accelMs2, 3)); put("wheelPowerCh", round(p.wheelPowerCh, 1)); put("torqueNm", round(p.torqueNm, 1))
                p.overlay?.let { put("overlay", round(it, 1)) }
            }
        }
    }

    private fun runJson(run: DynoRun, withCurve: Boolean, withSamples: Boolean): JsonObject {
        val car = backend.cars().car(run.carId)
        val result = DynoRuns.result(run, car?.physics)
        return buildJsonObject {
            put("id", run.id)
            put("carId", run.carId)
            car?.let { put("carLabel", it.label) }
            put("startedAt", Instant.ofEpochMilli(run.startedAtMs).toString())
            put("startedAtMs", run.startedAtMs)
            put("endedAtMs", run.endedAtMs)
            put("end", run.end)
            put("manual", run.manual)
            if (run.note.isNotEmpty()) put("note", run.note)
            run.profileName?.let { put("profile", it) }
            putJsonObject("inputs") {
                put("rpm", run.rpm.id); put("speed", run.speed.id)
                run.pedal?.let { put("pedal", it.id) }
                run.overlay?.let { putJsonObject("overlay") { put("id", it.id); put("label", it.label); put("unit", it.unit) } }
            }
            putJsonObject("physicsWhenMeasured") {
                run.massKg?.let { put("massKg", it) }
                run.dragAreaM2?.let { put("dragAreaM2", it) }
                run.rollingResistance?.let { put("rollingResistance", it) }
                run.airDensity?.let { put("airDensity", it) }
            }
            put("drivelineLossPct", run.drivelineLossPct)
            put("result", resultJson(result, withCurve, withSamples))
            if (withSamples) putJsonObject("raw") {
                fun series(name: String, s: io.github.jonathangtd.bimmerdata.dyno.DynoSeries) = putJsonObject(name) {
                    put("t", JsonArray(s.t.map(::JsonPrimitive))); put("v", JsonArray(s.v.map(::JsonPrimitive)))
                }
                series("rpm", run.rpmSeries); series("speed", run.speedSeries); series("pedal", run.pedalSeries); series("overlay", run.overlaySeries)
            }
        }
    }

    private fun requireRun(id: String): DynoRun = dyno().book().run(id) ?: throw notFound("run_not_found", "No dyno run '$id'; see GET /api/v1/dyno/runs")

    private fun control(op: () -> Unit): HttpResponse {
        try {
            op()
        } catch (e: DynoNotReady) {
            throw ApiException(409, e.code, e.message ?: e.code)
        }
        return ok(buildJsonObject { put("state", stateJson(dyno().state.value)) })
    }

    /** `{rpm, speed, pedal, overlay, drivelineLossPct}` merged into [old] (null = the default). */
    private fun parseInputs(b: JsonObject, old: DynoInputs): DynoInputs {
        b.allowOnly("rpm", "speed", "pedal", "overlay", "drivelineLossPct")
        fun id(key: String, current: String?, ok: (Measurement) -> Boolean, what: String): String? {
            if (key !in b) return current
            val v = b.string(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!v.contains('/')) throw invalid("'$key' must be a measurement id such as '<sgbd>/<id>' or 'gps/speed'")
            val m = dyno().candidates().firstOrNull { it.id == v } ?: meta(v)
            if (m != null && !ok(m)) throw invalid("'$key' must be $what ('$v' is in '${m.unit}')")
            return v
        }
        val loss = if ("drivelineLossPct" in b) {
            val el = b["drivelineLossPct"]
            if (el == null || el is JsonNull) DynoParams.DEFAULT_LOSS_PCT
            else (el as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: throw invalid("'drivelineLossPct' must be a number")
        } else old.drivelineLossPct
        if (loss !in DynoParams.LOSS_RANGE) throw invalid("'drivelineLossPct' must be in ${DynoParams.LOSS_RANGE.start}..${DynoParams.LOSS_RANGE.endInclusive}")
        return DynoInputs(
            rpm = id("rpm", old.rpm, DynoDefaults::isRpm, "an engine speed (unit rpm)"),
            speed = id("speed", old.speed, DynoDefaults::isSpeed, "a vehicle speed (unit km/h)"),
            pedal = id("pedal", old.pedal, DynoDefaults::isPercent, "a pedal position (unit %)"),
            overlay = id("overlay", old.overlay, DynoDefaults::isOverlay, "a torque (Nm) or a power (ch, PS, kW, hp)"),
            drivelineLossPct = loss,
        )
    }

    fun routes(): List<Route> {
        val carQuery = Param("car", "query", "Car id (default: the selected car).", S.str(), required = false)
        val runParam = Param("run", "path", "Run id (see GET /api/v1/dyno/runs).", S.str(), example = JsonPrimitive("a1b2c3d4e5"))
        val stateReply = Reply(description = "Dyno state", schema = S.obj(null, listOf("state"), false, null, "state" to S.ref("DynoState")))
        val controlErrors = mapOf(409 to "Not ready: no_car, car_mass_missing (set physics.massKg with PUT /api/v1/cars/{car}) or dyno_inputs_missing (no engine speed / speed value).", 501 to "Not available.")
        return listOf(
            Route(
                "GET", "/api/v1/dyno", RouteAccess.Needs(Scope.Read), "Dyno", "dynoStatus",
                "Virtual dyno: state, car values, inputs",
                "The virtual dyno measures the power reaching the wheels from the car's acceleration during a full-throttle pull in one gear: " +
                    "power = m·a·v + ½·ρ·CdA·v³ + Crr·m·g·v, with v = gear ratio × engine speed (fitted on the speed readings). The road is assumed flat (do runs both ways), rotating parts are not counted. " +
                    "It needs the car's mass (PUT /api/v1/cars/{car} `physics`), an engine speed (rpm) and a vehicle speed (km/h, an ECU's or `gps/speed`); a pedal value (%) lets an armed dyno start runs by itself (pedal ≥ 90 % and the engine speed rising), " +
                    "an overlay value (a torque or a power the car gives, e.g. a computed power) is drawn next to the measured curve. " +
                    "It reads at the pace the acquisition allows (a profile holding only these values reads the fastest).",
                reply = Reply(description = "Dyno", schema = S.obj(null, listOf("state", "car", "inputs"), false, null,
                    "state" to S.ref("DynoState"),
                    "car" to S.obj(null, listOf("id", "label", "physics", "ready"), false, null,
                        "id" to S.str(), "label" to S.str(), "physics" to S.ref("CarPhysics"),
                        "ready" to S.bool("A mass is set and the inputs are found."),
                        "problems" to S.arr(S.str(), "What is missing (codes car_mass_missing, dyno_inputs_missing).")),
                    "inputs" to S.ref("DynoInputs"))),
                errors = mapOf(409 to "No car selected (no_car).", 501 to "Not available."),
            ) { _ ->
                val car = selectedCar()
                val d = dyno()
                val used = d.resolvedInputs(car.id)
                val problems = buildList {
                    if (car.physics.massKg == null) add("car_mass_missing")
                    if (used.rpm == null || used.speed == null) add("dyno_inputs_missing")
                }
                ok(buildJsonObject {
                    put("state", stateJson(d.state.value))
                    putJsonObject("car") {
                        put("id", car.id); put("label", car.label); put("physics", CarRoutes.physicsJson(car.physics))
                        put("ready", problems.isEmpty()); put("problems", JsonArray(problems.map(::JsonPrimitive)))
                    }
                    put("inputs", inputsJson(car.id))
                })
            },
            Route(
                "GET", "/api/v1/dyno/settings", RouteAccess.Needs(Scope.Read), "Dyno", "dynoSettings",
                "Dyno inputs of a car",
                "The values the dyno reads for the car: `saved` (null = default) and `used` (the defaults filled in from the active profile: its first engine speed, its first ECU speed else the GPS, a pedal only when its name says so), and the `candidates` with the roles they suit.",
                params = listOf(carQuery),
                reply = Reply(description = "Inputs", schema = S.ref("DynoInputs")),
                errors = mapOf(404 to "Unknown car.", 409 to "No car selected.", 501 to "Not available."),
            ) { call -> ok(inputsJson(car(call).id)) },
            Route(
                "PUT", "/api/v1/dyno/settings", RouteAccess.Needs(Scope.Config), "Dyno", "updateDynoSettings",
                "Choose the dyno inputs of a car",
                "Fields given replace the saved ones (null = back to the default). Ids are measurement ids (`<sgbd>/<id>`, `gps/speed`, `calc/<key>`); a known one must have the right unit. " +
                    "`drivelineLossPct` (0..40, default 15) only changes the estimated engine power. Takes effect at the next arm.",
                params = listOf(carQuery),
                body = Body(S.obj(null, emptyList(), false, null,
                    "rpm" to S.str("Engine speed (rpm).", "<sgbd>/<id>"), "speed" to S.str("Vehicle speed (km/h).", "gps/speed"),
                    "pedal" to S.str("Pedal position (%), optional: lets the dyno start runs by itself."),
                    "overlay" to S.str("A torque (Nm) or power (ch/PS/kW/hp) to draw next to the measured curve, optional.", "calc/power"),
                    "drivelineLossPct" to S.num("Driveline loss for the engine estimate (%).", 15.0)),
                    example = buildJsonObject { put("rpm", "<sgbd>/<id>"); put("speed", "<sgbd>/<id2>"); put("pedal", "<sgbd>/<id3>"); put("overlay", "calc/power") }),
                reply = Reply(description = "Inputs", schema = S.ref("DynoInputs")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown car.", 409 to "No car selected.", 422 to "Invalid input (invalid_dyno_input).", 501 to "Not available."),
            ) { call ->
                val car = car(call)
                val inputs = parseInputs(call.bodyObject(), dyno().inputs(car.id))
                dyno().setInputs(car.id, inputs)
                ok(inputsJson(car.id))
            },
            Route(
                "POST", "/api/v1/dyno/arm", RouteAccess.Needs(Scope.Config), "Dyno", "armDyno",
                "Arm the dyno",
                "Reads the inputs of the selected car and waits for a pull: with a pedal input a run starts at full throttle (≥ 90 %) with the engine speed rising and ends on pedal release (< 80 %), engine speed drop, gear change or after 60 s; then it waits for the next one (the pedal has to be released in between). Without a pedal input use /start and /stop. Each run is kept (GET /api/v1/dyno/runs).",
                reply = stateReply, errors = controlErrors,
            ) { _ -> control { dyno().arm() } },
            Route(
                "POST", "/api/v1/dyno/disarm", RouteAccess.Needs(Scope.Config), "Dyno", "disarmDyno",
                "Disarm the dyno",
                "Stops waiting for pulls and stops reading the dyno's inputs; a run in progress is kept.",
                reply = stateReply, errors = mapOf(501 to "Not available."),
            ) { _ -> control { dyno().disarm() } },
            Route(
                "POST", "/api/v1/dyno/start", RouteAccess.Needs(Scope.Config), "Dyno", "startDynoRun",
                "Start a run now",
                "Starts a run at once (manual), armed or not; it ends with /stop or like an armed run.",
                reply = stateReply, errors = controlErrors,
            ) { _ -> control { dyno().start() } },
            Route(
                "POST", "/api/v1/dyno/stop", RouteAccess.Needs(Scope.Config), "Dyno", "stopDynoRun",
                "End the run in progress",
                "Ends and keeps the run in progress (too short ones are left out: see `lastDiscardedSamples`); stays armed.",
                reply = stateReply, errors = mapOf(501 to "Not available."),
            ) { _ -> control { dyno().stop() } },
            Route(
                "GET", "/api/v1/dyno/runs", RouteAccess.Needs(Scope.Read), "Dyno", "listDynoRuns",
                "Dyno runs",
                "The runs of a car, newest first, with their peaks (computed now with the car's current values). `car=all` lists every car's.",
                params = listOf(Param("car", "query", "Car id, or `all` (default: the selected car).", S.str(), required = false)),
                reply = Reply(description = "Runs", schema = S.obj(null, listOf("runs"), false, null, "runs" to S.arr(S.ref("DynoRun")))),
                errors = mapOf(404 to "Unknown car.", 409 to "No car selected.", 501 to "Not available."),
            ) { call ->
                val book = dyno().book()
                val runs = if (call.query("car") == "all") book.runs else book.runsOf(car(call).id)
                ok(buildJsonObject { put("runs", JsonArray(runs.sortedByDescending { it.startedAtMs }.map { runJson(it, withCurve = false, withSamples = false) })) })
            },
            Route(
                "GET", "/api/v1/dyno/runs/{run}", RouteAccess.Needs(Scope.Read), "Dyno", "getDynoRun",
                "One dyno run with its curve",
                "The run, its curve by engine speed bins (`result.points`: wheel power, torque at the engine without transmission losses, estimated engine power, the overlay as torque and power) and its peaks. `raw=true` adds every reading and every smoothed sample.",
                params = listOf(runParam, Param("raw", "query", "Add the readings and the smoothed samples (default false).", S.bool(), required = false)),
                reply = Reply(description = "Run", schema = S.ref("DynoRun")),
                errors = mapOf(404 to "Unknown run.", 501 to "Not available."),
            ) { call -> ok(runJson(requireRun(call.path("run")), withCurve = true, withSamples = call.queryBool("raw", false))) },
            Route(
                "PUT", "/api/v1/dyno/runs/{run}", RouteAccess.Needs(Scope.Config), "Dyno", "renameDynoRun",
                "Name a run",
                "Sets the run's note (≤ ${DynoRuns.MAX_NOTE_LENGTH} characters, e.g. \"4th, northbound\").",
                params = listOf(runParam),
                body = Body(S.obj(null, listOf("note"), false, null, "note" to S.str(example = "4th, northbound"))),
                reply = Reply(description = "Run", schema = S.ref("DynoRun")),
                errors = mapOf(400 to "Malformed body.", 404 to "Unknown run.", 501 to "Not available."),
            ) { call ->
                val id = call.path("run")
                requireRun(id)
                val b = call.bodyObject()
                b.allowOnly("note")
                dyno().renameRun(id, b.string("note").orEmpty())
                ok(runJson(requireRun(id), withCurve = false, withSamples = false))
            },
            Route(
                "DELETE", "/api/v1/dyno/runs/{run}", RouteAccess.Needs(Scope.Config), "Dyno", "deleteDynoRun",
                "Delete a run",
                "Deletes the run and its readings.",
                params = listOf(runParam),
                reply = Reply(description = "Deleted", schema = S.obj(null, listOf("deleted"), false, null, "deleted" to S.bool())),
                errors = mapOf(404 to "Unknown run.", 501 to "Not available."),
            ) { call ->
                if (!dyno().deleteRun(call.path("run"))) throw notFound("run_not_found", "No dyno run '${call.path("run")}'")
                ok(buildJsonObject { put("deleted", true) })
            },
            Route(
                "GET", "/api/v1/dyno/runs/{run}/csv", RouteAccess.Needs(Scope.Read), "Dyno", "dynoRunCsv",
                "A run as CSV",
                "Header lines (`#`: car, inputs, car values, ratio, rate), every smoothed sample, then the curve by engine speed bins.",
                params = listOf(runParam),
                reply = Reply(description = "CSV file", schema = S.str("CSV text"), contentType = "text/csv; charset=utf-8"),
                errors = mapOf(404 to "Unknown run.", 501 to "Not available."),
            ) { call ->
                val run = requireRun(call.path("run"))
                val car = backend.cars().car(run.carId)
                val text = DynoCsv.encode(run, DynoRuns.result(run, car?.physics), car?.label)
                HttpResponse.Bytes(200, "text/csv; charset=utf-8", text.toByteArray(Charsets.UTF_8), mapOf("Content-Disposition" to "attachment; filename=\"${DynoCsv.fileName(run)}\""))
            },
        )
    }

    companion object {
        private fun round(v: Double, d: Int): Double {
            var f = 1.0
            repeat(d) { f *= 10 }
            return Math.round(v * f) / f
        }

        internal fun schemas(): Map<String, JsonObject> = mapOf(
            "DynoState" to S.obj(
                "Live state of the dyno.", listOf("phase", "runSamples", "readings"), false, null,
                "phase" to S.str("idle, armed (waiting for a pull) or running (measuring).", enum = listOf("idle", "armed", "running")),
                "carId" to S.str("Car being measured."), "runSamples" to S.int("Engine speed readings of the run in progress."),
                "runStartedAtMs" to S.int(format = "int64"), "readings" to S.int("Readings of the inputs since armed."),
                "rpm" to S.num("Latest engine speed."), "pedal" to S.num("Latest pedal position."),
                "lastRunId" to S.str("Last run kept."), "lastDiscardedSamples" to S.int("The last run was too short to keep: its readings."),
            ),
            "DynoInputs" to S.obj(
                "Dyno inputs of a car.", listOf("carId", "saved", "used", "candidates"), false, null,
                "carId" to S.str(),
                "saved" to S.obj("As saved (null = default).", emptyList(), true, null),
                "used" to S.obj("What the dyno reads: each {id, label, unit} or null.", emptyList(), true, null),
                "candidates" to S.arr(S.obj(null, listOf("id", "label", "unit", "roles"), false, null,
                    "id" to S.str(), "label" to S.str(), "unit" to S.str(), "roles" to S.arr(S.str(enum = listOf("rpm", "speed", "pedal", "overlay"))))),
            ),
            "DynoRun" to S.obj(
                "A dyno run; `result` is computed at each read with the car's current values.", listOf("id", "carId", "startedAt", "end", "result"), false, null,
                "id" to S.str(), "carId" to S.str(), "carLabel" to S.str(), "startedAt" to S.str(format = "date-time"),
                "startedAtMs" to S.int(format = "int64"), "endedAtMs" to S.int(format = "int64"),
                "end" to S.str("Why it ended.", enum = listOf("PedalReleased", "RpmDropped", "GearChanged", "Stopped", "Disarmed", "TooLong", "NoData")),
                "manual" to S.bool(), "note" to S.str(), "profile" to S.str("Active profile when measured."),
                "inputs" to S.obj(null, emptyList(), true, null), "physicsWhenMeasured" to S.ref("CarPhysics"),
                "drivelineLossPct" to S.num(),
                "result" to S.obj("Curve and peaks.", listOf("ok", "warnings", "samples"), true, null,
                    "ok" to S.bool(),
                    "problem" to S.str("Why there is no curve.", enum = listOf("NoMass", "TooFewSamples", "NoSpeed", "NotOneGear", "NotAccelerating")),
                    "warnings" to S.arr(S.str(enum = listOf("FewSamples", "LowSampleRate", "AssumedFlat"))),
                    "kmhPer1000Rpm" to S.num("Gear ratio fitted."), "ratioSpreadPct" to S.num("RMS difference between speed and ratio × rpm."),
                    "samples" to S.int(), "sampleRateHz" to S.num("Engine speed readings per second."),
                    "peakWheelPowerCh" to S.num(), "peakWheelPowerRpm" to S.int(), "peakEnginePowerCh" to S.num("Wheel power + driveline loss."),
                    "peakTorqueNm" to S.num("Torque at the engine, transmission losses not counted."), "peakTorqueRpm" to S.int(),
                    "peakOverlayTorqueNm" to S.num(), "peakOverlayPowerCh" to S.num(),
                    "points" to S.arr(S.obj(null, listOf("rpm", "wheelPowerCh", "torqueNm", "enginePowerCh", "samples"), false, null,
                        "rpm" to S.int(), "wheelPowerCh" to S.num(), "torqueNm" to S.num(), "enginePowerCh" to S.num(),
                        "overlayTorqueNm" to S.num(), "overlayPowerCh" to S.num(), "samples" to S.int()))),
            ),
        )
    }
}
