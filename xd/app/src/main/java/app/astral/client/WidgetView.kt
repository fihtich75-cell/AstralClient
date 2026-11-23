package app.astral.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * One HUD widget = one tiny overlay window. Three styles: 0 compact, 1 glass, 2 minimal.
 * Own font / colour / opacity / size / position.
 */
class WidgetView(ctx: Context, val id: String) : View(ctx) {
    private val d = resources.displayMetrics.density

    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.STROKE }
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x12000000 }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val pVal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pEdit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val path = Path()
    private val fillPath = Path()
    private val rect = RectF()

    var edit = false
        set(v) { field = v; sig = ""; invalidate() }

    /** Transparent margin (px) around the visible box that holds the soft shadow of the glass style. */
    var inset = 0
        private set

    private var scale = 1f
    private var style = 0
    private var graph = false
    private var label = ""
    private var value = ""
    private var vColor = Theme.TEXT
    private var sig = ""

    private var padH = 0f
    private var padV = 0f
    private var gap = 0f
    private var radius = 0f
    private var gw = 0f
    private var gh = 0f
    private var k = 1f

    /** Re-reads this widget's settings. */
    fun applyStyle() {
        scale = Cfg.wInt(id, "scale", 100).coerceIn(40, 300) / 100f
        style = Cfg.wInt(id, "style", 0).coerceIn(0, 2)
        alpha = Cfg.wInt(id, "alpha", 100).coerceIn(10, 100) / 100f
        graph = id == "fps" && Cfg.wBool(id, "graph", true) && !Cfg.perf && style != 2
        k = d * scale
        when (style) {
            0 -> {
                padH = 7 * k; padV = 4 * k; gap = 5 * k; radius = 8 * k
                pVal.textSize = 13f * k; pLabel.textSize = 9.5f * k; inset = 0
            }
            1 -> {
                padH = 11 * k; padV = 7 * k; gap = 7 * k; radius = 12 * k
                pVal.textSize = 14f * k; pLabel.textSize = 10.5f * k; inset = ceil(7 * k).toInt()
            }
            else -> {
                padH = 2 * k; padV = 1 * k; gap = 0f; radius = 0f
                pVal.textSize = 15f * k; pLabel.textSize = 0f; inset = 0
            }
        }
        gw = (if (style == 1) 96 else 84) * k
        gh = 24 * k
        pVal.typeface = Fonts.get(Cfg.wInt(id, "font", 0))
        val shadow = if (style == 2) 2f * d else 0f
        pVal.setShadowLayer(shadow, 0f, 0f, Color.BLACK)
        pLabel.setShadowLayer(shadow, 0f, 0f, Color.BLACK)
        pLine.strokeWidth = 1.6f * k
        pStroke.strokeWidth = maxOf(1f, d)
        pBg.shader = null
        sig = ""
        requestLayout()
        invalidate()
    }

    private fun content(): Triple<String, String, Int> = when (id) {
        "fps" -> Triple("FPS", Metrics.fps.toString(), Theme.TEXT)
        "ping" -> {
            val p = Metrics.ping
            val c = when {
                p < 0 -> Theme.RED
                p < 60 -> Theme.GREEN
                p < 120 -> Theme.YELLOW
                else -> Theme.RED
            }
            Triple("PING", if (p < 0) "—" else "$p ms", c)
        }
        "cps" -> Triple("CPS", Metrics.cps().toString(), Theme.TEXT)
        "timer" -> Triple(Str.t("l_timer"), Metrics.timerStr(), Theme.TEXT)
        "battery" -> {
            val l = Metrics.battLevel
            val c = when {
                l < 0 -> Theme.TEXT
                Metrics.charging -> Theme.GREEN
                l <= 15 -> Theme.RED
                l <= 30 -> Theme.YELLOW
                else -> Theme.TEXT
            }
            Triple(Str.t("l_bat"), if (l < 0) "—" else if (Metrics.charging) "$l% ⚡" else "$l%", c)
        }
        "clock" -> Triple(Str.t("l_time"), Metrics.clock, Theme.TEXT)
        else -> {
            val t = Metrics.battTemp
            val c = when {
                t.isNaN() -> Theme.TEXT
                t < 38f -> Theme.GREEN
                t < 43f -> Theme.YELLOW
                else -> Theme.RED
            }
            Triple(Str.t("l_temp"), if (t.isNaN()) "—" else "%.1f°C".format(t), c)
        }
    }

    /** Called by the service ticker; redraws only when something visible changed. */
    fun refresh() {
        val (l, v, auto) = content()
        val user = Cfg.wInt(id, "color", 0)
        val col = if (user != 0) user else auto
        val s = "$l|$v|$col|${if (graph) Metrics.histPos else 0}|$edit"
        if (s == sig) return
        sig = s
        label = l
        value = v
        vColor = col
        requestLayout()
        invalidate()
    }

    private fun showLabel() = style != 2

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val fm = pVal.fontMetrics
        val lineH = fm.descent - fm.ascent
        var w = padH * 2 + pVal.measureText(value)
        if (showLabel()) w += pLabel.measureText(label) + gap
        var h = padV * 2 + lineH
        if (graph) {
            w = max(w, padH * 2 + gw)
            h += gh + 4 * k
        }
        setMeasuredDimension(ceil(w).toInt() + 2 * inset + 1, ceil(h).toInt() + 2 * inset + 1)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pBg.shader = null // rebuilt lazily for the new height
    }

    override fun onDraw(c: Canvas) {
        val acc = Theme.accent()
        val bw = (width - 2 * inset).toFloat()
        val bh = (height - 2 * inset).toFloat()
        c.save()
        c.translate(inset.toFloat(), inset.toFloat())

        if (style == 1) {
            // soft shadow: three growing translucent layers, shifted slightly down
            for (i in 3 downTo 1) {
                val e = i * 1.4f * k
                rect.set(-e, -e + 1.5f * k, bw + e, bh + e + 1.5f * k)
                c.drawRoundRect(rect, radius + e, radius + e, pShadow)
            }
            if (pBg.shader == null) {
                pBg.shader = LinearGradient(0f, 0f, 0f, max(1f, bh), Theme.GLASS_TOP, Theme.GLASS_BOT, Shader.TileMode.CLAMP)
            }
            rect.set(0f, 0f, bw, bh)
            c.drawRoundRect(rect, radius, radius, pBg)
            rect.set(pStroke.strokeWidth / 2, pStroke.strokeWidth / 2, bw - pStroke.strokeWidth / 2, bh - pStroke.strokeWidth / 2)
            c.drawRoundRect(rect, radius, radius, pStroke)
        } else if (style == 0) {
            pBg.shader = null
            pBg.color = Theme.WIDGET_BG
            rect.set(0f, 0f, bw, bh)
            c.drawRoundRect(rect, radius, radius, pBg)
        }

        val fm = pVal.fontMetrics
        val base = padV - fm.ascent
        var x = padH
        if (showLabel()) {
            pLabel.color = acc
            c.drawText(label, x, base, pLabel)
            x += pLabel.measureText(label) + gap
        }
        pVal.color = vColor
        c.drawText(value, x, base, pVal)
        if (graph) drawGraph(c, padH, padV + (fm.descent - fm.ascent) + 4 * k, bw, acc)
        if (edit) {
            pEdit.color = acc
            rect.set(1f, 1f, bw - 1f, bh - 1f)
            val r = if (radius > 0f) radius else 4 * d
            c.drawRoundRect(rect, r, r, pEdit)
        }
        c.restore()
    }

    private fun drawGraph(c: Canvas, x: Float, top: Float, boxW: Float, acc: Int) {
        var mx = 60
        for (v in Metrics.fpsHist) if (v > mx) mx = v
        val n = Metrics.fpsHist.size
        val pos = Metrics.histPos
        path.rewind()
        fillPath.rewind()
        val w = boxW - 2 * x
        for (i in 0 until n) {
            val v = Metrics.fpsHist[(pos + i) % n]
            val px = x + w * i / (n - 1)
            val py = top + gh - gh * v / mx
            if (i == 0) {
                path.moveTo(px, py)
                fillPath.moveTo(px, top + gh)
                fillPath.lineTo(px, py)
            } else {
                path.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(x + w, top + gh)
        fillPath.close()
        pFill.color = (acc and 0x00FFFFFF) or 0x33000000
        c.drawPath(fillPath, pFill)
        pLine.color = acc
        c.drawPath(path, pLine)
    }
}
