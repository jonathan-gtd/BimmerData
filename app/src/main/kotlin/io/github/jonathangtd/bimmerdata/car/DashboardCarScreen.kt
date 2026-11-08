/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.Dashboard
import io.github.jonathangtd.bimmerdata.dashboard.DashboardStore
import io.github.jonathangtd.bimmerdata.dashboard.dashboardDependencies
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.MeasurementClient
import io.github.jonathangtd.bimmerdata.profiles.ProfileStore
import io.github.jonathangtd.bimmerdata.profiles.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The Android Auto dashboard: the car pages of the user's layout, drawn on the host's surface
 * (navigation template, so the whole screen is ours) by [CarSurfaceRenderer], with the page and
 * connection buttons in the host's action strip.
 *
 * If the host never hands over a surface (a host that only allows plain templates), the screen
 * falls back after [SURFACE_TIMEOUT_MS] to a grid template whose items are the same cards
 * rendered as bitmaps, refreshed once per second.
 *
 * Measurements: while this screen is shown, the visible car page is requested as the
 * [MeasurementClient.Car] client (see [io.github.jonathangtd.bimmerdata.live.MeasurementRequests]).
 */
class DashboardCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val deps = dashboardDependencies(carContext)
    private val locale: Locale = carContext.resources.configuration.locales[0] ?: Locale.getDefault()
    private val renderer = CarSurfaceRenderer(carContext, locale)

    private val dashboard = MutableStateFlow<Dashboard?>(null)
    private val pageIndex = MutableStateFlow(0)
    private val index = MutableStateFlow<Map<String, Measurement>>(emptyMap())
    private val loadedSgbds = HashSet<String>()

    // ---- main thread ----
    private var connection: LiveConnection = LiveConnection.Disconnected
    private var pageCount = 0
    private var surfaceSeen = false
    private var fallback = false
    private var images: List<CardImage> = emptyList()
    private var fallbackJob: Job? = null
    private var activeProfileId: String? = null
    private var profileCount = 0

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        } catch (e: RuntimeException) {
            // e.g. SecurityException without the surface permission: grid fallback.
            Log.w(TAG, "No surface access, using the grid template", e)
            fallback = true
        }
        renderer.setNight(carContext.isDarkMode)
        renderer.onSurfaceBroken = {
            lifecycleScope.launch {
                if (!fallback) {
                    Log.w(TAG, "The host's surface cannot be drawn on, using the grid template")
                    fallback = true
                    startFallbackRefresh()
                }
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = onCreated()
            override fun onStart(owner: LifecycleOwner) = onStarted()
            override fun onStop(owner: LifecycleOwner) = deps.clearMeasurements(MeasurementClient.Car)
            override fun onDestroy(owner: LifecycleOwner) {
                fallbackJob?.cancel()
                renderer.release()
            }
        })
    }

    private fun onCreated() {
        // Layout: saved file first, then every save made by the phone editor.
        lifecycleScope.launch {
            dashboard.value = DashboardStore.saved.value ?: withContext(Dispatchers.IO) { DashboardStore(carContext).load() }
            DashboardStore.saved.filterNotNull().collect { dashboard.value = it }
        }
        // Another profile: back to its first page; the profile button shows from two profiles on.
        lifecycleScope.launch {
            ProfileStore.state.filterNotNull().collect { set ->
                if (set.activeId != activeProfileId) {
                    activeProfileId = set.activeId
                    pageIndex.value = 0
                }
                val count = Profiles.sameCar(set).size
                if (count != profileCount) {
                    profileCount = count
                    invalidate()
                }
            }
        }
        lifecycleScope.launch {
            dashboard.filterNotNull().collect { d ->
                d.carPages.flatMap { it.measurementIds() }.map { it.substringBefore('/').lowercase() }.distinct().forEach { loadSgbd(it) }
            }
        }
        lifecycleScope.launch {
            combine(dashboard, pageIndex, index) { d, i, idx ->
                val pages = d?.carPages.orEmpty()
                val page = pages.getOrNull(i.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                CarContent(
                    page = page, pageIndex = pages.indexOf(page).coerceAtLeast(0), pageCount = pages.size, index = idx,
                    message = if (d == null) "" else carContext.getString(R.string.car_no_pages),
                )
            }.collect { c ->
                renderer.setContent(c)
                if (c.pageCount != pageCount) {
                    pageCount = c.pageCount
                    invalidate()
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requestMeasurements(c)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { deps.liveDataSource.samples.collect { renderer.onSamples(it) } }
                launch {
                    val label = carContext.getString(io.github.jonathangtd.bimmerdata.R.string.card_unavailable)
                    deps.liveDataSource.unavailable.collect { renderer.onUnavailable(it.keys, label) }
                }
                launch {
                    combine(deps.liveDataSource.status, deps.recorder.state, deps.connection) { s, r, c -> Triple(s, r, c) }
                        .collect { (s, r, c) -> renderer.setStatus(s, r, c) }
                }
                launch {
                    deps.connection.collect {
                        val wasConnected = isConnected(connection)
                        connection = it
                        if (isConnected(it) != wasConnected) invalidate()
                    }
                }
            }
        }
    }

    private fun onStarted() {
        dashboard.value?.let { d ->
            val pages = d.carPages
            val page = pages.getOrNull(pageIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
            requestMeasurements(CarContent(page, 0, pages.size, index.value, ""))
        }
        if (fallback) startFallbackRefresh()
        else if (!surfaceSeen) {
            fallbackJob?.cancel()
            fallbackJob = lifecycleScope.launch {
                delay(SURFACE_TIMEOUT_MS)
                if (!surfaceSeen) {
                    Log.w(TAG, "The host gave no surface within $SURFACE_TIMEOUT_MS ms, using the grid template")
                    fallback = true
                    startFallbackRefresh()
                }
            }
        }
    }

    private fun requestMeasurements(c: CarContent) {
        val list = c.page?.measurementIds().orEmpty().mapNotNull { index.value[it] }
        deps.requestMeasurements(MeasurementClient.Car, list, foreground = true)
    }

    private suspend fun loadSgbd(sgbd: String) {
        if (sgbd.isEmpty() || !loadedSgbds.add(sgbd)) return
        val list = withContext(Dispatchers.IO) { runCatching { deps.catalog.measurements(sgbd) }.getOrDefault(emptyList()) }
        index.update { it + list.associateBy { m -> m.id } }
    }

    /** Head unit day / night mode, forwarded by the session. */
    fun onDarkModeChanged(dark: Boolean) = renderer.setNight(dark)

    private fun changePage(delta: Int) {
        val n = pageCount
        if (n <= 1) return
        pageIndex.update { ((it + delta) % n + n) % n }
        if (fallback) refreshImages()
    }

    // ---------------------------------------------------------------- templates

    override fun onGetTemplate(): Template = if (fallback) gridTemplate() else NavigationTemplate.Builder().setActionStrip(actionStrip()).build()

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    /**
     * Previous, next, profiles (from two profiles on), connect: at most 4, the navigation template's
     * limit. [compact] (plain templates allow only 2 actions): next and connect.
     */
    private fun actionStrip(compact: Boolean = false): ActionStrip {
        val strip = ActionStrip.Builder()
        if (pageCount > 1) {
            if (!compact) strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_chevron_left)).setOnClickListener { changePage(-1) }.build())
            strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_chevron_right)).setOnClickListener { changePage(1) }.build())
        }
        if (!compact && profileCount > 1) {
            strip.addAction(
                Action.Builder().setIcon(icon(R.drawable.ic_profile)).setOnClickListener { screenManager.push(ProfilesCarScreen(carContext)) }.build(),
            )
        }
        val connect = Action.Builder().setIcon(icon(R.drawable.ic_plug))
            .setOnClickListener { screenManager.push(ConnectCarScreen(carContext, deps)) }
        // Spelled out only while it is the thing to do.
        if (!isConnected(connection)) connect.setTitle(carContext.getString(R.string.car_connect))
        strip.addAction(connect.build())
        return strip.build()
    }

    // Title and action strip setters still work on every host (the Header replacement needs car API 7).
    @Suppress("DEPRECATION")
    private fun gridTemplate(): Template {
        val c = dashboard.value
        val pages = c?.carPages.orEmpty()
        val page = pages.getOrNull(pageIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        val builder = GridTemplate.Builder().setTitle(page?.name ?: carContext.getString(R.string.app_name)).setActionStrip(actionStrip(compact = true))
        if (images.isEmpty()) {
            if (page == null && c != null) {
                builder.setSingleList(ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_no_pages)).build())
            } else if (page != null && page.cards.isEmpty()) {
                builder.setSingleList(ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_empty_page)).build())
            } else {
                builder.setLoading(true)
            }
            return builder.build()
        }
        val list = ItemList.Builder()
        for (image in images) {
            list.addItem(
                GridItem.Builder()
                    .setTitle(image.value)
                    .setText(image.title)
                    .setImage(CarIcon.Builder(IconCompat.createWithBitmap(image.bitmap)).build(), GridItem.IMAGE_TYPE_LARGE)
                    .build(),
            )
        }
        return builder.setSingleList(list.build()).build()
    }

    private fun startFallbackRefresh() {
        fallbackJob?.cancel()
        fallbackJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshImages()
                    delay(FALLBACK_REFRESH_MS)
                }
            }
        }
    }

    private fun refreshImages() {
        renderer.renderCards(FALLBACK_IMAGE_PX) { list ->
            lifecycleScope.launch {
                images = list
                invalidate()
            }
        }
    }

    // ---------------------------------------------------------------- SurfaceCallback (main thread)

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface ${surfaceContainer.width}x${surfaceContainer.height} dpi ${surfaceContainer.dpi}")
        surfaceSeen = true
        val surface = surfaceContainer.surface ?: return
        renderer.setSurface(surface, surfaceContainer.width, surfaceContainer.height)
        if (fallback) {
            // Late surface: back to the full-screen dashboard.
            fallback = false
            fallbackJob?.cancel()
            images = emptyList()
            invalidate()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "Visible area $visibleArea")
        renderer.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "Stable area $stableArea")
        renderer.setStableArea(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer.setSurface(null, 0, 0)
    }

    companion object {
        private const val TAG = "DashboardCarScreen"
        private const val SURFACE_TIMEOUT_MS = 4_000L
        private const val FALLBACK_REFRESH_MS = 1_000L
        private const val FALLBACK_IMAGE_PX = 192

        fun isConnected(c: LiveConnection) =
            c is LiveConnection.Connected || c is LiveConnection.Connecting
    }
}
