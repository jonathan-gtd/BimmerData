/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import java.util.Locale

/**
 * Whether a stored fault is there right now, from its status text (F_VORHANDEN_TEXT and the like,
 * German as the SGBD returns it, sometimes English): what the fault list shows as a chip.
 */
enum class FaultPresence {
    /** Present now ("Fehler momentan vorhanden ..."). */
    Present,

    /** Stored but not present now ("... nicht vorhanden, aber bereits gespeichert"). */
    Stored,

    /** Entry kept by the ECU but the fault never occurred ("Fehler bisher nicht aufgetreten"). */
    NeverOccurred,

    /** No status text, or one this rule does not know. */
    Unknown,
    ;

    companion object {
        fun of(f: Fault): FaultPresence = of(f.presence)

        fun of(text: String?): FaultPresence {
            val t = text?.lowercase(Locale.ROOT)?.replace("ä", "ae")?.replace("ö", "oe")?.replace("ü", "ue")?.trim()
            if (t.isNullOrEmpty()) return Unknown
            return when {
                "bisher nicht aufgetreten" in t || "not yet occurred" in t || "never occurred" in t -> NeverOccurred
                "nicht vorhanden" in t || "not present" in t || "not currently present" in t -> Stored
                "vorhanden" in t || "present" in t -> Present
                "gespeichert" in t || "stored" in t -> Stored
                else -> Unknown
            }
        }

        /** The most urgent of [faults]: present before stored before never occurred. */
        fun worst(faults: List<Fault>): FaultPresence =
            faults.map { of(it) }.minByOrNull { order(it) } ?: Unknown

        /** Sort order: most urgent first. */
        fun order(p: FaultPresence): Int = when (p) {
            Present -> 0
            Unknown -> 1
            Stored -> 2
            NeverOccurred -> 3
        }
    }
}
