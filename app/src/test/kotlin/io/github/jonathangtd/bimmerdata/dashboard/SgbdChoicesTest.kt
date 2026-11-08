/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.live.SgbdInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SgbdChoicesTest {
    private val all = listOf(
        SgbdInfo("cas", "CAS · Keine Bemerkung", 243),
        SgbdInfo("gs19d", "Getriebesteuerung GS19.11 · SGBD fuer GS19.11", 523),
        SgbdInfo("kombi87", "KOMBI E87 / E90", 120),
        SgbdInfo("msv70", "MSV70 fuer N52 mit EWS3 oder CAS", 1197),
        SgbdInfo("ihka87", "IHKA · Muster-SGBD für PL2", 418),
    )

    @Test
    fun `detected ECUs first, only those present in the folder, in detection order`() {
        val c = SgbdChoices.of(all, listOf("MSV70.PRG", "kombi87", "unknown", "msv70"))
        assertEquals(listOf("msv70", "kombi87"), c.detected.map { it.name })
        assertEquals(all, c.all)
    }

    @Test
    fun `search by name and by description, every word, accents and case ignored`() {
        fun names(q: String) = SgbdChoices.filter(all, q).map { it.name }
        assertEquals(all.map { it.name }, names("  "))
        assertEquals(listOf("kombi87"), names("kombi"))
        assertEquals(listOf("gs19d"), names("getriebe"))
        assertEquals(listOf("msv70"), names("n52"))
        assertEquals(listOf("kombi87"), names("E90 kombi"))
        assertEquals(listOf("ihka87"), names("fur pl2"), "ü folds to u")
        // a name match comes before a description match ("cas" is also in the MSV70 text)
        assertEquals(listOf("cas", "msv70"), names("cas"))
    }
}
