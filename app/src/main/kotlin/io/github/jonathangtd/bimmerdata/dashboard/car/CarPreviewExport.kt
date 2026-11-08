/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.dashboard.car

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.dashboard.LiveFeed
import io.github.jonathangtd.bimmerdata.dashboard.dashboardDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.Locale

/**
 * Debug helper: renders every Android Auto page at 1280×480 into files/car_preview/car_N.png,
 * after collecting [seconds] of live data so sparklines and session min / max are filled.
 * Started with `am start … --ez renderCarPreview true` on debuggable builds only.
 */
object CarPreviewExport {
    suspend fun run(context: Context, seconds: Int) {
        val deps = dashboardDependencies(context)
        val store = DashboardStore(context)
        val dashboard = withContext(Dispatchers.IO) { store.load() }
        val feed = LiveFeed()
        coroutineScope {
            val job = launch { deps.liveDataSource.samples.collect { feed.update(it) } }
            withTimeoutOrNull(seconds * 1000L) { deps.liveDataSource.samples.first { false } }
            job.cancel()
        }
        val index = withContext(Dispatchers.IO) {
            dashboard.measurementIds().map { it.substringBefore('/') }.distinct()
                .flatMap { deps.catalog.measurements(it) }.associateBy { it.id }
        }
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val dir = File(context.filesDir, "car_preview").apply { mkdirs() }
        dashboard.carPages.forEachIndexed { i, page ->
            val bitmap = CarScreen.render(
                context, locale, page, i, dashboard.carPages.size, index, feed,
                deps.liveDataSource.status.value, deps.recorder.state.value,
            )
            withContext(Dispatchers.IO) {
                File(dir, "car_${i + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        Log.i("CarPreviewExport", "Rendered ${dashboard.carPages.size} car pages into $dir")
    }
}
