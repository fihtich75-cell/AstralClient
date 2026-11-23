package app.astral.client

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Click GUI: a pure-black, iOS-style settings sheet (grouped lists, green switches, segmented control).
 * Opened from Settings, above "Stop client". It only drives the overlay features that exist in the app.
 */
class ClickGui(private val ctx: Context, private val wm: WindowManager, private val host: Menu.Host) {
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val M = ViewGroup.LayoutParams.MATCH_PARENT

    private var root: FrameLayout? = null
    private var panel: View? = null
    private var body: LinearLayout? = null
    private var segLabels = ArrayList<TextView>()
    private var cat = 0

    val isOpen: Boolean get() = root != null

    private object C {
        const val BG = 0xFF000000.toInt()
        const val CARD = 0xFF1C1C1E.toInt()
        const val SEP = 0xFF38383A.toInt()
        const val SUB = 0xFF8E8E93.toInt()
        const val BLUE = 0xFF0A84FF.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val FILL = 0xFF636366.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
    }

    private fun d(v: Int) = ctx.dp(v)

    // ---------------------------------------------------------------- window

    fun open() {
        if (root != null) return
        val s = host.screen()
        val pw = minOf(d(560), (s.x * 0.94f).toInt())
        val ph = (s.y * 0.94f).toInt()

        val wrap = FrameLayout(ctx)
        wrap.setBackgroundColor(0x99000000.toInt())
        wrap.setOnClickListener { close() }

        val pn = LinearLayout(ctx)
        pn.orientation = LinearLayout.VERTICAL
        pn.background = GradientDrawable().apply {
            setColor(C.BG)
            cornerRadius = ctx.dpf(22f)
            setStroke(maxOf(1, d(1)), 0xFF2C2C2E.toInt())
        }
        pn.outlineProvider = ViewOutlineProvider.BACKGROUND
        pn.clipToOutline = true
        pn.isClickable = true // swallow taps so they do not close the sheet
        pn.setPadding(d(18), d(14), d(18), d(10))
        panel = pn

        pn.addView(header(), LinearLayout.LayoutParams(M, W))
        pn.addView(segmented(), LinearLayout.LayoutParams(M, W).apply { topMargin = d(10) })

        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(0, 0, 0, d(16))
        body = b
        val sc = ScrollView(ctx)
        sc.isVerticalScrollBarEnabled = false
        sc.overScrollMode = View.OVER_SCROLL_NEVER
        sc.addView(b, ViewGroup.LayoutParams(M, W))
        pn.addView(sc, LinearLayout.LayoutParams(M, 0, 1f).apply { topMargin = d(4) })

        wrap.addView(pn, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        root = wrap
        render()

        val p = WindowManager.LayoutParams(
            M, M, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (!Cfg.perf) {
            wrap.alpha = 0f
            pn.scaleX = 0.96f
            pn.scaleY = 0.96f
        }
        wm.addView(wrap, p)
        if (!Cfg.perf) {
            wrap.animate().alpha(1f).setDuration(180).start()
            pn.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun detach() {
        root = null
        panel = null
        body = null
        segLabels = ArrayList()
    }

    fun close() {
        val r = root ?: return
        detach()
        if (Cfg.perf) {
            try { wm.removeView(r) } catch (_: Exception) {}
            return
        }
        r.animate().alpha(0f).setDuration(160).withEndAction {
            try { wm.removeView(r) } catch (_: Exception) {}
        }.start()
    }

    fun closeNow() {
        val r = root ?: return
        detach()
        try { wm.removeView(r) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- chrome

    private fun tv(s: String, size: Float, color: Int = C.TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        t.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        return t
    }

    private fun header(): View {
        val h = LinearLayout(ctx)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        val title = tv("Astral", 28f, C.TEXT, true)
        h.addView(title, LinearLayout.LayoutParams(0, W, 1f))
        val done = tv(Str.t("done"), 17f, C.BLUE, true)
        done.setPadding(d(10), d(8), d(2), d(8))
        done.setOnClickListener { close() }
        h.addView(done, LinearLayout.LayoutParams(W, W))
        return h
    }

    private val cats get() = listOf(Str.t("hud"), Str.t("gui_general"))

    private fun segmented(): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.setPadding(d(2), d(2), d(2), d(2))
        box.background = GradientDrawable().apply { setColor(C.CARD); cornerRadius = ctx.dpf(10f) }
        segLabels = ArrayList()
        cats.forEachIndexed { i, name ->
            val t = tv(name, 13f, C.TEXT, true)
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setPadding(d(8), d(7), d(8), d(7))
            t.setOnClickListener {
                if (cat != i) {
                    cat = i
                    paintSeg()
                    render()
                }
            }
            segLabels.add(t)
            box.addView(t, LinearLayout.LayoutParams(0, W, 1f))
        }
        paintSeg()
        return box
    }

    private fun paintSeg() {
        segLabels.forEachIndexed { i, t ->
            t.background = if (i == cat) GradientDrawable().apply { setColor(C.FILL); cornerRadius = ctx.dpf(8f) } else null
        }
    }

    private fun render() {
        val b = body ?: return
        b.removeAllViews()
        if (cat == 0) pageHud(b) else pageGeneral(b)
        b.alpha = 0f
        b.animate().alpha(1f).setDuration(if (Cfg.perf) 0 else 160).start()
    }

    // ---------------------------------------------------------------- pages

    private fun pageHud(b: LinearLayout) {
        b.addView(caption(Str.t("widgets")))
        val group = group()
        Widgets.ids.forEachIndexed { i, id ->
            if (i > 0) group.addView(separator())
            group.addView(widgetRow(id), LinearLayout.LayoutParams(M, W))
        }
        b.addView(group, LinearLayout.LayoutParams(M, W))
    }

    private fun pageGeneral(b: LinearLayout) {
        b.addView(caption(Str.t("gui_general")))
        val g = group()
        g.addView(switchRow(Str.t("perf"), Cfg.perf) {
            Cfg.perf = it
            host.restyleAll()
        })
        g.addView(separator())
        g.addView(switchRow(Str.t("edge"), Cfg.edge) {
            Cfg.edge = it
            host.edgeChanged()
        })
        g.addView(separator())
        g.addView(switchRow(Str.t("snap"), Cfg.snap) { Cfg.snap = it })
        b.addView(g, LinearLayout.LayoutParams(M, W))

        b.addView(caption(Str.t("position")))
        val g2 = group()
        g2.addView(actionRow(Str.t("move_mode"), Str.t("move_on")) {
            host.editMode(true)
            close()
        })
        g2.addView(separator())
        g2.addView(actionRow(Str.t("reset_pos"), Str.t("apply")) {
            host.resetPositions()
        })
        g2.addView(separator())
        g2.addView(actionRow(Str.t("reset_timer"), Str.t("apply")) { Metrics.resetTimer() })
        b.addView(g2, LinearLayout.LayoutParams(M, W))
    }

    private fun caption(s: String): TextView {
        val t = tv(s.uppercase(), 12f, C.SUB)
        t.letterSpacing = 0.04f
        t.setPadding(d(14), d(18), 0, d(6))
        return t
    }

    private fun group(): LinearLayout {
        val g = LinearLayout(ctx)
        g.orientation = LinearLayout.VERTICAL
        g.background = GradientDrawable().apply { setColor(C.CARD); cornerRadius = ctx.dpf(14f) }
        g.outlineProvider = ViewOutlineProvider.BACKGROUND
        g.clipToOutline = true
        return g
    }

    private fun separator(): View {
        val v = View(ctx)
        v.setBackgroundColor(C.SEP)
        val lp = LinearLayout.LayoutParams(M, maxOf(1, d(1) / 2))
        lp.leftMargin = d(16)
        v.layoutParams = lp
        return v
    }

    private fun switchRow(label: String, on: Boolean, change: (Boolean) -> Unit): View {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(d(16), d(11), d(14), d(11))
        r.addView(tv(label, 16f), LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = d(10) })
        r.addView(IosSwitch(ctx, on, change), LinearLayout.LayoutParams(W, W))
        return r
    }

    private fun actionRow(label: String, action: String, click: () -> Unit): View {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(d(16), d(13), d(16), d(13))
        r.addView(tv(label, 16f), LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = d(10) })
        r.addView(tv(action, 16f, C.BLUE, true), LinearLayout.LayoutParams(W, W))
        r.setOnClickListener { click() }
        return r
    }

    /** Widget row: title + description + switch; tapping the row expands style / opacity / size. */
    private fun widgetRow(id: String): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(d(16), d(10), d(14), d(10))
        val names = LinearLayout(ctx)
        names.orientation = LinearLayout.VERTICAL
        names.addView(tv(Str.t("w_$id"), 16f))
        names.addView(tv(Str.t("d_$id"), 12f, C.SUB))
        head.addView(names, LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = d(10) })
        head.addView(IosSwitch(ctx, Cfg.wBool(id, "on", Widgets.defOn(id))) { v ->
            Cfg.wSetBool(id, "on", v)
            host.widgetChanged(id)
        }, LinearLayout.LayoutParams(W, W))
        box.addView(head, LinearLayout.LayoutParams(M, W))

        val extra = LinearLayout(ctx)
        extra.orientation = LinearLayout.VERTICAL
        extra.visibility = View.GONE
        extra.setPadding(d(16), 0, d(16), d(12))
        extra.addView(tv(Str.t("style"), 12f, C.SUB).apply { setPadding(0, d(2), 0, d(6)) })
        extra.addView(styleSeg(id))
        extra.addView(tv(Str.t("opacity"), 12f, C.SUB).apply { setPadding(0, d(10), 0, 0) })
        extra.addView(slider(10, 100, Cfg.wInt(id, "alpha", 100)) { Cfg.wSet(id, "alpha", it); host.widgetChanged(id) })
        extra.addView(tv(Str.t("size"), 12f, C.SUB))
        extra.addView(slider(60, 220, Cfg.wInt(id, "scale", 100)) { Cfg.wSet(id, "scale", it); host.widgetChanged(id) })
        box.addView(extra, LinearLayout.LayoutParams(M, W))

        var open = false
        head.setOnClickListener {
            open = !open
            if (open) extra.expandAnimated(!Cfg.perf) else extra.collapseAnimated(!Cfg.perf)
        }
        return box
    }

    private fun styleSeg(id: String): View {
        val labels = listOf(Str.t("st_compact"), Str.t("st_glass"), Str.t("st_min"))
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.setPadding(d(2), d(2), d(2), d(2))
        box.background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = ctx.dpf(9f) }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) {
            cells.forEachIndexed { i, t ->
                t.background = if (i == sel) GradientDrawable().apply { setColor(C.FILL); cornerRadius = ctx.dpf(7f) } else null
            }
        }
        labels.forEachIndexed { i, s ->
            val t = tv(s, 12.5f, C.TEXT, true)
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setPadding(d(6), d(6), d(6), d(6))
            t.setOnClickListener {
                paint(i)
                Cfg.wSet(id, "style", i)
                host.widgetChanged(id)
            }
            cells.add(t)
            box.addView(t, LinearLayout.LayoutParams(0, W, 1f))
        }
        paint(Cfg.wInt(id, "style", 0).coerceIn(0, 2))
        return box
    }

    private fun slider(lo: Int, hi: Int, value: Int, change: (Int) -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val label = tv("${value.coerceIn(lo, hi)}%", 12f, C.SUB)
        label.gravity = Gravity.END
        label.minWidth = d(44)
        val sb = SeekBar(ctx)
        sb.max = hi - lo
        sb.progress = value.coerceIn(lo, hi) - lo
        sb.progressTintList = ColorStateList.valueOf(C.BLUE)
        sb.progressBackgroundTintList = ColorStateList.valueOf(0xFF48484A.toInt())
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.setPadding(d(12), d(8), d(12), d(8))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                label.text = "${lo + p}%"
                if (fromUser) change(lo + p)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        row.addView(sb, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(label, LinearLayout.LayoutParams(W, W))
        return row
    }
}

/** iOS switch: 51x31 dp, green when on, white thumb with a short slide animation. */
class IosSwitch(ctx: Context, on: Boolean, private val cb: (Boolean) -> Unit) : View(ctx) {
    private var checked = on
    private var prog = if (on) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    init {
        setOnClickListener {
            checked = !checked
            val to = if (checked) 1f else 0f
            anim?.cancel()
            if (Cfg.perf) {
                prog = to
                invalidate()
            } else {
                val a = ValueAnimator.ofFloat(prog, to)
                a.duration = 200
                a.interpolator = DecelerateInterpolator()
                a.addUpdateListener { prog = it.animatedValue as Float; invalidate() }
                anim = a
                a.start()
            }
            cb(checked)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(51), context.dp(31))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = prog.coerceIn(0f, 1f)
        val off = 0xFF39393D.toInt()
        val on = 0xFF30D158.toInt()
        track.color = Color.rgb(
            (Color.red(off) + (Color.red(on) - Color.red(off)) * p).toInt(),
            (Color.green(off) + (Color.green(on) - Color.green(off)) * p).toInt(),
            (Color.blue(off) + (Color.blue(on) - Color.blue(off)) * p).toInt()
        )
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - context.dpf(2f)
        c.drawCircle(h / 2 + (w - h) * p, h / 2, r, thumb)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
