package app.astral.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** dp scaled by the user's "UI Scale" setting (menu only). Never smaller than 1 px. */
fun Context.ui(v: Int): Int = maxOf(1, (v * resources.displayMetrics.density * Cfg.uiScale / 100f).toInt())
fun Context.uif(v: Float): Float = v * resources.displayMetrics.density * Cfg.uiScale / 100f

/** Module card: faint fill + 1dp hairline. */
fun Context.card(radiusDp: Float): GradientDrawable =
    GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = uif(radiusDp); setStroke(maxOf(1, dp(1)), 0x26FFFFFF) }

fun Context.pill(color: Int, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }

/**
 * Dark glass: vertical translucent gradient, 20dp corners (default), 1dp hairline in white at 20 %.
 * [blurred] = a real cross-window blur sits behind the panel, so the fill can be lighter.
 */
fun Context.glass(radiusDp: Float = 20f, blurred: Boolean = false): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(if (blurred) Theme.GLASS_TOP_B else Theme.GLASS_TOP, if (blurred) Theme.GLASS_BOT_B else Theme.GLASS_BOT)
    ).apply {
        cornerRadius = dpf(radiusDp)
        setStroke(maxOf(1, dp(1)), Theme.STROKE)
    }

/** Subtle press feedback; does not consume the touch, so clicks still fire. */
fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}

/** Damped spring curve: starts fast, overshoots a little and settles at 1.0 (used for the 300 ms menu slide-in). */
class SpringInterpolator(private val damping: Float = 6f, private val cycles: Float = 1.1f) : TimeInterpolator {
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        if (t <= 0f) return 0f
        return (1.0 - Math.exp((-damping * t).toDouble()) * Math.cos(2.0 * Math.PI * cycles * t)).toFloat()
    }
}

/** Smoothly reveals a view (height + fade). The view must already be attached with LayoutParams. */
fun View.expandAnimated(animate: Boolean) {
    (tag as? ValueAnimator)?.cancel()
    val lp = layoutParams ?: return
    val par = parent as? View
    if (!animate || par == null || par.width <= 0) {
        visibility = View.VISIBLE
        alpha = 1f
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = lp
        return
    }
    val wSpec = View.MeasureSpec.makeMeasureSpec(maxOf(1, par.width - par.paddingLeft - par.paddingRight), View.MeasureSpec.EXACTLY)
    measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val target = measuredHeight
    lp.height = 0
    layoutParams = lp
    visibility = View.VISIBLE
    alpha = 0f
    val a = ValueAnimator.ofInt(0, target)
    a.duration = 240
    a.interpolator = DecelerateInterpolator()
    a.addUpdateListener {
        lp.height = it.animatedValue as Int
        alpha = it.animatedFraction
        layoutParams = lp
    }
    a.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            alpha = 1f
            layoutParams = lp
        }
    })
    tag = a
    a.start()
}

/** Smoothly hides a view (height + fade), then sets it GONE. */
fun View.collapseAnimated(animate: Boolean) {
    (tag as? ValueAnimator)?.cancel()
    val lp = layoutParams ?: return
    val start = height
    if (!animate || start <= 0) {
        visibility = View.GONE
        alpha = 1f
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = lp
        return
    }
    val a = ValueAnimator.ofInt(start, 0)
    a.duration = 200
    a.interpolator = DecelerateInterpolator()
    a.addUpdateListener {
        lp.height = it.animatedValue as Int
        alpha = 1f - it.animatedFraction
        layoutParams = lp
    }
    a.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            visibility = View.GONE
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            alpha = 1f
            layoutParams = lp
        }
    })
    tag = a
    a.start()
}

object Fonts {
    val names = listOf("Sans", "Bold", "Mono", "Light", "Cond", "Serif", "Casual")
    private val cache = arrayOfNulls<Typeface>(names.size)

    fun get(i: Int): Typeface {
        val k = i.coerceIn(0, names.size - 1)
        return cache[k] ?: make(k).also { cache[k] = it }
    }

    private fun make(i: Int): Typeface = when (i) {
        0 -> Typeface.create("sans-serif", Typeface.NORMAL)
        1 -> Typeface.create("sans-serif", Typeface.BOLD)
        2 -> Typeface.MONOSPACE
        3 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        4 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        5 -> Typeface.SERIF
        else -> Typeface.create("casual", Typeface.NORMAL)
    }
}

/** Animated on/off switch: spring thumb + colour fade to the accent. */
class Toggle(ctx: Context, on: Boolean, private val cb: (Boolean) -> Unit) : View(ctx) {
    private var checked = on
    private var prog = if (on) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val argb = ArgbEvaluator()
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
                a.duration = 240
                a.interpolator = SpringInterpolator(7f, 0.9f)
                a.addUpdateListener { prog = it.animatedValue as Float; invalidate() }
                anim = a
                a.start()
            }
            cb(checked)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.ui(44), context.ui(26))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        track.color = argb.evaluate(prog.coerceIn(0f, 1f), 0x33FFFFFF, Theme.accent()) as Int
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, h / 2, h / 2, track)
        val r = h / 2 - h * 0.12f
        c.drawCircle(h / 2 + (w - h) * prog, h / 2, r, thumb)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Round colour swatch; colour 0 means "auto". */
class Swatch(ctx: Context, val color: Int) : View(ctx) {
    var sel = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); this.color = Color.WHITE
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Theme.TEXT; textSize = ctx.dpf(11f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.ui(30), context.ui(30))

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - context.dpf(3f)
        if (color == 0) {
            fill.color = Theme.CHIP
            c.drawCircle(cx, cy, r, fill)
            c.drawText("A", cx, cy + txt.textSize * 0.35f, txt)
        } else {
            fill.color = color
            c.drawCircle(cx, cy, r, fill)
        }
        if (sel) c.drawCircle(cx, cy, r + context.dpf(1.5f), ring)
    }
}

/** Floating menu button (left of the in-game chat). Shows a check mark while widgets are being moved. */
class MenuButton(ctx: Context) : View(ctx) {
    var edit = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glyph = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        moveTo(48f, 14f); lineTo(78f, 82f); lineTo(66f, 82f); lineTo(60f, 68f); lineTo(36f, 68f)
        lineTo(30f, 82f); lineTo(18f, 82f); close()
        moveTo(48f, 36f); lineTo(41f, 56f); lineTo(55f, 56f); close()
    }
    private val tick = Path().apply { moveTo(24f, 50f); lineTo(42f, 68f); lineTo(74f, 30f) }

    init { background = ctx.glass(20f) }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(40), context.dp(40))

    override fun onDraw(c: Canvas) {
        val s = width / 96f
        c.save()
        c.scale(s, s)
        if (edit) {
            stroke.color = Theme.GREEN
            c.drawPath(tick, stroke)
        } else {
            fill.color = Theme.accent()
            c.drawPath(glyph, fill)
        }
        c.restore()
    }
}

/** Full-screen, non-touchable layer shown in "move mode": a faint grid plus the alignment lines the dragged widget snaps to. */
class GuideView(ctx: Context) : View(ctx) {
    private val xs = ArrayList<Int>()
    private val ys = ArrayList<Int>()
    private val grid = Paint().apply { color = 0x16FFFFFF; strokeWidth = 1f }
    private val axis = Paint().apply { color = 0x30FFFFFF; strokeWidth = ctx.dpf(1f) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x30FFFFFF; strokeWidth = ctx.dpf(1f)
    }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dpf(1.5f) }
    private val step = ctx.dpf(24f)
    private val margin = ctx.dpf(8f)

    fun set(x: List<Int>, y: List<Int>) {
        xs.clear(); xs.addAll(x)
        ys.clear(); ys.addAll(y)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        var x = 0f
        while (x <= w) { c.drawLine(x, 0f, x, h, grid); x += step }
        var y = 0f
        while (y <= h) { c.drawLine(0f, y, w, y, grid); y += step }
        c.drawLine(w / 2, 0f, w / 2, h, axis)
        c.drawLine(0f, h / 2, w, h / 2, axis)
        c.drawRect(margin, margin, w - margin, h - margin, frame)
        hot.color = Theme.accent()
        for (v in xs) c.drawLine(v.toFloat(), 0f, v.toFloat(), h, hot)
        for (v in ys) c.drawLine(0f, v.toFloat(), w, v.toFloat(), hot)
    }
}
