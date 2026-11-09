/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import io.github.jonathangtd.bimmerdata.R
import io.github.jonathangtd.bimmerdata.dashboard.longestTrendWindowMs
import io.github.jonathangtd.bimmerdata.dashboard.CardSpecs
import io.github.jonathangtd.bimmerdata.dashboard.car.CarChrome
import io.github.jonathangtd.bimmerdata.dashboard.car.StatusLine
import io.github.jonathangtd.bimmerdata.live.AcquisitionState
import io.github.jonathangtd.bimmerdata.live.LiveConnection
import io.github.jonathangtd.bimmerdata.live.LiveMode
import io.github.jonathangtd.bimmerdata.dashboard.LiveFeed
import io.github.jonathangtd.bimmerdata.dashboard.Page
import io.github.jonathangtd.bimmerdata.dashboard.car.CarPageRenderer
import io.github.jonathangtd.bimmerdata.dashboard.car.CarScreen
import io.github.jonathangtd.bimmerdata.gauges.GaugeFonts
import io.github.jonathangtd.bimmerdata.gauges.GaugeSpec
import io.github.jonathangtd.bimmerdata.gauges.GaugeState
import io.github.jonathangtd.bimmerdata.gauges.GaugeType
import io.github.jonathangtd.bimmerdata.live.AcquisitionStatus
import io.github.jonathangtd.bimmerdata.live.Measurement
import io.github.jonathangtd.bimmerdata.live.RecordingState
import io.github.jonathangtd.bimmerdata.live.Sample
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What the car screen shows: one car page of the user's layout, or a message when there is none. */
data class CarContent(
    val page: Page?,
    val pageIndex: Int,
    val pageCount: Int,
    /** Catalog metadata (label, unit, range) of the measurements on the page. */
    val index: Map<String, Measurement>,
    /** Shown instead of the cards when [page] is null. */
    val message: String,
)

/** One card rendered for template-only hosts: the picture plus its value as text. */
class CardImage(val bitmap: Bitmap, val title: String, val value: String)

/**
 * Draws the Android Auto dashboard on the host's surface with the same [CarPageRenderer] as the
 * phone preview, on a dedicated thread so the host (and the main thread) never wait for a frame.
 *
 * Frame pacing: a frame is drawn when the surface, the visible area, the page, the samples or the
 * status change; then again every [FRAME_MS] while needles are still moving (value smoothing), and
 * once per second for what depends on the clock only (stale values, recording timer). Several
 * changes within one frame interval are coalesced into one frame (at most ~30 fps).
 *
 * All public methods may be called from any thread.
 */
class CarSurfaceRenderer(private val context: Context, private val locale: Locale) {
    private val thread = HandlerThread("BimmerData-car", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    /** Held while drawing, so [setSurface] (null) never returns while the old surface is in use. */
    private val surfaceLock = Any()

    // ---- render thread only ----
    private var night = false
    private var renderer = CarPageRenderer(context, locale, night)
    private val feed = LiveFeed()
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val visibleArea = Rect()
    private val stableArea = Rect()
    private var content: CarContent? = null
    private var specs: List<GaugeSpec> = emptyList()
    private val states = HashMap<String, GaugeState>()
    private val imageStates = HashMap<String, GaugeState>()
    private var status = AcquisitionStatus()
    private var recording = RecordingState()
    private var connection: LiveConnection = LiveConnection.Disconnected
    private var frameScheduled = false
    private var lastFrameMs = 0L
    private var lockFailures = 0
    private val regions = ArrayList<Rect>()
    private var lastRegionAt = 0L
    private var loggedInvalid = false
    private var logNextFrame = true
    private var frameTimeMs = 0f
    private var frames = 0
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GaugeFonts.get(context).medium
        textAlign = Paint.Align.CENTER
    }
    private val area = RectF()
    private val header = RectF()
    private val grid = RectF()
    private var compactCache: Pair<Page, Page>? = null
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphRect = RectF()

    private val frame = Runnable { drawFrame() }
    private val tick = object : Runnable {
        override fun run() {
            requestFrame()
            handler.postDelayed(this, 1000)
        }
    }

    fun setSurface(newSurface: Surface?, width: Int, height: Int) {
        if (newSurface == null) {
            // Stop drawing before the host releases the surface.
            val done = CountDownLatch(1)
            handler.postAtFrontOfQueue {
                synchronized(surfaceLock) { surface = null }
                handler.removeCallbacks(tick)
                done.countDown()
            }
            if (!done.await(500, TimeUnit.MILLISECONDS)) synchronized(surfaceLock) { /* wait for the frame in progress */ }
            return
        }
        handler.post {
            synchronized(surfaceLock) {
                surface = newSurface
                lockFailures = 0
                surfaceWidth = width
                surfaceHeight = height
            }
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 1000)
            requestFrame()
        }
    }

    /** Called on the render thread when the host's surface keeps refusing to be drawn on. */
    @Volatile var onSurfaceBroken: (() -> Unit)? = null

    fun setVisibleArea(rect: Rect) = handler.post { visibleArea.set(rect); logNextFrame = true; requestFrame() }
    fun setStableArea(rect: Rect) = handler.post {
        stableArea.set(rect)
        addRegion(rect)
        requestFrame()
    }

    /**
     * Android Auto sometimes reports two areas at once when the app goes from a small card to the
     * main area (or back), in either order: e.g. the main area, then at the same millisecond the
     * card it just left. Following the last one would leave the screen black. The host only shows
     * the part of the surface where the app is, so every area of such a burst is drawn (the last
     * on top): the one on screen is always right, the other one is out of view. The next single
     * change goes back to one area.
     */
    private fun addRegion(rect: Rect) {
        val now = SystemClock.uptimeMillis()
        val burst = now - lastRegionAt <= BURST_MS && regions.isNotEmpty() && regions.last() != rect
        val previous = regions.lastOrNull()
        regions.clear()
        if (burst && previous != null) {
            regions += previous
            Log.i(TAG, "Two areas at once ($previous, $rect): drawing both")
        }
        regions += Rect(rect)
        lastRegionAt = now
    }

    fun setNight(on: Boolean) = handler.post {
        if (on == night) return@post
        night = on
        renderer = CarPageRenderer(context, locale, on)
        rebuildSpecs()
        requestFrame()
    }

    fun setContent(c: CarContent) = handler.post {
        content = c
        rebuildSpecs()
        requestFrame()
    }

    fun onSamples(samples: Map<String, Sample>) = handler.post {
        feed.update(samples)
        requestFrame()
    }

    fun onUnavailable(ids: Set<String>, label: String) = handler.post {
        feed.unavailable = ids
        feed.unavailableLabel = label
        requestFrame()
    }

    fun setStatus(s: AcquisitionStatus, r: RecordingState, c: LiveConnection) = handler.post {
        status = s
        recording = r
        connection = c
        requestFrame()
    }

    /** Short status for the car ("Simulation · 26,4 reads/s"), the engine's own label is too technical. */
    private fun carStatus(chrome: CarChrome): CarChrome {
        val c = connection
        val mode = when (c) {
            is LiveConnection.Connected -> c.mode
            is LiveConnection.Failed -> return chrome.copy(statusText = context.getString(R.string.conn_failed, c.message), statusColor = StatusLine.DANGER)
            else -> return chrome
        }
        if (status.state != AcquisitionState.Running) return chrome
        val label = context.getString(
            when (mode) {
                LiveMode.Usb -> R.string.conn_usb_short
                LiveMode.Bluetooth -> R.string.bt_short
                LiveMode.Simulation -> R.string.conn_sim
            },
        )
        return chrome.copy(statusText = context.getString(R.string.status_running, label, renderer.gauges.format.format(status.cyclesPerSecond, 1)))
    }

    /**
     * Renders every card of the current page as a [sizePx] square bitmap (hosts that do not give
     * the app a surface) and hands them to [callback] on the render thread.
     */
    fun renderCards(sizePx: Int, callback: (List<CardImage>) -> Unit) = handler.post {
        val page = content?.page
        if (page == null) {
            callback(emptyList())
            return@post
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val format = renderer.gauges.format
        val images = page.cards.mapIndexed { i, card ->
            val spec = specs[i]
            val state = imageStates.getOrPut(card.id) { GaugeState() }
            val bitmap = createBitmap(sizePx, sizePx)
            state.sync(spec, feed, now)
            drawGlyph(Canvas(bitmap), rect, spec, state)
            val signed = spec.type == GaugeType.BipolarBar
            val value = spec.channels.indices.joinToString(" · ") { c ->
                val ch = spec.channels[c]
                val text = state.text(c) ?: state.valueText(c, format, ch.decimals, signed)
                if (state.target(c).isNaN()) text else format.withUnit(text, ch.unit)
            }
            CardImage(bitmap, spec.title, value)
        }
        callback(images)
    }

    /**
     * Grid items are shown at about 64 dp: a whole card would be unreadable there, so the picture
     * is only the gauge (a 270° ring, or one centered bar per channel) and the host writes the
     * value as text under it.
     */
    private fun drawGlyph(canvas: Canvas, r: RectF, spec: GaugeSpec, state: GaugeState) {
        val theme = renderer.gauges.theme
        val s = r.width()
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeCap = Paint.Cap.ROUND
        val warn = spec.isWarning(state.target(0))
        if (spec.type == GaugeType.BipolarBar) {
            val n = spec.channels.size.coerceAtLeast(1)
            val maxAbs = maxOf(abs(spec.min), abs(spec.max)).takeIf { it > 0 } ?: 25.0
            glyphPaint.strokeWidth = s * 0.11f
            val left = s * 0.14f
            val right = s * 0.86f
            val mid = s / 2f
            for (i in 0 until n) {
                val y = s * (0.5f + (i - (n - 1) / 2f) * 0.26f)
                glyphPaint.color = GLYPH_TRACK
                canvas.drawLine(left, y, right, y, glyphPaint)
                val v = state.smoothed(i)
                if (!v.isNaN()) {
                    val len = ((abs(v) / maxAbs).coerceAtMost(1.0) * (right - left) / 2).toFloat()
                    glyphPaint.color = if (spec.isWarning(state.target(i))) theme.warning else theme.accent
                    canvas.drawLine(mid, y, if (v >= 0) mid + len else mid - len, y, glyphPaint)
                }
            }
            return
        }
        val stroke = s * 0.11f
        glyphPaint.strokeWidth = stroke
        glyphRect.set(r)
        glyphRect.inset(stroke / 2f + s * 0.08f, stroke / 2f + s * 0.08f)
        glyphRect.offset(0f, s * 0.04f)
        glyphPaint.color = GLYPH_TRACK
        canvas.drawArc(glyphRect, 135f, 270f, false, glyphPaint)
        val v = state.smoothed(0)
        if (!v.isNaN() && spec.max > spec.min) {
            val f = ((v - spec.min) / (spec.max - spec.min)).coerceIn(0.0, 1.0).toFloat()
            if (f > 0.001f) {
                glyphPaint.color = if (warn) theme.warning else theme.accent
                canvas.drawArc(glyphRect, 135f, 270f * f, false, glyphPaint)
            }
        }
    }

    fun release() {
        setSurface(null, 0, 0)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- render thread

    private fun rebuildSpecs() {
        val c = content
        val page = c?.page
        specs = page?.cards?.map { CardSpecs.build(it, c.index, renderer.gauges.format) }.orEmpty()
        feed.retainMs = longestTrendWindowMs(listOfNotNull(page))
        val ids = page?.cards?.mapTo(HashSet()) { it.id }.orEmpty()
        states.keys.retainAll(ids)
        imageStates.keys.retainAll(ids)
    }

    private fun requestFrame() {
        if (frameScheduled || surface == null) return
        frameScheduled = true
        val wait = (lastFrameMs + FRAME_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed(frame, wait)
    }

    private fun drawFrame() {
        frameScheduled = false
        var animating = false
        synchronized(surfaceLock) {
            val s = surface ?: return
            if (!s.isValid) {
                if (!loggedInvalid) Log.w(TAG, "Car surface no longer valid, nothing drawn")
                loggedInvalid = true
                return
            }
            loggedInvalid = false
            lastFrameMs = SystemClock.uptimeMillis()
            // Software canvas on purpose: when the host drops its surface while a frame is in
            // flight, a hardware canvas aborts the whole process in libhwui ("getFrame() called on
            // a context with no surface"), a software one only throws. A frame takes a few ms.
            val canvas = try {
                s.lockCanvas(null)
            } catch (e: Exception) {
                // Seen after the app process was restarted under a running host: the host keeps
                // handing out its dead surface. Retry for a while, then let the screen fall back.
                if (++lockFailures == 1) Log.w(TAG, "Cannot lock the car surface", e)
                if (lockFailures == MAX_LOCK_FAILURES) onSurfaceBroken?.invoke()
                if (lockFailures < MAX_LOCK_FAILURES) {
                    frameScheduled = true
                    handler.postDelayed(frame, 100)
                }
                return
            }
            lockFailures = 0
            if (logNextFrame) {
                logNextFrame = false
                Log.i(TAG, "Frame drawn, surface ${surfaceWidth}x$surfaceHeight, area $visibleArea")
            }
            try {
                val start = SystemClock.uptimeMillis()
                canvas.drawColor(renderer.background)
                animating = drawContent(canvas)
                frameTimeMs = frameTimeMs * 0.95f + (SystemClock.uptimeMillis() - start) * 0.05f
                if (++frames % 300 == 0) Log.d(TAG, "Average frame time ${String.format(Locale.ROOT, "%.1f", frameTimeMs)} ms")
            } finally {
                try {
                    s.unlockCanvasAndPost(canvas)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Cannot post the car frame", e)
                }
            }
        }
        if (animating) requestFrame()
    }

    /** The dashboard in every area of the last burst (usually one), see [addRegion]. */
    private fun drawContent(canvas: Canvas): Boolean {
        if (regions.size < 2) {
            chooseArea(area)
            return drawIn(canvas)
        }
        var animating = false
        for (r in regions) {
            area.set(r)
            animating = drawIn(canvas) || animating
        }
        return animating
    }

    /** The dashboard in [area]. */
    private fun drawIn(canvas: Canvas): Boolean {
        val c = content ?: return false
        val frameNanos = SystemClock.elapsedRealtimeNanos()
        val page = c.page
        val u = surfaceHeight / CarPageRenderer.REFERENCE_HEIGHT
        // Android Auto shows the app full screen, next to a media card, or as a small card next to
        // another app: in a narrow area the cards are reflowed on two columns.
        val compact = area.width() < area.height() * COMPACT_ASPECT
        val reserve: Float
        if (area.top >= MIN_BAND * u) {
            // Full screen: header line in the action strip band, cards in the stable area.
            header.set(area.left, 0f, area.right, area.top)
            grid.set(area)
            reserve = CarPageRenderer.ACTION_STRIP_WIDTH
        } else {
            header.set(area.left, area.top, area.right, area.top + HEADER_HEIGHT * u)
            grid.set(area.left, header.bottom, area.right, area.bottom)
            reserve = if (compact) 0f else CarPageRenderer.ACTION_STRIP_WIDTH
        }
        val shown = if (page != null && compact) compactPage(page) else page
        val st = shown?.cards?.map { states.getOrPut(it.id) { GaugeState() } }.orEmpty()
        val chrome = CarScreen.chrome(
            context, page ?: Page(name = "BimmerData", columns = 1, rows = 1), c.pageIndex, c.pageCount.coerceAtLeast(1),
            status, recording, feed.nowMs, renderer.gauges.format,
        ).let { carStatus(if (c.pageCount > 1) it.copy(pageLabel = "${c.pageIndex + 1} / ${c.pageCount}") else it.copy(pageLabel = "")) }
        val animating = renderer.drawHosted(canvas, header, grid, surfaceHeight.toFloat(), reserve, shown, specs, st, feed, frameNanos, chrome)
        if (page == null) drawMessage(canvas, c.message, u)
        return animating
    }

    /** [page] on two columns, cards in their layout order, an odd last card across both columns. */
    private fun compactPage(page: Page): Page {
        compactCache?.let { (source, result) -> if (source === page) return result }
        val n = page.cards.size
        val cols = if (n <= 1) 1 else 2
        val rows = ((n + cols - 1) / cols).coerceAtLeast(1)
        val cards = page.cards.mapIndexed { i, card ->
            val wide = cols == 2 && i == n - 1 && n % 2 == 1
            card.copy(col = i % cols, row = i / cols, colSpan = if (wide) 2 else 1, rowSpan = 1)
        }
        return page.copy(columns = cols, rows = rows, cards = cards).also { compactCache = page to it }
    }

    private fun drawMessage(canvas: Canvas, message: String, u: Float) {
        messagePaint.textSize = 24f * u
        messagePaint.color = MESSAGE_COLOR
        val text = GaugeState.ellipsize(message, messagePaint, area.width() - 40f * u)
        canvas.drawText(text, area.centerX(), area.centerY() + 8f * u, messagePaint)
    }

    /**
     * Stable area when the host gives one (never covered, even when its controls come back), else
     * the visible area, else the whole surface.
     */
    private fun chooseArea(out: RectF) {
        val r = when {
            !stableArea.isEmpty -> stableArea
            !visibleArea.isEmpty -> visibleArea
            else -> Rect(0, 0, surfaceWidth, surfaceHeight)
        }
        out.set(r)
    }

    companion object {
        private const val TAG = "CarSurfaceRenderer"
        const val FRAME_MS = 33L

        /** Area changes closer than this are one burst (see addRegion). */
        private const val BURST_MS = 150L
        private const val MESSAGE_COLOR = 0xFF8E97A3.toInt()

        /** Gauge track of the grid glyphs: the host draws them on its own grey, lighter than our cards. */
        private const val GLYPH_TRACK = 0xFF525A66.toInt()

        /** Failed surface locks in a row (100 ms apart) before giving up on the surface. */
        private const val MAX_LOCK_FAILURES = 30

        /** Smallest top inset (mockup pixels) treated as the host's action strip band. */
        private const val MIN_BAND = 48f

        /** Header line height (mockup pixels) when the host keeps no band for its action strip. */
        private const val HEADER_HEIGHT = 64f

        /** Areas narrower than this × their height get the two-column layout. */
        private const val COMPACT_ASPECT = 1.4f
    }
}
