package app.astral.client

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Export / import of profiles as .json files in Download/AstralClient.
 * Export writes the active profile; import validates the file and adds it as a NEW profile (nothing is overwritten).
 * The license and the permission flags are never written and never read.
 */
object ConfigIO {
    private const val REL = "Download/AstralClient"
    private const val MAX_BYTES = 256 * 1024

    class Entry(val name: String, val file: File?, val uri: Uri?)

    sealed class Res {
        class Ok(val name: String) : Res()
        class Err(val message: String) : Res()
    }

    @Suppress("DEPRECATION")
    private fun dir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AstralClient")

    private fun errText(code: String, arg: String = ""): String = Str.t(code) + if (arg.isNotEmpty()) ": $arg" else ""

    private fun fileName(): String {
        val a = Profiles.active
        val base = if (a == Profiles.DEFAULT) "default" else a.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().ifEmpty { "profile" }
        return "astral-$base.json"
    }

    /** Returns a human-readable path on success, null on failure. */
    fun export(c: Context): String? {
        val text = Cfg.dump(Profiles.activeLabel()).toString(2)
        val name = fileName()
        try {
            val f = File(dir(), name)
            f.parentFile?.mkdirs()
            f.writeText(text)
            return "$REL/$name"
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, REL)
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
                c.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
                return "$REL/$name"
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** .json files in Download/AstralClient, newest first (plain files, plus MediaStore entries when direct access is not granted). */
    fun list(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        try {
            val all = dir().listFiles()
            if (all != null) {
                val js = all.filter { it.isFile && it.name.endsWith(".json", true) }.sortedByDescending { it.lastModified() }
                for (f in js) {
                    out.add(Entry(f.name, f, null))
                    seen.add(f.name)
                }
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                c.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("$REL%", "%.json"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC"
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val n = cur.getString(1) ?: continue
                        if (n in seen) continue
                        seen.add(n)
                        out.add(Entry(n, null, ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0))))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out.take(30)
    }

    private fun read(c: Context, e: Entry): String? {
        return try {
            val f = e.file
            if (f != null) {
                if (f.length() > MAX_BYTES) return "\u0000big"
                f.readText()
            } else {
                val u = e.uri ?: return null
                c.contentResolver.openInputStream(u)?.use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.size > MAX_BYTES) "\u0000big" else bytes.toString(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Validates the file, then adds it as a new profile and switches to it. */
    fun import(c: Context, e: Entry): Res {
        val text = read(c, e) ?: return Res.Err(errText("err_read"))
        if (text == "\u0000big") return Res.Err(errText("err_big"))
        return when (val p = Cfg.parse(text)) {
            is Parsed.Err -> Res.Err(errText(p.code, p.arg))
            is Parsed.Ok -> {
                val base = p.name ?: e.name.removeSuffix(".json").removePrefix("astral-")
                val (name, err) = Profiles.addImported(p.entries, base)
                if (name != null) Res.Ok(Profiles.label(name)) else Res.Err(errText(err ?: "err_read"))
            }
        }
    }
}
