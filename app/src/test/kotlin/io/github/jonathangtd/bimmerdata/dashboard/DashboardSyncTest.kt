/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The phone dashboard against layouts written elsewhere (REST API). */
class DashboardSyncTest {
    private val mine = Msv70Example.create().copy(name = "mine")
    private val theirs = DefaultDashboard.create("API").copy(name = "theirs")

    @Test
    fun `own writes are recognized and ignored`() {
        val ui = DashboardUi(dashboard = mine)
        assertSame(ui, DashboardSync.onSaved(ui, mine, lastPersisted = mine))
        // a debounced write of an older draft, while the user already changed more
        val draft = mine.copy(name = "draft 2")
        val editing = DashboardUi(dashboard = draft, editing = true)
        assertSame(editing, DashboardSync.onSaved(editing, mine, lastPersisted = mine))
    }

    @Test
    fun `not editing - an external layout replaces the shown one, page indexes clamped`() {
        val ui = DashboardUi(dashboard = mine, phonePage = 2, carPage = 2, undo = mine)
        val next = DashboardSync.onSaved(ui, theirs, lastPersisted = mine)
        assertEquals(theirs, next.dashboard)
        assertEquals(0, next.phonePage)
        assertEquals(0, next.carPage)
        assertNull(next.undo)
        assertNull(next.external)
    }

    @Test
    fun `editing - the draft stays and the external layout waits`() {
        val draft = mine.updatePage(Target.Phone, mine.phonePages[0].id) { it.copy(cards = it.cards.drop(1)) }
        val ui = DashboardUi(dashboard = draft, editing = true)
        val waiting = DashboardSync.onSaved(ui, theirs, lastPersisted = mine)
        assertEquals(draft, waiting.dashboard)
        assertEquals(theirs, waiting.external)

        // "Load it": the external layout is shown, the draft can be restored with undo
        val accepted = DashboardSync.accept(waiting)
        assertEquals(theirs, accepted.dashboard)
        assertEquals(draft, accepted.undo)
        assertNull(accepted.external)

        // the user keeps editing: the next own write replaced it on disk
        assertNull(DashboardSync.onOwnSave(waiting).external)
        assertEquals(draft, DashboardSync.onOwnSave(waiting).dashboard)
    }

    @Test
    fun `leaving the editor adopts the external layout unless an own change is pending`() {
        // no own change since the last write: the untouched draft must not overwrite the API's layout
        val untouched = DashboardUi(dashboard = mine, editing = false, external = theirs)
        assertEquals(theirs, DashboardSync.onEditingDone(untouched, lastPersisted = mine).dashboard)
        // an own change not yet written (debounce): it will be written, last writer wins
        val changed = mine.copy(phonePages = mine.phonePages + Page(name = "new", columns = 2, rows = 6, cards = listOf(Card(type = GaugeType.Trend, measurementIds = listOf("x/y")))))
        val pending = DashboardUi(dashboard = changed, external = theirs)
        val done = DashboardSync.onEditingDone(pending, lastPersisted = mine)
        assertEquals(changed, done.dashboard)
        assertNull(done.external)
    }
}
