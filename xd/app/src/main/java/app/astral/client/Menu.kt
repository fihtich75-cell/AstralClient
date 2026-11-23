package app.astral.client

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.text.InputFilter
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** In-game dark-glass menu: HUD / Visuals / Settings / Configs. Lives in its own overlay window. */
class Menu(private val ctx: Context, private val wm: WindowManager, private val host: Host) {
    interface Host {
        fun widgetChanged(id: String)
        fun restyleAll()
        fun editMode(on: Boolean)
        fun edgeChanged()
        fun resetPositions()
        fun stopClient()
        fun screen(): Point
    }

    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val M = ViewGroup.LayoutParams.MATCH_PARENT

    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var body: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var tabInd: View? = null
    private val tabIcons = ArrayList<ImageView>()
    private val tabLabels = ArrayList<TextView>()
    private var tabCellW = 0
    private val TABS = listOf("hud", "visuals", "settings", "configs")
    private val ICONS = intArrayOf(
        R.drawable.ic_tab_hud, R.drawable.ic_tab_visuals, R.drawable.ic_tab_settings, R.drawable.ic_tab_configs
    )
    private var tab = 0
    private var selW = Widgets.ids[0]

    private var blurMode = false
    private var baseX = 0
    private var slide = 0f
    private var slideAnim: ValueAnimator? = null

    private var cfgMsg: String? = null
    private var cfgOk = true
    private var creating = false
    private var renaming: String? = null

    val isOpen: Boolean get() = root != null

    private fun d(v: Int) = ctx.ui(v)

    /** Text size in dp scaled by the UI Scale setting; never below 8 so 70 % stays legible. */
    private fun sp(v: Float) = maxOf(8f, v * Cfg.uiScale / 100f)

    private fun TextView.px(v: Float) { setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp(v)) }

    /** Horizontal fling on the panel switches tabs (sliders disallow interception while dragging). */
    private val swipe = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > 900f && abs(vx) > 2f * abs(vy)) {
                val n = (tab + if (vx < 0) 1 else -1).coerceIn(0, TABS.size - 1)
                root?.post { selectTab(n) }
            }
            return false
        }
    })

    // ---------------------------------------------------------------- window

    private fun blurOn(): Boolean {
        if (Build.VERSION.SDK_INT < 31 || Cfg.perf) return false
        return try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }
    }

    /** Moves the panel horizontally by [offset] px (0 = final place). With blur the window itself moves so the blur follows. */
    private fun slideTo(offset: Float) {
        val r = root ?: return
        if (blurMode) {
            val p = lp ?: return
            p.x = baseX + offset.toInt()
            try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
        } else {
            r.translationX = offset
        }
    }

    @Suppress("DEPRECATION")
    fun open(anim: Boolean = true) {
        if (root != null) return
        val s = host.screen()
        val blur = blurOn()
        blurMode = blur
        val shadow = if (blur) 0 else ctx.dp(14) // room for the soft drop shadow (blur windows are cut to the panel)
        val margin = ctx.dp(12)
        val pw = minOf(d(420), (s.x * 0.8f).toInt(), s.x - 2 * margin)
        val ph = s.y - 2 * margin
        baseX = margin - shadow
        slide = (pw + margin + shadow).toFloat()

        val wrap = FrameLayout(ctx)
        wrap.clipChildren = false
        wrap.clipToPadding = false
        wrap.setPadding(shadow, shadow, shadow, shadow)
        wrap.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) close()
            false
        }

        val pn = object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                swipe.onTouchEvent(ev)
                return false
            }
        }
        pn.orientation = LinearLayout.VERTICAL
        pn.background = ctx.glass(20f, blur)
        pn.setPadding(d(14), d(12), d(14), d(12))
        if (!blur) {
            pn.elevation = ctx.dpf(10f)
            pn.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        wrap.addView(pn, FrameLayout.LayoutParams(M, M))
        root = wrap

        pn.addView(header(), LinearLayout.LayoutParams(M, W))
        pn.addView(tabBar(), LinearLayout.LayoutParams(M, W).apply { topMargin = d(10) })

        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(0, 0, 0, d(8))
        body = b
        val sc = ScrollView(ctx)
        sc.isVerticalScrollBarEnabled = false
        sc.overScrollMode = View.OVER_SCROLL_NEVER
        sc.addView(b, ViewGroup.LayoutParams(M, W))
        scroll = sc
        pn.addView(sc, LinearLayout.LayoutParams(M, 0, 1f).apply { topMargin = d(4) })
        renderBody(0)

        val p = WindowManager.LayoutParams(
            pw + 2 * shadow, ph + 2 * shadow, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // needed for elevation shadows
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = baseX
        p.y = margin - shadow
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (blur) {
            // real glassmorphism: blur whatever is behind the panel (when the device allows cross-window blur)
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            p.blurBehindRadius = ctx.dp(24)
        }
        lp = p

        val start = anim && !Cfg.perf
        if (start) {
            wrap.alpha = 0.35f
            if (blur) p.x = baseX - slide.toInt() else wrap.translationX = -slide
        }
        wm.addView(wrap, p)

        if (start) {
            // slides in from the left with a spring overshoot, 300 ms
            val spring = SpringInterpolator(6f, 1.1f)
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 300
            a.interpolator = LinearInterpolator()
            a.addUpdateListener { va ->
                val t = va.animatedFraction
                slideTo(-slide * (1f - spring.getInterpolation(t)))
                wrap.alpha = 0.35f + 0.65f * minOf(1f, t * 2.5f)
            }
            slideAnim = a
            a.start()
        }
    }

    private fun detachState() {
        slideAnim?.cancel()
        slideAnim = null
        root = null
        lp = null
        body = null
        scroll = null
        tabInd = null
        tabCellW = 0
        tabIcons.clear()
        tabLabels.clear()
    }

    fun close() {
        val r = root ?: return
        val p = lp
        val blur = blurMode
        val fromX = if (blur) (p?.x ?: 0) else 0
        val dist = slide
        detachState()
        val remove = Runnable { try { wm.removeView(r) } catch (_: Exception) {} }
        if (Cfg.perf) {
            remove.run()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 220
        a.interpolator = AccelerateInterpolator()
        a.addUpdateListener { va ->
            val f = va.animatedFraction
            if (blur && p != null) {
                p.x = fromX - (dist * f).toInt()
                try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
            } else {
                r.translationX = -dist * f
            }
            r.alpha = 1f - f
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { remove.run() }
        })
        a.start()
    }

    fun closeNow() {
        val r = root ?: return
        detachState()
        try { wm.removeView(r) } catch (_: Exception) {}
    }

    /** Rebuilds the whole menu (language / accent / scale change), keeping tab and scroll position. */
    private fun rebuild() {
        if (root == null) return
        val sy = scroll?.scrollY ?: 0
        closeNow()
        open(false)
        scroll?.post { scroll?.scrollTo(0, sy) }
    }

    private fun windowFocusable(on: Boolean) {
        val p = lp ?: return
        val r = root ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
    }

    private fun endEdit() {
        val v = root?.findFocus()
        if (v != null) {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
        windowFocusable(false)
    }

    // ---------------------------------------------------------------- chrome

    private fun header(): View {
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = tv("ASTRAL", 17f, Theme.accent(), true)
        title.letterSpacing = 0.18f
        head.addView(title, LinearLayout.LayoutParams(0, W, 1f))
        val x = ImageView(ctx)
        x.setImageResource(R.drawable.ic_close)
        x.setColorFilter(Theme.SUB)
        x.setPadding(d(6), d(6), d(6), d(6))
        x.setOnClickListener { close() }
        head.addView(x, LinearLayout.LayoutParams(d(32), d(32)))
        return head
    }

    private fun tabBar(): View {
        val box = FrameLayout(ctx)
        box.background = ctx.pill(0x14FFFFFF, 14f)
        box.setPadding(d(3), d(3), d(3), d(3))

        val ind = View(ctx)
        ind.background = ctx.pill(Theme.accent(), 11f)
        tabInd = ind
        box.addView(ind, FrameLayout.LayoutParams(0, M))

        val lin = LinearLayout(ctx)
        lin.orientation = LinearLayout.HORIZONTAL
        tabIcons.clear()
        tabLabels.clear()
        TABS.forEachIndexed { i, k ->
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(d(2), d(7), d(2), d(7))
            val ic = ImageView(ctx)
            ic.setImageResource(ICONS[i])
            cell.addView(ic, LinearLayout.LayoutParams(d(20), d(20)))
            val t = TextView(ctx)
            t.text = Str.t(k)
            t.gravity = Gravity.CENTER
            t.typeface = Typeface.DEFAULT_BOLD
            t.maxLines = 1
            // long labels ("Настройки") shrink instead of being cut off, at any UI Scale
            t.setAutoSizeTextTypeUniformWithConfiguration(7, maxOf(9, sp(11f).toInt()), 1, TypedValue.COMPLEX_UNIT_DIP)
            cell.addView(t, LinearLayout.LayoutParams(M, W).apply { topMargin = d(3) })
            cell.setOnClickListener { selectTab(i) }
            tabIcons.add(ic)
            tabLabels.add(t)
            lin.addView(cell, LinearLayout.LayoutParams(0, W, 1f))
        }
        box.addView(lin, FrameLayout.LayoutParams(M, W))
        lin.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val cw = (r - l) / TABS.size
            if (cw > 0 && cw != tabCellW) {
                tabCellW = cw
                val p = ind.layoutParams
                p.width = cw
                ind.layoutParams = p
                ind.translationX = (tab * cw).toFloat()
            }
        }
        paintTabs(false)
        return box
    }

    private fun paintTabs(animate: Boolean) {
        val on = Theme.onAccent(Theme.accent())
        tabIcons.forEachIndexed { i, ic -> ic.setColorFilter(if (i == tab) on else Theme.SUB) }
        tabLabels.forEachIndexed { i, t -> t.setTextColor(if (i == tab) on else Theme.SUB) }
        val ind = tabInd ?: return
        val x = (tab * tabCellW).toFloat()
        ind.animate().cancel()
        if (animate && !Cfg.perf) {
            ind.animate().translationX(x).setDuration(280).setInterpolator(SpringInterpolator(7f, 0.9f)).start()
        } else {
            ind.translationX = x
        }
    }

    private fun selectTab(i: Int) {
        if (i == tab) return
        val dir = if (i > tab) 1 else -1
        tab = i
        creating = false
        renaming = null
        paintTabs(true)
        renderBody(dir)
    }

    /** dir: -1 / +1 = slide the new page in from that side, 0 = no animation. */
    private fun renderBody(dir: Int) {
        val b = body ?: return
        b.animate().cancel()
        b.removeAllViews()
        when (tab) {
            0 -> tabHud(b)
            1 -> tabVisuals(b)
            2 -> tabSettings(b)
            else -> tabConfigs(b)
        }
        scroll?.scrollTo(0, 0)
        if (dir != 0 && !Cfg.perf) {
            b.alpha = 0f
            b.translationX = dir * d(26).toFloat()
            b.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else {
            b.alpha = 1f
            b.translationX = 0f
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun tabHud(b: LinearLayout) {
        b.addView(section(Str.t("widgets")))
        for (id in Widgets.ids) b.addView(moduleCard(id), LinearLayout.LayoutParams(M, W).apply { bottomMargin = d(8) })

        b.addView(section(Str.t("position")))
        b.addView(row(Str.t("move_mode"), chip(Str.t("move_on"), true) { host.editMode(true) }))
        b.addView(row(Str.t("snap"), Toggle(ctx, Cfg.snap) { Cfg.snap = it }))
        b.addView(chip(Str.t("reset_pos"), false) {
            host.resetPositions()
            toast(Str.t("reset_done"))
        }, LinearLayout.LayoutParams(M, W).apply { topMargin = d(8) })
        b.addView(chip(Str.t("reset_timer"), false) { Metrics.resetTimer() }, LinearLayout.LayoutParams(M, W).apply { topMargin = d(8) })
    }

    /** Card: title + short description + switch; a tap smoothly expands style / opacity / size. */
    private fun moduleCard(id: String): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.background = ctx.card(14f)
        box.setPadding(d(12), d(10), d(12), d(10))

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val names = LinearLayout(ctx)
        names.orientation = LinearLayout.VERTICAL
        names.addView(tv(Str.t("w_$id"), 14f, Theme.TEXT, true))
        names.addView(tv(Str.t("d_$id"), 11f, Theme.SUB))
        head.addView(names, LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = d(8) })
        head.addView(Toggle(ctx, Cfg.wBool(id, "on", Widgets.defOn(id))) { v ->
            Cfg.wSetBool(id, "on", v)
            host.widgetChanged(id)
        })
        box.addView(head, LinearLayout.LayoutParams(M, W))

        val extra = LinearLayout(ctx)
        extra.orientation = LinearLayout.VERTICAL
        extra.visibility = View.GONE
        extra.addView(section(Str.t("style")))
        extra.addView(styleChips(id))
        extra.addView(section(Str.t("opacity")))
        extra.addView(slider(10, 100, Cfg.wInt(id, "alpha", 100), { "$it%" }) { Cfg.wSet(id, "alpha", it); host.widgetChanged(id) })
        extra.addView(section(Str.t("size")))
        extra.addView(slider(60, 220, Cfg.wInt(id, "scale", 100), { "$it%" }) { Cfg.wSet(id, "scale", it); host.widgetChanged(id) })
        extra.addView(chip(Str.t("more"), false) {
            selW = id
            tab = 1
            paintTabs(true)
            renderBody(1)
        }, LinearLayout.LayoutParams(M, W).apply { topMargin = d(8) })
        box.addView(extra, LinearLayout.LayoutParams(M, W))

        var open = false
        box.setOnClickListener {
            open = !open
            if (open) extra.expandAnimated(!Cfg.perf) else extra.collapseAnimated(!Cfg.perf)
        }
        return box
    }

    private fun styleChips(id: String): View =
        chipRow(listOf(Str.t("st_compact"), Str.t("st_glass"), Str.t("st_min")), Cfg.wInt(id, "style", 0).coerceIn(0, 2)) {
            Cfg.wSet(id, "style", it)
            host.widgetChanged(id)
        }

    private fun tabVisuals(b: LinearLayout) {
        val id = selW
        b.addView(chipRow(Widgets.ids.map { Str.t("w_$it") }, Widgets.ids.indexOf(id)) { i ->
            selW = Widgets.ids[i]
            renderBody(0)
        })

        b.addView(section(Str.t("style")))
        b.addView(styleChips(id))

        b.addView(section(Str.t("font")))
        b.addView(chipRow(Fonts.names, Cfg.wInt(id, "font", 0)) { Cfg.wSet(id, "font", it); host.widgetChanged(id) })

        b.addView(section(Str.t("color")))
        b.addView(swatchRow(Theme.swatches, Cfg.wInt(id, "color", 0)) { Cfg.wSet(id, "color", it); host.widgetChanged(id) })

        b.addView(section(Str.t("opacity")))
        b.addView(slider(10, 100, Cfg.wInt(id, "alpha", 100), { "$it%" }) { Cfg.wSet(id, "alpha", it); host.widgetChanged(id) })

        b.addView(section(Str.t("size")))
        b.addView(slider(60, 220, Cfg.wInt(id, "scale", 100), { "$it%" }) { Cfg.wSet(id, "scale", it); host.widgetChanged(id) })

        if (id == "fps") {
            b.addView(row(Str.t("graph"), Toggle(ctx, Cfg.wBool(id, "graph", true)) { Cfg.wSetBool(id, "graph", it); host.widgetChanged(id) }))
        }

        val s = host.screen()
        val cx = Cfg.wInt(id, "x", Widgets.defX(ctx, id))
        val cy = Cfg.wInt(id, "y", Widgets.defY(ctx, id))
        b.addView(section(Str.t("pos_x")))
        b.addView(slider(0, 100, (cx * 100 / s.x.coerceAtLeast(1)).coerceIn(0, 100), { "$it%" }) {
            Cfg.wSet(id, "x", it * s.x / 100); host.widgetChanged(id)
        })
        b.addView(section(Str.t("pos_y")))
        b.addView(slider(0, 100, (cy * 100 / s.y.coerceAtLeast(1)).coerceIn(0, 100), { "$it%" }) {
            Cfg.wSet(id, "y", it * s.y / 100); host.widgetChanged(id)
        })
    }

    private fun tabSettings(b: LinearLayout) {
        b.addView(section(Str.t("lang")))
        b.addView(chipRow(listOf("Русский", "English"), if (Cfg.lang == "ru") 0 else 1) {
            Cfg.lang = if (it == 0) "ru" else "en"
            host.restyleAll()
            rebuild()
        })

        // accent colour: 8 presets + custom HEX, applied everywhere immediately
        b.addView(section(Str.t("accent")))
        b.addView(swatchRow(Theme.presets, Theme.accent()) { c -> setAccent(c) })
        b.addView(tv(Str.t("accent_hex"), 11f, Theme.SUB).apply { setPadding(0, d(8), 0, d(4)) })
        b.addView(hexRow())

        b.addView(section(Str.t("ui_scale")))
        b.addView(slider(70, 150, Cfg.uiScale, { "$it%" }, { rebuild() }) { /* applied on release */ Cfg.uiScale = it })

        b.addView(row(Str.t("perf"), Toggle(ctx, Cfg.perf) {
            Cfg.perf = it
            host.restyleAll()
            root?.post { rebuild() }
        }))
        b.addView(row(Str.t("edge"), Toggle(ctx, Cfg.edge) { Cfg.edge = it; host.edgeChanged() }))

        b.addView(section(Str.t("ping_srv")))
        b.addView(textField("${Cfg.pingHost}:${Cfg.pingPort}", "host:19132", 80) { commitPing(it) }, LinearLayout.LayoutParams(M, W))

        b.addView(chip(Str.t("stop_client"), false) { host.stopClient() }, LinearLayout.LayoutParams(M, W).apply { topMargin = d(16) })
    }

    private fun setAccent(c: Int) {
        Cfg.accent = c
        host.restyleAll()
        rebuild()
    }

    private fun hexRow(): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val dot = View(ctx)
        dot.background = ctx.pill(Theme.accent(), 50f)
        row.addView(dot, LinearLayout.LayoutParams(d(26), d(26)).apply { rightMargin = d(8) })
        val et = textField(Theme.hex(Theme.accent()), "#RRGGBB", 7) { applyHex(it) }
        row.addView(et, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(chip(Str.t("apply"), true) { applyHex(et.text.toString()) }, LinearLayout.LayoutParams(W, W).apply { leftMargin = d(8) })
        return row
    }

    private fun applyHex(raw: String) {
        val c = Theme.parseHex(raw)
        if (c == null) {
            toast(Str.t("hex_bad"))
            return
        }
        if (Theme.tooDark(c)) {
            toast(Str.t("hex_dark"))
            return
        }
        endEdit()
        setAccent(c)
    }

    private fun tabConfigs(b: LinearLayout) {
        cfgMsg?.let { m ->
            b.addView(tv(m, 12f, if (cfgOk) Theme.GREEN else Theme.RED, true).apply { setPadding(0, d(4), 0, d(6)) })
        }

        b.addView(section(Str.t("profiles")))
        for (n in Profiles.names()) b.addView(profileRow(n), LinearLayout.LayoutParams(M, W).apply { bottomMargin = d(6) })
        if (creating) {
            b.addView(inlineNameField("", Str.t("p_create")) { raw ->
                val err = Profiles.create(raw)
                if (err != null) {
                    setMsg(Str.t(err), false)
                    renderBody(0)
                } else {
                    creating = false
                    setMsg("${Str.t("p_created")}: ${Profiles.activeLabel()}", true)
                    afterProfileChange()
                }
            })
        } else {
            b.addView(chip("+  " + Str.t("p_new"), false) {
                creating = true
                renaming = null
                renderBody(0)
            }, LinearLayout.LayoutParams(M, W).apply { topMargin = d(2) })
        }

        b.addView(section(Str.t("exchange")))
        b.addView(chip(Str.t("export_cfg"), true) {
            val path = ConfigIO.export(ctx)
            if (path != null) setMsg("${Str.t("cfg_saved")}: $path", true) else setMsg(Str.t("cfg_fail"), false)
            renderBody(0)
        }, LinearLayout.LayoutParams(M, W))

        b.addView(section(Str.t("import_cfg")))
        val files = ConfigIO.list(ctx)
        if (files.isEmpty()) {
            b.addView(tv(Str.t("import_none"), 11.5f, Theme.SUB))
        } else {
            for (f in files) {
                val r = tv(f.name, 13f, Theme.TEXT).apply {
                    background = ctx.card(12f)
                    setPadding(d(12), d(9), d(12), d(9))
                    setOnClickListener { importFile(f) }
                    pressable()
                }
                b.addView(r, LinearLayout.LayoutParams(M, W).apply { bottomMargin = d(6) })
            }
        }

        // factory reset (two taps)
        val f = chip(Str.t("factory"), false) {}
        var armed = false
        f.setOnClickListener {
            if (!armed) {
                armed = true
                f.text = Str.t("factory_confirm")
                f.setTextColor(Theme.RED)
                f.postDelayed({
                    armed = false
                    f.text = Str.t("factory")
                    f.setTextColor(Theme.TEXT)
                }, 3000)
            } else {
                Cfg.resetSettings()
                setMsg(Str.t("factory_done"), true)
                afterProfileChange()
            }
        }
        b.addView(f, LinearLayout.LayoutParams(M, W).apply { topMargin = d(18) })
    }

    private fun importFile(f: ConfigIO.Entry) {
        when (val r = ConfigIO.import(ctx, f)) {
            is ConfigIO.Res.Ok -> {
                setMsg("${Str.t("cfg_imported")}: ${r.name}", true)
                afterProfileChange()
            }
            is ConfigIO.Res.Err -> {
                setMsg(r.message, false)
                renderBody(0)
            }
        }
    }

    private fun profileRow(name: String): View {
        if (renaming == name) {
            return inlineNameField(name, Str.t("p_ok")) { raw ->
                val err = Profiles.rename(name, raw)
                if (err != null) {
                    setMsg(Str.t(err), false)
                } else {
                    renaming = null
                    setMsg(Str.t("p_renamed"), true)
                }
                renderBody(0)
            }
        }
        val active = name == Profiles.active
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ctx.card(12f)
        row.setPadding(d(10), d(6), d(6), d(6))

        val mark = ImageView(ctx)
        mark.setImageResource(R.drawable.ic_check)
        mark.setColorFilter(Theme.accent())
        mark.visibility = if (active) View.VISIBLE else View.INVISIBLE
        row.addView(mark, LinearLayout.LayoutParams(d(18), d(18)).apply { rightMargin = d(8) })
        row.addView(tv(Profiles.label(name), 13.5f, if (active) Theme.TEXT else Theme.SUB, active), LinearLayout.LayoutParams(0, W, 1f))

        if (name != Profiles.DEFAULT) {
            val edit = iconBtn(R.drawable.ic_edit) {
                renaming = name
                creating = false
                renderBody(0)
            }
            row.addView(edit, LinearLayout.LayoutParams(d(34), d(34)))
            val del = iconBtn(R.drawable.ic_delete) {}
            var armed = false
            del.setOnClickListener {
                if (!armed) {
                    armed = true
                    del.setColorFilter(Theme.RED)
                    toast(Str.t("del_confirm"))
                    del.postDelayed({ armed = false; del.setColorFilter(Theme.SUB) }, 3000)
                } else {
                    Profiles.delete(name)
                    setMsg(Str.t("p_deleted"), true)
                    afterProfileChange()
                }
            }
            row.addView(del, LinearLayout.LayoutParams(d(34), d(34)))
        }
        row.setOnClickListener {
            if (!active) {
                Profiles.switchTo(name)
                setMsg("${Str.t("p_switched")}: ${Profiles.label(name)}", true)
                afterProfileChange()
            }
        }
        return row
    }

    private fun inlineNameField(initial: String, okLabel: String, commit: (String) -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val et = textField(initial, Str.t("p_name_hint"), Profiles.NAME_MAX) { commit(it) }
        row.addView(et, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(chip(okLabel, true) {
            val s = et.text.toString()
            endEdit()
            commit(s)
        }, LinearLayout.LayoutParams(W, W).apply { leftMargin = d(8) })
        return row
    }

    private fun setMsg(s: String, ok: Boolean) {
        cfgMsg = s
        cfgOk = ok
        toast(s)
    }

    private fun afterProfileChange() {
        Metrics.resetPing()
        host.restyleAll()
        host.edgeChanged()
        rebuild()
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- text input

    private fun textField(initial: String, hint: String, maxLen: Int, done: (String) -> Unit): EditText {
        val et = EditText(ctx)
        et.setText(initial)
        et.setTextColor(Theme.TEXT)
        et.setHintTextColor(Theme.SUB)
        et.hint = hint
        et.px(13f)
        et.setSingleLine()
        et.background = ctx.pill(Theme.CHIP, 10f)
        et.setPadding(d(12), d(9), d(12), d(9))
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // the menu window is not focusable (so the game keeps working); make it focusable only while typing
        et.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                windowFocusable(true)
                v.post {
                    v.requestFocus()
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
                }
            }
            false
        }
        et.setOnEditorActionListener { v, _, _ ->
            val s = v.text.toString()
            endEdit()
            done(s)
            true
        }
        return et
    }

    private fun commitPing(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        val i = s.lastIndexOf(':')
        val host = if (i > 0) s.substring(0, i).trim() else s
        val port = if (i > 0) s.substring(i + 1).trim().toIntOrNull() ?: 19132 else 19132
        if (host.isEmpty() || port !in 1..65535) return
        Cfg.pingHost = host
        Cfg.pingPort = port
        Metrics.resetPing()
    }

    // ---------------------------------------------------------------- building blocks

    private fun tv(s: String, size: Float = 13f, color: Int = Theme.TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.px(size)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun section(s: String): TextView {
        val t = tv(s.uppercase(), 10.5f, Theme.accent(), true)
        t.letterSpacing = 0.08f
        t.setPadding(0, d(14), 0, d(4))
        return t
    }

    /** Label (wraps when long) on the left, control on the right. */
    private fun row(label: String, right: View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, d(6), 0, d(6))
        r.addView(tv(label, 13.5f), LinearLayout.LayoutParams(0, W, 1f).apply { rightMargin = d(8) })
        r.addView(right, LinearLayout.LayoutParams(W, W))
        return r
    }

    private fun chip(text: String, filled: Boolean, click: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = text
        t.px(12.5f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setTextColor(if (filled) Theme.onAccent(Theme.accent()) else Theme.TEXT)
        t.background = ctx.pill(if (filled) Theme.accent() else Theme.CHIP, 12f)
        t.setPadding(d(14), d(9), d(14), d(9))
        t.setOnClickListener { click() }
        t.pressable()
        return t
    }

    private fun iconBtn(res: Int, click: () -> Unit): ImageView {
        val i = ImageView(ctx)
        i.setImageResource(res)
        i.setColorFilter(Theme.SUB)
        i.setPadding(d(7), d(7), d(7), d(7))
        i.setOnClickListener { click() }
        return i
    }

    private fun chipRow(labels: List<String>, cur: Int, pick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val lin = LinearLayout(ctx)
        lin.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<TextView>()
        val on = Theme.onAccent(Theme.accent())
        fun paint(sel: Int) {
            chips.forEachIndexed { i, t ->
                val s = i == sel
                t.setTextColor(if (s) on else Theme.SUB)
                t.background = ctx.pill(if (s) Theme.accent() else Theme.CHIP, 12f)
            }
        }
        labels.forEachIndexed { i, s ->
            val t = TextView(ctx)
            t.text = s
            t.px(12f)
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setPadding(d(12), d(7), d(12), d(7))
            t.setOnClickListener { paint(i); pick(i) }
            chips.add(t)
            lin.addView(t, LinearLayout.LayoutParams(W, W).apply { rightMargin = d(6) })
        }
        paint(cur)
        hs.addView(lin)
        return hs
    }

    private fun swatchRow(colors: IntArray, cur: Int, pick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val lin = LinearLayout(ctx)
        lin.orientation = LinearLayout.HORIZONTAL
        val sw = ArrayList<Swatch>()
        for (c in colors) {
            val s = Swatch(ctx, c)
            s.sel = c == cur
            s.setOnClickListener { sw.forEach { it.sel = it.color == c }; pick(c) }
            sw.add(s)
            lin.addView(s, LinearLayout.LayoutParams(W, W).apply { rightMargin = d(6) })
        }
        hs.addView(lin)
        return hs
    }

    private fun slider(lo: Int, hi: Int, value: Int, fmt: (Int) -> String, done: (() -> Unit)? = null, change: (Int) -> Unit): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val label = tv(fmt(value.coerceIn(lo, hi)), 12f, Theme.SUB)
        label.gravity = Gravity.END
        label.minWidth = d(44)
        val sb = SeekBar(ctx)
        sb.max = hi - lo
        sb.progress = value.coerceIn(lo, hi) - lo
        sb.progressTintList = ColorStateList.valueOf(Theme.accent())
        sb.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        // fixed (unscaled) side padding: the thumb is dp-sized and would be clipped by a smaller, scaled padding at 70 %
        sb.setPadding(ctx.dp(12), d(6), ctx.dp(12), d(6))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = lo + p
                label.text = fmt(v)
                if (fromUser) change(v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { done?.invoke() }
        })
        box.addView(sb, LinearLayout.LayoutParams(0, W, 1f))
        box.addView(label, LinearLayout.LayoutParams(W, W))
        return box
    }
}
