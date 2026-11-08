/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard

import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Layout of the active profile: atomic writes, one process-wide lock, read-modify-write, the
 * [DashboardStore.saved] feed, the migration of the old layout file.
 */
class DashboardStoreTest {
    private val warnings = Collections.synchronizedList(ArrayList<String>())

    private fun profiles(dir: File) = ProfileStore(dir, { "My profile" }, { msg, _ -> warnings += msg }, info = {})
    private fun store(dir: File) = DashboardStore(profiles(dir))

    @Test
    fun `first launch has no profile and an empty layout, a save creates the profile`(@TempDir dir: File) {
        val s = store(dir)
        assertEquals(Dashboard(), s.load())
        assertTrue(s.save(Dashboard()), "an empty layout without profile changes nothing")
        assertTrue(profiles(dir).load().profiles.isEmpty())
        assertTrue(s.save(Dashboard(name = "Empty")))
        assertEquals(Dashboard(name = "Empty"), store(dir).load(), "a profile named after the layout, no page added")
        val d = Msv70Example.create().copy(name = "330i")
        assertTrue(s.save(d))
        assertEquals(d, store(dir).load(), "the same profile, renamed, with the pages")
        assertEquals(1, profiles(dir).load().profiles.size)
        assertEquals(d, DashboardStore.saved.value)
        assertEquals(listOf(ProfileStore.FILE_NAME), dir.list()!!.toList(), "no temporary file left")
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the old layout file becomes the first profile, an empty one gives none`(@TempDir dir: File) {
        val old = Msv70Example.create().copy(name = "Ma 330i")
        File(dir, DashboardStore.FILE_NAME).writeText(DashboardJson.encode(old))
        assertEquals(old, store(dir).load())
        val set = profiles(dir).load()
        assertEquals(listOf("Ma 330i"), set.profiles.map { it.name })
        assertEquals(set.profiles[0].id, set.activeId)
        assertTrue(File(dir, ProfileStore.FILE_NAME).isFile, "profiles.json written at once")
        assertTrue(File(dir, DashboardStore.FILE_NAME).isFile, "the old file is kept as it was")
        // later edits go to profiles.json; the old file is never read again
        assertTrue(store(dir).save(old.copy(name = "330i")))
        assertEquals("330i", store(dir).load().name)

        val empty = File(dir, "empty").apply { mkdirs() }
        File(empty, DashboardStore.FILE_NAME).writeText(DashboardJson.encode(Dashboard()))
        assertNull(profiles(empty).load().active, "an empty old layout: first launch stays empty")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun `an unreadable file is kept aside, an interrupted legacy write migrates its backup`(@TempDir dir: File) {
        File(dir, ProfileStore.FILE_NAME).writeText("{ not json")
        assertTrue(store(dir).load().measurementIds().isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(dir.list()!!.any { it.startsWith(ProfileStore.FILE_NAME + ".unreadable-") }, "the user's file is not lost")
        // androidx AtomicFile (old versions): base half written, .bak = last good layout
        val legacy = File(dir, "legacy").apply { mkdirs() }
        val good = Msv70Example.create()
        File(legacy, DashboardStore.FILE_NAME).writeText("{ half")
        File(legacy, DashboardStore.FILE_NAME + ".bak").writeText(DashboardJson.encode(good))
        assertEquals(good.copy(name = "My profile"), store(legacy).load())
    }

    @Test
    fun `concurrent read-modify-write from two writers loses no update`(@TempDir dir: File) {
        val api = store(dir)
        val editor = store(dir)
        api.save(Dashboard(phonePages = listOf(DefaultDashboard.phonePage("Page 1")), name = "0"))
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
