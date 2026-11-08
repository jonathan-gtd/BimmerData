/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.vehicle

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaultStatus
import io.github.jonathangtd.bimmerdata.live.vehicle.EcuFaults
import io.github.jonathangtd.bimmerdata.live.vehicle.Fault
import io.github.jonathangtd.bimmerdata.live.vehicle.NamedValue
import io.github.jonathangtd.bimmerdata.live.vehicle.ScanMethod
import io.github.jonathangtd.bimmerdata.live.vehicle.Vehicle
import io.github.jonathangtd.bimmerdata.live.vehicle.VehicleEcu
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Everything the Faults tab keeps between launches: the user's cars and their last fault read. */
data class Garage(
    val vehicles: List<Vehicle> = emptyList(),
    val currentId: String? = null,
    /** Last fault read per vehicle id. */
    val faults: Map<String, List<EcuFaults>> = emptyMap(),
    val mode: LinkMode = LinkMode.Simulation,
) {
    val current: Vehicle? get() = vehicles.firstOrNull { it.id == currentId }
}

/** How the Faults tab reaches the car. */
enum class LinkMode { Usb, Simulation }

/** JSON file in app storage (atomic writes). */
class VehicleStore(context: Context, private val file: AtomicFile = AtomicFile(File(context.filesDir, "vehicles.json"))) {

    fun load(): Garage {
        val text = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return Garage()
        return runCatching { GarageJson.decode(text) }
            .onFailure { Log.w(Tag, "Unreadable vehicles.json, starting empty", it) }
            .getOrDefault(Garage())
    }

    fun save(garage: Garage) {
        val out = file.startWrite()
        try {
            out.write(GarageJson.encode(garage).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(Tag, "Could not save the vehicles", e)
        }
    }

    private companion object {
        const val Tag = "VehicleStore"
    }
}

/** Storage format (version 1); the live model classes stay free of serialization annotations. */
object GarageJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Serializable
    internal data class GarageDto(
        val version: Int = 1,
        val vehicles: List<VehicleDto> = emptyList(),
        val currentId: String? = null,
        val faults: Map<String, List<EcuFaultsDto>> = emptyMap(),
        val mode: String? = null,
    )

    @Serializable
    internal data class VehicleDto(
        val id: String,
        val label: String,
        val vin: String? = null,
        val vinShort: String? = null,
        val series: String? = null,
        val seriesSgbd: String? = null,
        val seriesByUser: Boolean = false,
        val ecus: List<EcuDto> = emptyList(),
        val scannedAtMs: Long? = null,
        val scanMethod: String? = null,
        val busTopology: Boolean = false,
    )

    @Serializable
    internal data class EcuDto(
        val address: Int,
        val name: String,
        val description: String? = null,
        val group: String? = null,
        val sgbd: String? = null,
        val sgbdAvailable: Boolean = false,
        val bus: String? = null,
        val ident: List<ValueDto> = emptyList(),
    )

    @Serializable
    internal data class ValueDto(val name: String, val value: String, val unit: String? = null)

    @Serializable
    internal data class EcuFaultsDto(
        val address: Int,
        val status: String,
        val faults: List<FaultDto> = emptyList(),
        val message: String? = null,
        val readAtMs: Long = 0,
    )

    @Serializable
    internal data class FaultDto(
        val code: Int,
        val text: String,
        val symptom: String? = null,
        val presence: String? = null,
        val warning: String? = null,
        val extra: List<String> = emptyList(),
        val hex: String? = null,
    )

    fun encode(garage: Garage): String = json.encodeToString(
        GarageDto.serializer(),
        GarageDto(
            vehicles = garage.vehicles.map { it.toDto() },
            currentId = garage.currentId,
            faults = garage.faults.mapValues { (_, list) -> list.map { it.toDto() } },
            mode = garage.mode.name,
        ),
    )

    fun decode(text: String): Garage {
        val dto = json.decodeFromString(GarageDto.serializer(), text)
        val vehicles = dto.vehicles.map { it.toModel() }
        return Garage(
            vehicles = vehicles,
            currentId = dto.currentId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id,
            faults = dto.faults.filterKeys { id -> vehicles.any { it.id == id } }.mapValues { (_, list) -> list.mapNotNull { it.toModel() } },
            mode = LinkMode.entries.firstOrNull { it.name == dto.mode } ?: LinkMode.Simulation,
        )
    }

    private fun Vehicle.toDto() =
        VehicleDto(id, label, vin, vinShort, series, seriesSgbd, seriesByUser, ecus.map { it.toDto() }, scannedAtMs, scanMethod?.name, busTopology)

    private fun VehicleDto.toModel() = Vehicle(
        id, label, vin, vinShort, series, seriesSgbd, seriesByUser, ecus.map { it.toModel() }, scannedAtMs,
        ScanMethod.entries.firstOrNull { it.name == scanMethod }, busTopology,
    )

    private fun VehicleEcu.toDto() = EcuDto(address, name, description, group, sgbd, sgbdAvailable, bus, ident.map { ValueDto(it.name, it.value, it.unit) })

    private fun EcuDto.toModel() = VehicleEcu(address, name, description, group, sgbd, sgbdAvailable, bus, ident.map { NamedValue(it.name, it.value, it.unit) })

    private fun EcuFaults.toDto() = EcuFaultsDto(address, status.name, faults.map { FaultDto(it.code, it.text, it.symptom, it.presence, it.warning, it.extra, it.hex) }, message, readAtMs)

    private fun EcuFaultsDto.toModel(): EcuFaults? {
        val s = EcuFaultStatus.entries.firstOrNull { it.name == status } ?: return null
        return EcuFaults(address, s, faults.map { Fault(it.code, it.text, it.symptom, it.presence, it.warning, it.extra, it.hex) }, message, readAtMs)
    }
}
