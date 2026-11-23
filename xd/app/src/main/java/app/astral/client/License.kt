package app.astral.client

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hwid {
    fun get(c: Context): String {
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        @Suppress("DEPRECATION")
        val serial = try { Build.SERIAL } catch (_: Throwable) { "unknown" }
        return MessageDigest.getInstance("SHA-256")
            .digest((id + serial).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** First 12 hex chars of the SHA-256 HWID, upper case, no dashes. Used for key binding. */
    fun short(c: Context): String = get(c).substring(0, 12).uppercase()

    /** Same, formatted XXXX-XXXX-XXXX for display. */
    fun display(c: Context): String = short(c).chunked(4).joinToString("-")
}

/**
 * Offline keys: ASTRAL-XXXX-XXXX = 8 base32 chars = 40 bits = [16 bit expiry in hours since 2026-01-01 UTC][24 bit HMAC].
 * HMAC-SHA256(secret, "<shortHwid>:<expiryHours>") truncated to 24 bits. Keys are generated with tools/keygen.py.
 */
object License {
    enum class R { OK, INVALID, EXPIRED }

    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val EPOCH_MS = 1767225600000L
    private val RE = Regex("^ASTRAL-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    fun formatOk(k: String) = RE.matches(k)

    private fun mac(short: String, exp: Int): Int {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(Secret.bytes(), "HmacSHA256"))
        val h = m.doFinal("$short:$exp".toByteArray())
        return ((h[0].toInt() and 0xFF) shl 16) or ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
    }

    /** Expiry time in ms if the key is genuine for this device, else -1. */
    private fun expiry(c: Context, key: String): Long {
        if (!formatOk(key)) return -1L
        var v = 0L
        for (ch in key.removePrefix("ASTRAL-").replace("-", "")) {
            val i = ALPHA.indexOf(ch)
            if (i < 0) return -1L
            v = (v shl 5) or i.toLong()
        }
        val exp = (v shr 24).toInt()
        if ((v and 0xFFFFFFL).toInt() != mac(Hwid.short(c), exp)) return -1L
        return EPOCH_MS + exp * 3_600_000L
    }

    fun verify(c: Context, key: String): R {
        Cfg.init(c)
        val e = expiry(c, key)
        if (e < 0) return R.INVALID
        val now = System.currentTimeMillis()
        if (now > e) return R.EXPIRED
        Cfg.licKey = key
        Cfg.licHwid = Hwid.short(c)
        Cfg.licTs = now
        return R.OK
    }

    /** True while the stored key is genuine, unexpired and the clock has not been rolled back. */
    fun cachedValid(c: Context): Boolean {
        Cfg.init(c)
        val k = Cfg.licKey ?: return false
        val e = expiry(c, k)
        if (e < 0) return false
        val now = System.currentTimeMillis()
        if (now > e || now < Cfg.licTs - 300_000L) return false
        Cfg.licTs = maxOf(Cfg.licTs, now)
        return true
    }

    fun daysLeft(c: Context): Int {
        val k = Cfg.licKey ?: return 0
        val e = expiry(c, k)
        if (e < 0) return 0
        return ((e - System.currentTimeMillis() + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
    }
}
