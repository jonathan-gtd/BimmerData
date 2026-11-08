/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

/** How the ECU list of a [Vehicle] was obtained. */
enum class ScanMethod {
    /** One functional identification (IDENT_FUNKTIONAL of the vehicle SGBD): every ECU answers. */
    Functional,

    /** Group files identified one after the other (no functional job or no functional answer). */
    Sequential,
}

/** One car the user diagnosed. Several are kept (one per VIN). */
data class Vehicle(
    /** Stable key: the 7-character short VIN when known, else a generated id. */
    val id: String,
    /** Name shown to the user, editable (default: series and short VIN). */
    val label: String,
    /** 17-character VIN (CAS), null if only the short VIN could be read. */
    val vin: String? = null,
    /** Short VIN (last 7 characters, FG_NR of C_FG_LESEN_FUNKTIONAL). */
    val vinShort: String? = null,
    /** Series family as detected, e.g. "E8x/E9x", or the user's choice. */
    val series: String? = null,
    /** Vehicle SGBD used for the functional jobs, lower case without extension, e.g. "e89x". */
    val seriesSgbd: String? = null,
    /** [seriesSgbd] was chosen by the user (kept by the next scans), not detected. */
    val seriesByUser: Boolean = false,
    val ecus: List<VehicleEcu> = emptyList(),
    val scannedAtMs: Long? = null,
    val scanMethod: ScanMethod? = null,
    /** ECUs carry a [VehicleEcu.bus] from the per-bus functional identification. */
    val busTopology: Boolean = false,
) {
    fun ecu(address: Int): VehicleEcu? = ecus.firstOrNull { it.address == address }
}

/** One ECU found in the car. */
data class VehicleEcu(
    /** Diagnosis address (ID_SG_ADR), e.g. 0x12 for the engine. */
    val address: Int,
    /** Short name from the vehicle SGBD's GROBNAME table ("DME/DDE", "CAS"...). */
    val name: String,
    /** Long name (ECU_NAME / STEUERGERAET of T_GRTB), in German as in the SGBDs. */
    val description: String? = null,
    /** Group file without extension, lower case, e.g. "d_motor". */
    val group: String? = null,
    /** Variant SGBD without extension, lower case, e.g. "msv70"; null if it could not be identified. */
    val sgbd: String? = null,
    /** [sgbd] exists in the imported ECU folder (else faults can only be shown as codes). */
    val sgbdAvailable: Boolean = false,
    /** Bus name ("PT-CAN", "K-CAN", "MOST", "SI"...) if [Vehicle.busTopology]. */
    val bus: String? = null,
    /** Identification data (part number, hardware, software, date, supplier...). */
    val ident: List<NamedValue> = emptyList(),
)

/** A named value as shown to the user (EDIABAS result name and formatted value, optional unit). */
data class NamedValue(val name: String, val value: String, val unit: String? = null)

/** One fault memory entry. */
data class Fault(
    /** Fault code (F_ORT_NR), e.g. 0x2F08. */
    val code: Int,
    /** Fault location text from the SGBD (F_ORT_TEXT), empty if unknown. */
    val text: String,
    /** Symptom / fault type (F_SYMPTOM_TEXT or F_ART1_TEXT). */
    val symptom: String? = null,
    /** "present / stored" text (F_VORHANDEN_TEXT). */
    val presence: String? = null,
    /** Warning lamp text (F_WARNUNG_TEXT). */
    val warning: String? = null,
    /** Further fault type texts (F_READY_TEXT, F_ARTi_TEXT...). */
    val extra: List<String> = emptyList(),
    /** Raw fault data (F_HEX_CODE) as hex. */
    val hex: String? = null,
) {
    val codeHex: String get() = String.format("%04X", code)
}

enum class EcuFaultStatus {
    /** Read, no fault stored. */
    Ok,

    /** Read, at least one fault stored. */
    Faults,

    /** No answer (ECU absent, asleep, wiring...). */
    NotResponding,

    /** The ECU answered but the job failed (JOB_STATUS not OKAY, interpreter error). */
    Error,
}

/** Fault memory of one ECU at [readAtMs]. */
data class EcuFaults(
    val address: Int,
    val status: EcuFaultStatus,
    val faults: List<Fault> = emptyList(),
    /** Error text, or a note (e.g. faults only as codes because the SGBD is missing). */
    val message: String? = null,
    val readAtMs: Long = 0,
)

/** Environment data of one occurrence of a fault. */
data class FaultOccurrence(val mileageKm: Long?, val values: List<NamedValue>)

/** FS_LESEN_DETAIL of one fault. */
data class FaultDetail(
    val fault: Fault,
    /** Frequency counter (F_HFK), null if the ECU has none. */
    val frequency: Int? = null,
    /** Logistic counter (F_LZ): driving cycles left before the fault is forgotten. */
    val logisticCounter: Int? = null,
    /** OBD code ("P0113") and its text, if the ECU is emission-relevant. */
    val pcode: String? = null,
    val pcodeText: String? = null,
    /** Fault types (F_ARTi_TEXT). */
    val kinds: List<String> = emptyList(),
    /** First / last occurrence... as the SGBD reports them (one result set each). */
    val occurrences: List<FaultOccurrence> = emptyList(),
)
