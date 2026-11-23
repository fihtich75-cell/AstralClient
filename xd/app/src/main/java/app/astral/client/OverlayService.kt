package app.astral.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * The client runtime: HUD widgets, the menu button, the edge-swipe zone and the glass menu,
 * all drawn as overlay windows on top of Minecraft.
 */
class OverlayService : Service(), Menu.Host {
    companion object {
        const val ACTION_STOP = "app.astral.client.STOP"
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())

    private val views = LinkedHashMap<String, WidgetView>()
    private val lps = HashMap<String, WindowManager.LayoutParams>()
    private var cpsView: View? = null
    private var button: MenuButton? = null
    private var btnLp: WindowManager.LayoutParams? = null
    private var edgeView: View? = null
    private var menu: Menu? = null
    private var guides: GuideView? = null

    private var edit = false
    private var shown = true
    private var ticks = 0
    private var ready = false

    private val NT = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    private val TOUCH = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Cfg.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // started with startForegroundService(): startForeground() must come first, even when we stop right away
        startFg()
        if (!License.cachedValid(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ready) setup()
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        menu?.closeNow()
        menu = null
        for (v in views.values) rm(v)
        views.clear()
        rm(cpsView); rm(button); rm(edgeView); rm(guides)
        cpsView = null; button = null; edgeView = null; guides = null
        Metrics.stop()
        ready = false
        super.onDestroy()
    }

    private fun rm(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("astral", "Astral", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "astral")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Astral Client")
            .setContentText("Minecraft HUD")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close), Str.t("stop_client"), stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ---------------------------------------------------------------- setup

    private fun lp(w: Int, hh: Int, flags: Int) = WindowManager.LayoutParams(
        w, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @Suppress("DEPRECATION")
    override fun screen(): Point {
        val p = Point()
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    private fun setup() {
        ready = true
        Fg.reset()
        Metrics.start()
        Metrics.pollSystem(this)
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT

        // HUD widgets: one overlay window each
        for (id in Widgets.ids) {
            val v = WidgetView(this, id)
            v.applyStyle()
            val p = lp(wrap, wrap, NT)
            views[id] = v
            lps[id] = p
            attachDrag(v, id)
            syncPos(id)
            wm.addView(v, p)
            syncWidget(id)
        }

        // CPS probe: 1x1 window that receives ACTION_OUTSIDE for every tap landing outside our windows
        cpsView = View(this).apply {
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) Metrics.click()
                false
            }
        }
        wm.addView(
            cpsView, lp(1, 1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        )

        setupButton()
        setupEdge()
        h.postDelayed(tick, 100)
    }

    private fun setupButton() {
        val s = screen()
        val b = MenuButton(this)
        val p = lp(dp(40), dp(40), TOUCH).apply {
            // default: just left of the in-game chat button (top centre); draggable, position is remembered
            x = if (Cfg.btnX >= 0) Cfg.btnX else s.x / 2 - dp(110)
            y = if (Cfg.btnY >= 0) Cfg.btnY else dp(6)
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            val sc = screen()
                            p.x = (ox + dx).coerceIn(0, max(0, sc.x - dp(40)))
                            p.y = (oy + dy).coerceIn(0, max(0, sc.y - dp(40)))
                            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            Cfg.btnX = p.x
                            Cfg.btnY = p.y
                        } else {
                            onButton()
                        }
                    }
                }
                return true
            }
        })
        button = b
        btnLp = p
        wm.addView(b, p)
    }

    private fun setupEdge() {
        rm(edgeView)
        edgeView = null
        if (!Cfg.edge) return
        val s = screen()
        val v = View(this)
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var fired = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; fired = false }
                    MotionEvent.ACTION_MOVE -> if (!fired && e.rawX - sx > dp(36)) {
                        fired = true
                        openMenu()
                    }
                }
                return true
            }
        })
        wm.addView(v, lp(dp(14), (s.y * 0.4f).toInt(), TOUCH).apply { x = 0; y = (s.y * 0.2f).toInt() })
        edgeView = v
        v.visibility = if (shown) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- widgets

    private fun syncPos(id: String) {
        val p = lps[id] ?: return
        val v = views[id]
        val s = screen()
        var x = Cfg.wInt(id, "x", Widgets.defX(this, id))
        var y = Cfg.wInt(id, "y", Widgets.defY(this, id))
        // keep the widget fully on screen (also after a profile switch / size change)
        val w = v?.measuredWidth ?: 0
        val hh = v?.measuredHeight ?: 0
        if (w > 0) x = x.coerceIn(0, max(0, s.x - w))
        if (hh > 0) y = y.coerceIn(0, max(0, s.y - hh))
        p.x = max(0, x)
        p.y = max(0, y)
    }

    /** Applies the stored settings of one widget to its window. */
    private fun syncWidget(id: String) {
        val v = views[id] ?: return
        val p = lps[id] ?: return
        syncPos(id)
        p.flags = if (edit) TOUCH else NT
        v.edit = edit
        v.applyStyle()
        v.visibility = if (shown && Cfg.wBool(id, "on", Widgets.defOn(id))) View.VISIBLE else View.GONE
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private class SnapRes(val delta: Int, val line: Int)

    /** Finds the nearest alignment of the moving box (start/centre/end) with the screen edges, centre or other widgets. */
    private fun snapAxis(start: Int, size: Int, screen: Int, margin: Int, targets: List<Int>, thr: Int): SnapRes? {
        var best: SnapRes? = null
        fun consider(anchor: Int, t: Int) {
            val dl = t - anchor
            val b = best
            if (abs(dl) <= thr && (b == null || abs(dl) < abs(b.delta))) best = SnapRes(dl, t)
        }
        consider(start, margin)
        consider(start + size, screen - margin)
        consider(start + size / 2, screen / 2)
        for (t in targets) {
            consider(start, t)
            consider(start + size, t)
            consider(start + size / 2, t)
        }
        return best
    }

    /** Visible bounds (l, t, r, b) of every other shown widget. */
    private fun otherBounds(except: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        for ((oid, ov) in views) {
            if (oid == except || ov.visibility != View.VISIBLE) continue
            val op = lps[oid] ?: continue
            val ins = ov.inset
            out.add(intArrayOf(op.x + ins, op.y + ins, op.x + ov.width - ins, op.y + ov.height - ins))
        }
        return out
    }

    private fun attachDrag(v: WidgetView, id: String) {
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                if (!edit) return false
                val p = lps[id] ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y }
                    MotionEvent.ACTION_MOVE -> {
                        val s = screen()
                        val ins = v.inset
                        val vw = v.width - 2 * ins
                        val vh = v.height - 2 * ins
                        var vx = (ox + (e.rawX - sx).toInt() + ins).coerceIn(0, max(0, s.x - vw))
                        var vy = (oy + (e.rawY - sy).toInt() + ins).coerceIn(0, max(0, s.y - vh))
                        val xs = ArrayList<Int>()
                        val ys = ArrayList<Int>()
                        if (Cfg.snap) {
                            val others = otherBounds(id)
                            val thr = dp(10)
                            val mg = dp(8)
                            val rx = snapAxis(vx, vw, s.x, mg, others.flatMap { listOf(it[0], it[2], (it[0] + it[2]) / 2) }, thr)
                            if (rx != null) { vx += rx.delta; xs.add(rx.line) }
                            val ry = snapAxis(vy, vh, s.y, mg, others.flatMap { listOf(it[1], it[3], (it[1] + it[3]) / 2) }, thr)
                            if (ry != null) { vy += ry.delta; ys.add(ry.line) }
                            vx = vx.coerceIn(0, max(0, s.x - vw))
                            vy = vy.coerceIn(0, max(0, s.y - vh))
                        }
                        p.x = vx - ins
                        p.y = vy - ins
                        guides?.set(xs, ys)
                        try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Cfg.wSet(id, "x", p.x)
                        Cfg.wSet(id, "y", p.y)
                        guides?.set(emptyList(), emptyList())
                    }
                }
                return true
            }
        })
    }

    private fun showGuides() {
        if (guides != null) return
        val s = screen()
        val g = GuideView(this)
        wm.addView(g, lp(s.x, s.y, NT).apply { x = 0; y = 0 })
        guides = g
    }

    private fun hideGuides() {
        rm(guides)
        guides = null
    }

    // ---------------------------------------------------------------- menu

    private fun onButton() {
        if (edit) editMode(false) else toggleMenu()
    }

    private fun toggleMenu() {
        val m = menu ?: Menu(this, wm, this).also { menu = it }
        if (m.isOpen) m.close() else m.open()
    }

    private fun openMenu() {
        val m = menu ?: Menu(this, wm, this).also { menu = it }
        if (!m.isOpen) m.open()
    }

    // Menu.Host

    override fun widgetChanged(id: String) = syncWidget(id)

    override fun restyleAll() {
        for (id in Widgets.ids) syncWidget(id)
        val b = button
        val p = btnLp
        if (b != null && p != null) {
            val s = screen()
            p.x = if (Cfg.btnX >= 0) Cfg.btnX else s.x / 2 - dp(110)
            p.y = if (Cfg.btnY >= 0) Cfg.btnY else dp(6)
            try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
            b.invalidate()
        }
    }

    override fun editMode(on: Boolean) {
        edit = on
        button?.edit = on
        if (on) {
            menu?.close()
            showGuides()
            Toast.makeText(this, Str.t("edit_hint"), Toast.LENGTH_LONG).show()
        } else {
            hideGuides()
        }
        for (id in Widgets.ids) syncWidget(id)
    }

    override fun edgeChanged() = setupEdge()

    override fun resetPositions() {
        for (id in Widgets.ids) {
            Cfg.wClearPos(id)
            syncWidget(id)
        }
    }

    override fun stopClient() {
        stopSelf()
    }

    // ---------------------------------------------------------------- ticker

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (shown) for (v in views.values) if (v.visibility == View.VISIBLE) v.refresh()
            if (ticks % 10 == 0) {
                Metrics.pollSystem(this@OverlayService)
                checkForeground()
            }
            if (ticks % 600 == 0 && !License.cachedValid(this@OverlayService)) {
                stopSelf()
                return
            }
            h.postDelayed(this, 100)
        }
    }

    /** With usage access the overlay only shows while Minecraft is in front; without it, it always shows. */
    private fun checkForeground() {
        val show = Fg.poll(this) == Fg.MC
        if (show == shown) return
        shown = show
        Metrics.active = show
        if (!show) {
            menu?.closeNow()
            editModeSilent()
        }
        for (id in Widgets.ids) syncWidget(id)
        val vis = if (show) View.VISIBLE else View.GONE
        button?.visibility = vis
        cpsView?.visibility = vis
        edgeView?.visibility = vis
    }

    private fun editModeSilent() {
        if (!edit) return
        edit = false
        button?.edit = false
        hideGuides()
    }
}
