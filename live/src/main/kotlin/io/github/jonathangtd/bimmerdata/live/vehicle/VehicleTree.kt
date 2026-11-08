/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import java.util.Locale

/** Function area of an ECU, for the vehicle tree (ISTA-like). */
enum class EcuCategory { Gateway, Powertrain, Chassis, Body, Climate, Infotainment, Safety, Other }

/** One branch of the tree: a title key ([category] or [bus]) and its ECUs in address order. */
data class TreeGroup(val category: EcuCategory?, val bus: String?, val ecus: List<VehicleEcu>)

/**
 * Builds the vehicle tree automatically, without any per-car data:
 *
 * - [byBus]: the real topology when the scan could ask every bus separately
 *   ([Vehicle.busTopology]): PT-CAN, K-CAN, MOST, SI-bus, plus the ECUs that answered none of
 *   them (gateway first).
 * - [byFunction] (always available): function areas from the group file of each ECU (D_MOTOR ->
 *   powertrain, D_DSC -> chassis...), then from its short name, then from the address range.
 */
object VehicleTree {

    fun byFunction(vehicle: Vehicle): List<TreeGroup> =
        vehicle.ecus.groupBy { categoryOf(it) }.entries
            .sortedBy { it.key.ordinal }
            .map { (cat, ecus) -> TreeGroup(cat, null, ecus.sortedBy { it.address }) }

    /** Null without bus information. */
    fun byBus(vehicle: Vehicle): List<TreeGroup>? {
        if (!vehicle.busTopology) return null
        val order = listOf("PT-CAN", "K-CAN", "MOST", "SI", "LIN", "VD-FLEXRAY")
        return vehicle.ecus.groupBy { it.bus }.entries
            .sortedBy { (bus, _) -> if (bus == null) -1 else order.indexOf(bus).let { if (it < 0) order.size else it } }
            .map { (bus, ecus) -> TreeGroup(if (bus == null) EcuCategory.Gateway else null, bus, ecus.sortedBy { it.address }) }
    }

    fun categoryOf(ecu: VehicleEcu): EcuCategory {
        ecu.group?.let { g -> byGroup[g.lowercase(Locale.ROOT)]?.let { return it } }
        val name = ecu.name.uppercase(Locale.ROOT)
        byName.entries.firstOrNull { (prefix, _) -> name.startsWith(prefix) }?.let { return it.value }
        return when (ecu.address) {
            0x00 -> EcuCategory.Gateway
            in 0x01..0x0F -> EcuCategory.Safety
            in 0x10..0x1F -> EcuCategory.Powertrain
            in 0x20..0x3F -> EcuCategory.Chassis
            in 0x60..0x6F -> EcuCategory.Infotainment
            in 0x78..0x7A -> EcuCategory.Climate
            else -> EcuCategory.Other
        }
    }

    private fun groups(category: EcuCategory, vararg names: String) = names.map { it to category }

    /** Group file -> area (group files of the E-series BMW-FAST cars). */
    private val byGroup: Map<String, EcuCategory> = listOf(
        groups(EcuCategory.Gateway, "d_zgm", "d_mostgw", "d_can", "d_can2", "d_can_60", "d_canbus"),
        groups(
            EcuCategory.Powertrain, "d_motor", "d_motor2", "d_egs", "d_egs_7x", "d_ekp", "d_vgsg", "d_vvt", "d_vvt2",
            "d_gws", "d_ldm", "d_ldm2", "d_pgs",
        ),
        groups(
            EcuCategory.Chassis, "d_dsc", "d_abskwp", "d_eps", "d_afs", "d_ars", "d_edc", "d_edcshl", "d_edcshr", "d_edcsvl",
            "d_edcsvr", "d_ehc", "d_emf", "d_rdc", "d_acc", "d_qsg", "d_svt", "d_tlc", "d_fla", "d_nve", "d_nvc",
        ),
        groups(
            EcuCategory.Body, "d_cas", "d_kbm", "d_lm", "d_fzd", "d_szl", "d_shd", "d_pdc", "d_sitz_f", "d_sitz_b", "d_spmbt",
            "d_spmft", "d_tmbt", "d_tmft", "d_tmbth", "d_tmfth", "d_cvm", "d_rls", "d_dwa", "d_sine", "d_ahm", "d_hkl", "d_xen_l",
            "d_xen_r", "d_alc", "d_zke3bt", "d_zke3ft", "d_zke3pm", "d_zke3sb", "d_zke3sd", "d_zke_gm", "d_bzm", "d_bzmf", "d_szm",
            "d_fah", "d_bfh", "d_fas", "d_bfs", "d_smac", "d_kfs", "d_pow", "d_albvf", "d_albvb", "d_frm", "d_jbe",
        ),
        groups(EcuCategory.Climate, "d_klima", "d_klima2", "d_shzh", "d_zuheiz", "d_ihka", "d_ihkr"),
        groups(
            EcuCategory.Infotainment, "d_kombi", "d_mmi", "d_ccc", "d_nav", "d_tel", "d_ask", "d_amp", "d_anttu", "d_radio",
            "d_cid", "d_cidf", "d_cidf2", "d_hud", "d_svs", "d_video", "d_ec", "d_ecf", "d_cdc", "d_cdcdsp", "d_mmc",
            "d_mmcdsp", "d_iboc", "d_dab", "d_ispb", "d_khi", "d_vsw", "d_rse", "d_rfk", "d_most", "d_most60", "d_most65",
            "d_digtun", "d_mmif", "d_mmifc", "d_fbi", "d_beho",
        ),
        groups(
            EcuCategory.Safety, "d_sim", "d_sbsl", "d_sbsl2", "d_sbsr", "d_sbsr2", "d_sasl", "d_sasr", "d_stvl", "d_stvl2",
            "d_stvr", "d_stvr2", "d_ssfa", "d_ssbf", "d_ssh", "d_sfz", "d_secur1", "d_secur2", "d_ecall",
        ),
    ).flatten().toMap()

    /** Short name prefix -> area, when the group is unknown. */
    private val byName: Map<String, EcuCategory> = linkedMapOf(
        "DME" to EcuCategory.Powertrain, "DDE" to EcuCategory.Powertrain, "EGS" to EcuCategory.Powertrain, "EKP" to EcuCategory.Powertrain,
        "DSC" to EcuCategory.Chassis, "EPS" to EcuCategory.Chassis, "AFS" to EcuCategory.Chassis, "EDC" to EcuCategory.Chassis,
        "CAS" to EcuCategory.Body, "FRM" to EcuCategory.Body, "JB" to EcuCategory.Gateway, "KGM" to EcuCategory.Gateway,
        "KOMBI" to EcuCategory.Infotainment, "RAD" to EcuCategory.Infotainment, "CCC" to EcuCategory.Infotainment,
        "MASK" to EcuCategory.Infotainment, "KLIMA" to EcuCategory.Climate, "IHK" to EcuCategory.Climate,
        "MRS" to EcuCategory.Safety, "ACSM" to EcuCategory.Safety, "SIM" to EcuCategory.Safety,
    )
}
