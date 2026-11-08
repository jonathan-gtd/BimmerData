/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Layout file: atomic writes, one process-wide lock, read-modify-write, the [DashboardStore.saved] feed. */
class DashboardStoreTest {
    private val warnings = Collections.synchronizedList(ArrayList<String>())

    private fun store(dir: File) = DashboardStore(File(dir, DashboardStore.FILE_NAME), { DefaultDashboard.create("Page 1") }, { msg, _ -> warnings += msg })

    @Test
    fun `first launch gives the empty default, a save is read back and published`(@TempDir dir: File) {
        val s = store(dir)
        assertEquals(DefaultDashboard.create("Page 1").phonePages.map { it.name }, s.load().phonePages.map { it.name })
        val d = Msv70Example.create().copy(name = "330i")
        assertTrue(s.save(d))
        assertEquals(d, store(dir).load())
        assertEquals(d, DashboardStore.saved.value)
        assertEquals(listOf(DashboardStore.FILE_NAME), dir.list()!!.toList(), "no temporary file left")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an unreadable file gives the default layout, an interrupted legacy write its backup`(@TempDir dir: File) {
        File(dir, DashboardStore.FILE_NAME).writeText("{ not json")
        assertTrue(store(dir).load().measurementIds().isEmpty())
        assertEquals(1, warnings.size)
        // androidx AtomicFile (previous versions): base half written, .bak = last good layout
        val good = Msv70Example.create()
        File(dir, DashboardStore.FILE_NAME + ".bak").writeText(DashboardJson.encode(good))
        assertEquals(good, store(dir).load())
        assertTrue(store(dir).save(good.copy(name = "x")))
        assertFalse(File(dir, DashboardStore.FILE_NAME + ".bak").exists(), "backup dropped after a good write")
    }

    @Test
    fun `concurrent read-modify-write from two writers loses no update`(@TempDir dir: File) {
        val api = store(dir)
        val editor = store(dir)
        api.save(DefaultDashboard.create("Page 1").copy(name = "0"))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(400) { i ->
            pool.submit {
                start.await()
                (if (i % 2 == 0) api else editor).update { it.copy(name = (it.name!!.toInt() + 1).toString()) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals("400", store(dir).load().name)
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun `readers never see a half-written file while writers save`(@TempDir dir: File) {
        val big = Msv70Example.create()
        val layouts = (0 until 20).map { big.copy(name = "v$it") }
        store(dir).save(layouts[0])
        val pool = Executors.newFixedThreadPool(6)
        repeat(3) { w -> pool.submit { repeat(100) { store(dir).save(layouts[(it + w) % layouts.size]) } } }
        val seen = Collections.synchronizedSet(HashSet<String?>())
        repeat(3) { pool.submit { repeat(200) { seen += store(dir).load().name } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(warnings.isEmpty(), "a reader saw a broken file: $warnings")
        assertTrue(seen.all { it in layouts.map { l -> l.name } }, "$seen")
        assertTrue(layouts.any { it == store(dir).load() })
    }
}
