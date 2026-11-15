/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dyno

import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementSource
import io.github.jonathangtd.bimmerdata.live.dyno.DynoCapture
import io.github.jonathangtd.bimmerdata.live.dyno.DynoEnd
import io.github.jonathangtd.bimmerdata.live.dyno.TimedValue
import io.github.jonathangtd.bimmerdata.live.vehicle.CarPhysics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Synthetic runs and an in-memory [DynoControl] for the app tests. */
object DynoTestData {
    val rpm = Measurement("ecu/0x0001", "ecu", "Engine speed", "rpm", MeasurementSource.MeasurementTable(1, "STAT_RPM_WERT"))
    val speed = Measurement("ecu/0x0002", "ecu", "Speed", "km/h", MeasurementSource.MeasurementTable(2, "STAT_SPEED_WERT"))
    val pedal = Measurement("ecu/0x0003", "ecu", "Pedal position", "%", MeasurementSource.MeasurementTable(3, "STAT_PEDAL_WERT"))
    val load = Measurement("ecu/0x0004", "ecu", "Load", "%", MeasurementSource.MeasurementTable(4, "STAT_LOAD_WERT"))
    val torque = Measurement("ecu/0x0005", "ecu", "Torque", "Nm", MeasurementSource.MeasurementTable(5, "STAT_TORQUE_WERT"))
    val oil = Measurement("ecu/0x0006", "ecu", "Oil", "°C", MeasurementSource.MeasurementTable(6, "STAT_OIL_WERT"))
    val gps = Measurement("gps/speed", "gps", "GPS speed", "km/h", MeasurementSource.Sensor("gps", "speed"))
    val candidates = listOf(oil, load, rpm, gps, speed, pedal, torque)

    /** A pull in one gear (25 km/h per 1000 rpm) of a 1500 kg car with [peakKw] at 6000 rpm, read about 6 times a second. */
    fun capture(startMs: Long = 1_700_000_000_000L, peakKw: Double = 120.0): DynoCapture {
        val p = CarPhysics(massKg = 1500.0).resolved()!!
        val k = 0.025
        var v = k * 2000 / 3.6
        var t = 0L
        var next = 0L
        val rpm = ArrayList<TimedValue>()
        val speed = ArrayList<TimedValue>()
        val pedal = ArrayList<TimedValue>()
        while (t < 60_000) {
            val r = v * 3.6 / k
            if (r >= 6200) break
            if (t >= next) {
                rpm += TimedValue(startMs + t, r.roundToInt().toDouble())
                speed += TimedValue(startMs + t, (v * 3.6).roundToInt().toDouble())
                pedal += TimedValue(startMs + t, 99.0)
                next = t + 160
            }
            val kw = peakKw * (r / 6000) * (2 - r / 6000)
            v += (kw * 1000 - 0.5 * p.airDensity * p.dragAreaM2 * v * v * v - p.rollingResistance * p.massKg * 9.80665 * v) / (p.massKg * v) * 0.001
            t++
        }
        return DynoCapture(rpm, speed, pedal)
    }

    fun run(carId: String, startMs: Long = 1_700_000_000_000L, massKg: Double? = 1500.0, id: String = "run$startMs"): DynoRun = DynoRuns.of(
        carId, capture(startMs), startMs, startMs + 10_000, DynoEnd.PedalReleased, manual = false, profileName = "Dyno",
        rpm = DynoRunInput(rpm.id, rpm.label, rpm.unit), speed = DynoRunInput(speed.id, speed.label, speed.unit),
        pedal = DynoRunInput(pedal.id, pedal.label, pedal.unit), overlay = null,
        physics = CarPhysics(massKg = massKg), lossPct = 15.0, id = id,
    )
}

/** In-memory dyno: arming needs the car's mass ([massOf]) and resolvable inputs. */
class FakeDyno(private val massOf: () -> Double?) : DynoControl {
    var book = DynoBook()
    private val _state = MutableStateFlow(DynoLiveState())
    override val state: StateFlow<DynoLiveState> = _state

    override fun book() = book
    override fun candidates() = DynoTestData.candidates
    override fun resolvedInputs(carId: String) = DynoDefaults.resolve(inputs(carId), candidates())
    override fun setInputs(carId: String, inputs: DynoInputs) { book = book.copy(inputs = book.inputs + (carId to inputs)) }

    override fun arm() {
        if (massOf() == null) throw DynoNotReady("car_mass_missing", "mass")
        _state.value = DynoLiveState(DynoPhase.Armed, carId = "car")
    }

    override fun disarm() { _state.value = DynoLiveState() }
    override fun start() { arm(); _state.value = _state.value.copy(phase = DynoPhase.Running) }
    override fun stop() { if (_state.value.phase == DynoPhase.Running) _state.value = _state.value.copy(phase = DynoPhase.Armed) }

    override fun deleteRun(id: String): Boolean {
        if (book.run(id) == null) return false
        book = DynoRuns.delete(book, id)
        return true
    }

    override fun renameRun(id: String, note: String): Boolean {
        if (book.run(id) == null) return false
        book = DynoRuns.rename(book, id, note)
        return true
    }
}
