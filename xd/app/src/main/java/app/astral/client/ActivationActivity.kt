package app.astral.client

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ActivationActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var btn: TextView
    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private var locked = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        window.statusBarColor = Theme.BG_TOP
        window.navigationBarColor = Theme.BG_BOT
        val hwid = Hwid.display(this)
        val M = ViewGroup.LayoutParams.MATCH_PARENT
        val W = ViewGroup.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
        col.addView(ImageView(this).apply { setImageResource(R.drawable.astral_logo) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        col.addView(TextView(this).apply {
            text = "ASTRAL CLIENT"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.16f
            setTextColor(Theme.accent()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(14) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(20f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        card.addView(TextView(this).apply { text = Str.t("hwid"); textSize = 12f; setTextColor(Theme.SUB); gravity = Gravity.CENTER })
        card.addView(TextView(this).apply {
            text = hwid; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("hwid", hwid))
                Toast.makeText(this@ActivationActivity, Str.t("copied"), Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(TextView(this).apply { text = Str.t("tap_copy"); textSize = 11f; setTextColor(Theme.SUB); gravity = Gravity.CENTER })
        col.addView(card, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(24) })

        input = EditText(this).apply {
            hint = "ASTRAL-XXXX-XXXX"; setHintTextColor(Theme.SUB); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            gravity = Gravity.CENTER; setSingleLine(); textSize = 16f
            background = pill(Theme.CHIP, 14f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        col.addView(input, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(20) })

        btn = TextView(this).apply {
            text = Str.t("activate"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Theme.onAccent(Theme.accent())); background = pill(Theme.accent(), 16f)
            setOnClickListener { submit() }
            pressable()
        }
        col.addView(btn, LinearLayout.LayoutParams(M, dp(54)).apply { topMargin = dp(14) })

        status = TextView(this).apply { setTextColor(Theme.SUB); gravity = Gravity.CENTER; textSize = 13f }
        col.addView(status, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(12) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Theme.BG_TOP, Theme.BG_BOT))
            addView(col, ViewGroup.LayoutParams(M, W))
        })
    }

    private fun submit() {
        if (locked) return
        val k = input.text.toString().trim().uppercase()
        when (License.verify(this, k)) {
            License.R.OK -> { startActivity(Intent(this, MainActivity::class.java)); finish() }
            License.R.INVALID -> lock(30, "invalid")
            License.R.EXPIRED -> lock(30, "expired")
        }
    }

    private fun lock(sec: Int, key: String) {
        if (sec <= 1) {
            status.text = Str.t(key)
            locked = false
            btn.alpha = 1f
            return
        }
        locked = true
        btn.alpha = 0.4f
        status.text = "${Str.t(key)} — ${Str.t("retry")} $sec"
        h.postDelayed({ lock(sec - 1, key) }, 1000)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
