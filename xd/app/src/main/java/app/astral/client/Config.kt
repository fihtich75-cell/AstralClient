package app.astral.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** HUD widgets that exist outside the game process (device data only). */
object Widgets {
    val ids = listOf("fps", "ping", "cps", "timer", "battery", "clock", "btemp")
    fun defOn(id: String) = id == "fps" || id == "ping" || id == "cps" || id == "timer"
    fun defX(c: Context, id: String) = c.dp(8)
    fun defY(c: Context, id: String) = c.dp(8) + ids.indexOf(id) * c.dp(36)
}

/** Which settings keys exist, their types and legal ranges. Used for import validation. */
object Schema {
    private val WIDGET = Regex("^w_(fps|ping|cps|timer|battery|clock|btemp)_([a-z]+)$")
    private val WINT = setOf("x", "y", "alpha", "scale", "font", "color", "style")
    private val WBOOL = setOf("on", "graph")

    /** 'b' boolean, 'i' int, 's' string; null for unknown keys. */
    fun type(k: String): Char? = when (k) {
        "lang", "ping_host" -> 's'
        "perf", "edge", "snap" -> 'b'
        "accent", "btnx", "btny", "ping_port", "ui_scale" -> 'i'
        else -> {
            val m = WIDGET.matchEntire(k)
            if (m == null) null else {
                val f = m.groupValues[2]
                if (f in WINT) 'i' else if (f in WBOOL) 'b' else null
            }
        }
    }

    fun clampInt(k: String, v: Int): Int = when {
        k == "ui_scale" -> v.coerceIn(70, 150)
        k == "ping_port" -> v.coerceIn(1, 65535)
        k == "accent" -> v or 0xFF000000.toInt()
        k == "btnx" || k == "btny" -> v.coerceIn(-1, 20000)
        k.endsWith("_x") || k.endsWith("_y") -> v.coerceIn(0, 20000)
        k.endsWith("_alpha") -> v.coerceIn(10, 100)
        k.endsWith("_scale") -> v.coerceIn(40, 300)
        k.endsWith("_font") -> v.coerceIn(0, Fonts.names.size - 1)
        k.endsWith("_style") -> v.coerceIn(0, 2)
        else -> v
    }

    fun cleanString(k: String, v: String): String? = when (k) {
        "lang" -> if (v == "ru" || v == "en") v else null
        "ping_host" ->
            if (v.length in 1..253 && v.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '_' }) v else null
        else -> null
    }
}

/** Result of parsing a config file / profile snapshot. */
sealed class Parsed {
    class Ok(val entries: Map<String, Any>, val name: String?) : Parsed()
    class Err(val code: String, val arg: String = "") : Parsed()
}

object Cfg {
    private lateinit var p: SharedPreferences
    private var defHost = "geo.hivebedrock.network"
    private var defPort = 19132

    fun init(c: Context) {
        if (::p.isInitialized) return
        p = c.applicationContext.getSharedPreferences("astral", Context.MODE_PRIVATE)
        Profiles.init(c)
        try {
            val j = JSONObject(c.assets.open("config.json").bufferedReader().use { it.readText() })
            defHost = j.optString("ping_host", defHost)
            defPort = j.optInt("ping_port", defPort)
        } catch (_: Exception) {
        }
    }

    var lang: String
        get() = p.getString("lang", "ru") ?: "ru"
        set(v) { p.edit().putString("lang", v).apply() }
    var perf: Boolean
        get() = p.getBoolean("perf", false)
        set(v) { p.edit().putBoolean("perf", v).apply() }
    var accent: Int
        get() = p.getInt("accent", Theme.DEFAULT_ACCENT) or 0xFF000000.toInt()
        set(v) { p.edit().putInt("accent", v or 0xFF000000.toInt()).apply() }
    var edge: Boolean
        get() = p.getBoolean("edge", true)
        set(v) { p.edit().putBoolean("edge", v).apply() }
    var snap: Boolean
        get() = p.getBoolean("snap", true)
        set(v) { p.edit().putBoolean("snap", v).apply() }
    var btnX: Int
        get() = p.getInt("btnx", -1)
        set(v) { p.edit().putInt("btnx", v).apply() }
    var btnY: Int
        get() = p.getInt("btny", -1)
        set(v) { p.edit().putInt("btny", v).apply() }
    var pingHost: String
        get() = p.getString("ping_host", defHost) ?: defHost
        set(v) { p.edit().putString("ping_host", v).apply() }
    var pingPort: Int
        get() = p.getInt("ping_port", defPort)
        set(v) { p.edit().putInt("ping_port", v).apply() }
    var uiScale: Int
        get() = p.getInt("ui_scale", 100).coerceIn(70, 150)
        set(v) { p.edit().putInt("ui_scale", v.coerceIn(70, 150)).apply() }

    // permission-flow flags (never exported, never part of a profile)
    var permsAsked: Boolean
        get() = p.getBoolean("perms_asked", false)
        set(v) { p.edit().putBoolean("perms_asked", v).apply() }
    var notifAsked: Boolean
        get() = p.getBoolean("perms_notif_asked", false)
        set(v) { p.edit().putBoolean("perms_notif_asked", v).apply() }
    var autostartDone: Boolean
        get() = p.getBoolean("autostart_done", false)
        set(v) { p.edit().putBoolean("autostart_done", v).apply() }

    // per-widget settings
    fun wInt(id: String, k: String, def: Int) = p.getInt("w_${id}_$k", def)
    fun wSet(id: String, k: String, v: Int) { p.edit().putInt("w_${id}_$k", v).apply() }
    fun wBool(id: String, k: String, def: Boolean) = p.getBoolean("w_${id}_$k", def)
    fun wSetBool(id: String, k: String, v: Boolean) { p.edit().putBoolean("w_${id}_$k", v).apply() }
    fun wClearPos(id: String) { p.edit().remove("w_${id}_x").remove("w_${id}_y").apply() }

    // license cache (kept separate from resettable settings)
    var licKey: String?
        get() = p.getString("lic_key", null)
        set(v) { p.edit().putString("lic_key", v).apply() }
    var licHwid: String?
        get() = p.getString("lic_hwid", null)
        set(v) { p.edit().putString("lic_hwid", v).apply() }
    var licTs: Long
        get() = p.getLong("lic_ts", 0L)
        set(v) { p.edit().putLong("lic_ts", v).apply() }

    private fun protectedKey(k: String) = k.startsWith("lic_") || k.startsWith("perms_") || k == "autostart_done"

    private fun typed(v: Any): JSONObject? {
        val e = JSONObject()
        return when (v) {
            is Boolean -> e.put("t", "b").put("v", v)
            is Int -> e.put("t", "i").put("v", v)
            is String -> e.put("t", "s").put("v", v)
            else -> null
        }
    }

    /** Wraps validated entries into the on-disk format. */
    fun toJson(entries: Map<String, Any>, profile: String? = null): JSONObject {
        val s = JSONObject()
        for ((k, v) in entries) {
            val e = typed(v) ?: continue
            s.put(k, e)
        }
        val o = JSONObject().put("app", "astral").put("version", 2)
        if (profile != null) o.put("profile", profile)
        return o.put("settings", s)
    }

    /** Current settings (never the license cache or permission flags) in the config-file format. */
    fun dump(profile: String? = null): JSONObject {
        val m = LinkedHashMap<String, Any>()
        for ((k, v) in p.all) {
            if (v == null || protectedKey(k) || Schema.type(k) == null) continue
            m[k] = v
        }
        return toJson(m, profile)
    }

    /** Validates a config file. Unknown keys are ignored; known keys must have the right type. */
    fun parse(text: String): Parsed {
        val j = try { JSONObject(text) } catch (_: Exception) { return Parsed.Err("err_json") }
        if (j.optString("app") != "astral") return Parsed.Err("err_app")
        val ver = j.optInt("version", -1)
        if (ver < 1 || ver > 2) return Parsed.Err("err_ver", if (ver < 0) "?" else ver.toString())
        val s = j.optJSONObject("settings") ?: return Parsed.Err("err_settings")
        val out = LinkedHashMap<String, Any>()
        val keys = s.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (protectedKey(k)) continue
            val t = Schema.type(k) ?: continue
            val o = s.optJSONObject(k) ?: return Parsed.Err("err_value", k)
            if (o.optString("t") != t.toString()) return Parsed.Err("err_value", k)
            val raw = o.opt("v")
            if (t == 'b') {
                val b = raw as? Boolean ?: return Parsed.Err("err_value", k)
                out[k] = b
            } else if (t == 'i') {
                val n: Int = when (raw) {
                    is Int -> raw
                    is Long -> if (raw >= Int.MIN_VALUE.toLong() && raw <= Int.MAX_VALUE.toLong()) raw.toInt() else return Parsed.Err("err_value", k)
                    else -> return Parsed.Err("err_value", k)
                }
                out[k] = Schema.clampInt(k, n)
            } else {
                val str = raw as? String ?: return Parsed.Err("err_value", k)
                val clean = Schema.cleanString(k, str) ?: return Parsed.Err("err_value", k)
                out[k] = clean
            }
        }
        if (s.length() > 0 && out.isEmpty()) return Parsed.Err("err_empty")
        val name = j.optString("profile", "").trim().ifEmpty { null }
        return Parsed.Ok(out, name)
    }

    /** Writes validated entries; with clear=true every user setting is removed first. */
    fun write(entries: Map<String, Any>, clear: Boolean) {
        val e = p.edit()
        if (clear) {
            for (k in p.all.keys) if (!protectedKey(k)) e.remove(k)
        }
        for ((k, v) in entries) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
                else -> {}
            }
        }
        e.apply()
    }

    /** Factory reset: everything except the license cache and the permission-flow flags. */
    fun resetSettings() = write(emptyMap(), true)
}

/**
 * Several settings profiles. The live settings always sit in the main preferences; a profile is a
 * stored snapshot that is refreshed when the user leaves it. The license and permission flags are not part of any profile.
 */
object Profiles {
    const val DEFAULT = "Default"
    const val MAX = 16
    const val NAME_MAX = 24

    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.applicationContext.getSharedPreferences("astral_profiles", Context.MODE_PRIVATE)
        if (!sp.contains("names")) {
            sp.edit().putString("names", JSONArray().put(DEFAULT).toString()).putString("active", DEFAULT).apply()
        }
    }

    fun names(): List<String> {
        val l = ArrayList<String>()
        try {
            val a = JSONArray(sp.getString("names", "[]"))
            for (i in 0 until a.length()) {
                val n = a.optString(i)
                if (n.isNotEmpty() && n !in l) l.add(n)
            }
        } catch (_: Exception) {
        }
        if (DEFAULT !in l) l.add(0, DEFAULT)
        return l
    }

    private fun saveNames(l: List<String>) {
        val a = JSONArray()
        for (n in l) a.put(n)
        sp.edit().putString("names", a.toString()).apply()
    }

    var active: String
        get() {
            val a = sp.getString("active", DEFAULT) ?: DEFAULT
            return if (a in names()) a else DEFAULT
        }
        private set(v) { sp.edit().putString("active", v).apply() }

    fun label(n: String) = if (n == DEFAULT) Str.t("profile_default") else n

    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(NAME_MAX)

    /** Returns a Str key describing why the name is unusable, or null when it is fine. */
    fun check(raw: String, except: String? = null): String? {
        val n = clean(raw)
        if (n.isEmpty()) return "p_empty"
        if (n.equals(DEFAULT, true) || n.equals(Str.t("profile_default"), true)) return "p_exists"
        if (names().any { it != except && it.equals(n, true) }) return "p_exists"
        return null
    }

    private fun snapshot() {
        sp.edit().putString("p:$active", Cfg.dump().toString()).apply()
    }

    private fun uniqueName(base: String): String {
        val b = clean(base).ifEmpty { "Profile" }
        if (check(b) == null) return b
        var i = 2
        while (true) {
            val suffix = " ($i)"
            val n = b.take(NAME_MAX - suffix.length) + suffix
            if (check(n) == null) return n
            i++
        }
    }

    /** New profile = a copy of the current settings; it becomes active. Returns an error key or null. */
    fun create(raw: String): String? {
        check(raw)?.let { return it }
        val l = names()
        if (l.size >= MAX) return "p_limit"
        val n = clean(raw)
        snapshot()
        sp.edit().putString("p:$n", Cfg.dump().toString()).apply()
        saveNames(l + n)
        active = n
        return null
    }

    fun switchTo(n: String) {
        if (n == active || n !in names()) return
        snapshot()
        val txt = sp.getString("p:$n", null)
        val parsed = if (txt != null) Cfg.parse(txt) else null
        Cfg.write(if (parsed is Parsed.Ok) parsed.entries else emptyMap(), true)
        active = n
    }

    fun rename(old: String, raw: String): String? {
        if (old == DEFAULT) return "p_exists"
        check(raw, old)?.let { return it }
        val n = clean(raw)
        val txt = sp.getString("p:$old", null)
        val e = sp.edit().remove("p:$old")
        if (txt != null) e.putString("p:$n", txt)
        e.apply()
        val wasActive = active == old
        saveNames(names().map { if (it == old) n else it })
        if (wasActive) active = n
        return null
    }

    fun delete(n: String) {
        if (n == DEFAULT) return
        if (active == n) switchTo(DEFAULT)
        saveNames(names().filter { it != n })
        sp.edit().remove("p:$n").apply()
    }

    /** Adds an imported config as a new profile and switches to it. Returns (name, null) or (null, errorKey). */
    fun addImported(entries: Map<String, Any>, base: String): Pair<String?, String?> {
        val l = names()
        if (l.size >= MAX) return Pair(null, "p_limit")
        val n = uniqueName(base)
        snapshot() // keep the profile we are leaving
        sp.edit().putString("p:$n", Cfg.toJson(entries).toString()).apply()
        saveNames(l + n)
        Cfg.write(entries, true)
        active = n
        return Pair(n, null)
    }

    fun activeLabel(): String = label(active)
}

object Theme {
    const val GREEN = 0xFF69F0AE.toInt()
    const val YELLOW = 0xFFFFD740.toInt()
    const val RED = 0xFFFF5252.toInt()
    const val TEXT = 0xFFEDEDF5.toInt()
    const val SUB = 0xFF9C9CB4.toInt()
    const val CHIP = 0x1FFFFFFF
    const val STROKE = 0x33FFFFFF // 1dp white at 20 %
    const val GLASS_TOP = 0xD91E1C34.toInt()
    const val GLASS_BOT = 0xCC0D0D16.toInt()
    const val GLASS_TOP_B = 0x8C1E1C34.toInt() // lighter fill when the real blur is behind the panel
    const val GLASS_BOT_B = 0x990D0D16.toInt()
    const val WIDGET_BG = 0xB3101018.toInt()
    const val BG_TOP = 0xFF0B0B12.toInt()
    const val BG_BOT = 0xFF181228.toInt()
    const val DEFAULT_ACCENT = 0xFF7C4DFF.toInt()

    /** 8 accent presets. */
    val presets = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFF448AFF.toInt(), 0xFF18FFFF.toInt(), 0xFF69F0AE.toInt(),
        0xFFFFD740.toInt(), 0xFFFFAB40.toInt(), 0xFFFF5252.toInt(), 0xFFFF4081.toInt()
    )

    /** Widget colour choices; 0 means "automatic". */
    val swatches = intArrayOf(
        0, 0xFFFFFFFF.toInt(), 0xFF7C4DFF.toInt(), 0xFF18FFFF.toInt(), 0xFF69F0AE.toInt(),
        0xFFFFD740.toInt(), 0xFFFFAB40.toInt(), 0xFFFF5252.toInt(), 0xFFFF4081.toInt(), 0xFFB0BEC5.toInt()
    )

    fun accent(): Int = Cfg.accent

    private fun lum(c: Int): Float =
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    /** Text/icon colour that stays readable on top of the accent colour. */
    fun onAccent(c: Int): Int = if (lum(c) > 0.62f) 0xFF101018.toInt() else 0xFFFFFFFF.toInt()

    fun tooDark(c: Int): Boolean = lum(c) < 0.2f

    /** "#RRGGBB", "RRGGBB" or "#RGB" -> opaque ARGB, or null when it is not a valid colour. */
    fun parseHex(raw: String): Int? {
        var s = raw.trim().removePrefix("#")
        if (s.length == 3) s = "" + s[0] + s[0] + s[1] + s[1] + s[2] + s[2]
        if (s.length != 6 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return s.toLong(16).toInt() or 0xFF000000.toInt()
    }

    fun hex(c: Int): String = "#%06X".format(c and 0xFFFFFF)
}
