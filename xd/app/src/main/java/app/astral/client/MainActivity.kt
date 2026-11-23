package app.astral.client

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Splash / setup screen: logo, status, "Allow everything", then "Launch" (starts Minecraft + the overlay client). */
class MainActivity : Activity() {
    private lateinit var logo: ImageView
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var card: LinearLayout
    private lateinit var allow: TextView
    private lateinit var skip: TextView
    private lateinit var launch: TextView
    private lateinit var mcBox: LinearLayout
    private var pulse: ValueAnimator? = null
    private val h = Handler(Looper.getMainLooper())

    private var chain = false
    private var waiting = false
    private val asked = HashSet<Perms.P>()

    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val M = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        window.statusBarColor = Theme.BG_TOP
        window.navigationBarColor = Theme.BG_BOT
        build()
    }

    override fun onResume() {
        super.onResume()
        if (!License.cachedValid(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        advanceIfWaiting()
        refresh()
        // some OEM builds report a permission a moment after returning from its settings screen
        h.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600)
    }

    /** The system permission dialog (notifications, storage < 11) reports here; settings screens come back via onResume. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        advanceIfWaiting()
        refresh()
    }

    override fun onDestroy() {
        pulse?.cancel()
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun build() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(40), dp(24), dp(32))

        logo = ImageView(this)
        logo.setImageResource(R.drawable.astral_logo)
        col.addView(logo, LinearLayout.LayoutParams(dp(132), dp(132)))
        val pa = ValueAnimator.ofFloat(1f, 1.06f)
        pa.duration = 1600
        pa.repeatMode = ValueAnimator.REVERSE
        pa.repeatCount = ValueAnimator.INFINITE
        pa.addUpdateListener { logo.scaleX = it.animatedValue as Float; logo.scaleY = it.animatedValue as Float }
        pa.start()
        pulse = pa

        val title = TextView(this)
        title.text = "ASTRAL CLIENT"
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.16f
        title.setTextColor(Theme.accent())
        title.gravity = Gravity.CENTER
        col.addView(title, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(18) })

        status = TextView(this)
        status.textSize = 15f
        status.setTextColor(Theme.TEXT)
        status.gravity = Gravity.CENTER
        col.addView(status, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(8) })

        sub = TextView(this)
        sub.textSize = 12f
        sub.setTextColor(Theme.SUB)
        sub.gravity = Gravity.CENTER
        col.addView(sub, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(4) })

        card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = glass(20f)
        card.setPadding(dp(18), dp(14), dp(18), dp(14))
        col.addView(card, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(22) })

        // Minecraft missing: explanation + Play Store button
        mcBox = LinearLayout(this)
        mcBox.orientation = LinearLayout.VERTICAL
        mcBox.gravity = Gravity.CENTER_HORIZONTAL
        mcBox.background = glass(20f)
        mcBox.setPadding(dp(18), dp(14), dp(18), dp(16))
        val mcTitle = TextView(this)
        mcTitle.text = Str.t("mc_missing")
        mcTitle.textSize = 16f
        mcTitle.typeface = Typeface.DEFAULT_BOLD
        mcTitle.setTextColor(Theme.RED)
        mcTitle.gravity = Gravity.CENTER
        mcBox.addView(mcTitle, LinearLayout.LayoutParams(M, W))
        val mcHint = TextView(this)
        mcHint.text = Str.t("mc_hint")
        mcHint.textSize = 13f
        mcHint.setTextColor(Theme.SUB)
        mcHint.gravity = Gravity.CENTER
        mcBox.addView(mcHint, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(4) })
        mcBox.addView(button(Str.t("open_play"), true) { openPlay() }, LinearLayout.LayoutParams(M, dp(48)).apply { topMargin = dp(12) })
        col.addView(mcBox, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(16) })

        allow = button(Str.t("allow_all"), true) { startChain() }
        col.addView(allow, LinearLayout.LayoutParams(M, dp(54)).apply { topMargin = dp(22) })

        skip = button(Str.t("skip"), false) { skipStep() }
        col.addView(skip, LinearLayout.LayoutParams(M, dp(48)).apply { topMargin = dp(12) })

        launch = button(Str.t("launch"), true) { launchMinecraft() }
        col.addView(launch, LinearLayout.LayoutParams(M, dp(54)).apply { topMargin = dp(12) })

        val root = ScrollView(this)
        root.isFillViewport = true
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Theme.BG_TOP, Theme.BG_BOT))
        root.addView(col, ViewGroup.LayoutParams(M, W))
        setContentView(root)
    }

    private fun button(text: String, filled: Boolean, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setTextColor(if (filled) Theme.onAccent(Theme.accent()) else Theme.TEXT)
        t.background = pill(if (filled) Theme.accent() else Theme.CHIP, 16f)
        t.setPadding(dp(12), 0, dp(12), 0)
        t.setOnClickListener { click() }
        t.pressable()
        return t
    }

    private fun mcInstalled() = packageManager.getLaunchIntentForPackage(Fg.MC) != null

    private fun refresh() {
        val missing = Perms.all.count { !Perms.granted(this, it) }
        val overlay = Perms.granted(this, Perms.P.OVERLAY)
        val mcOk = mcInstalled()

        status.text = when {
            chain && waiting -> Str.t("st_wait")
            chain -> Str.t("st_busy")
            missing == 0 -> Str.t("st_ready")
            Cfg.permsAsked -> Str.t("st_left")
            else -> Str.t("st_need")
        }
        sub.text = if (!mcOk) Str.t("mc_missing") else "${Str.t("days_left")}: ${License.daysLeft(this)}"
        sub.setTextColor(if (!mcOk) Theme.RED else Theme.SUB)

        card.removeAllViews()
        for (p in Perms.all) {
            val ok = Perms.granted(this, p)
            val t = TextView(this)
            t.text = (if (ok) "✓   " else "○   ") + Perms.label(p)
            t.textSize = 14f
            t.setTextColor(if (ok) Theme.GREEN else Theme.SUB)
            t.setPadding(0, dp(5), 0, dp(5))
            card.addView(t)
        }

        mcBox.visibility = if (mcOk) View.GONE else View.VISIBLE
        allow.visibility = if (missing > 0 && !chain) View.VISIBLE else View.GONE
        skip.visibility = if (chain && waiting) View.VISIBLE else View.GONE
        // Launch needs the overlay permission and an installed Minecraft; the rest may be skipped after the "Allow everything" pass.
        val canLaunch = overlay && (missing == 0 || Cfg.permsAsked) && !chain && mcOk
        launch.visibility = if (canLaunch) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- permissions flow

    private fun startChain() {
        asked.clear()
        chain = true
        waiting = false
        next()
    }

    /** Called when we come back from a system screen / dialog. Idempotent: the first caller wins. */
    private fun advanceIfWaiting() {
        if (chain && waiting) {
            waiting = false
            next()
        }
    }

    /** "Skip this step": never leaves the user stuck on a settings screen that does not return by itself. */
    private fun skipStep() {
        if (chain && waiting) {
            waiting = false
            next()
        }
    }

    /** One tap walks through every missing permission; each system screen returns here via onResume / onRequestPermissionsResult. */
    private fun next() {
        val p = Perms.all.firstOrNull { !Perms.granted(this, it) && it !in asked }
        if (p == null) {
            chain = false
            waiting = false
            Cfg.permsAsked = true
            refresh()
            return
        }
        asked.add(p)
        if (Perms.request(this, p)) {
            waiting = true
            refresh()
        } else {
            next()
        }
    }

    // ---------------------------------------------------------------- launch

    private fun openPlay() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Fg.MC}")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Fg.MC}")))
            } catch (_: Exception) {
                Toast.makeText(this, Str.t("mc_missing"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchMinecraft() {
        if (!Settings.canDrawOverlays(this)) {
            Perms.request(this, Perms.P.OVERLAY)
            return
        }
        val mc = packageManager.getLaunchIntentForPackage(Fg.MC)
        if (mc == null) {
            Toast.makeText(this, Str.t("mc_missing"), Toast.LENGTH_LONG).show()
            refresh()
            return
        }
        Fg.reset()
        startForegroundService(Intent(this, OverlayService::class.java))
        mc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(mc)
    }
}
